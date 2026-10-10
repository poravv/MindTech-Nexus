package com.mindtechpy.nexus.application.saga.catalog;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.EntityMappingRepository;
import com.mindtechpy.nexus.application.port.out.GiftHubPort;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.mapping.EntityType;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.System.Logger.Level.INFO;

/**
 * {@code [NEXUS-CATALOG]} full catalog push POS → GiftHub (spec §10.2). One step {@code SYNC_PAGE} per POS
 * page of {@value #PAGE_SIZE} (the POS and GiftHub bulk maximum): read the page, upsert it with
 * {@code PUT /products/bulk}, record the counts and move to the next page until the POS total is covered.
 * A failure retries the same page (both calls are idempotent), never the pages already pushed. No
 * compensation: an upsert leaves nothing to undo. Products the POS stops listing are not archived in v1.
 */
public class CatalogSyncSaga implements Saga {

    static final String SYNC_PAGE = "SYNC_PAGE";
    static final int PAGE_SIZE = 500;
    static final String DEFAULT_CATEGORY = "General";
    private static final int ERROR_SAMPLES = 10;

    private static final System.Logger log = System.getLogger(CatalogSyncSaga.class.getName());

    private final PosPort pos;
    private final GiftHubPort giftHub;
    private final EntityMappingRepository mappings;

    public CatalogSyncSaga(PosPort pos, GiftHubPort giftHub, EntityMappingRepository mappings) {
        this.pos = pos;
        this.giftHub = giftHub;
        this.mappings = mappings;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.CATALOG_SYNC;
    }

    @Override
    public String firstStep() {
        return SYNC_PAGE;
    }

    @Override
    public StepResult execute(StepContext context) {
        Operation op = context.operation();
        if (context.source() == null || context.source().connection().connector() != ConnectorType.POS
                || context.target().connection().connector() != ConnectorType.GIFTHUB) {
            return new StepResult.Failed(ConnectorError.of(ErrorKind.REJECTED, "integration-misconfigured",
                    "La integración CATALOG debe tener el POS como origen y GiftHub como destino"));
        }
        Map<String, Object> result = new HashMap<>(op.result() == null ? Map.of() : op.result());
        int page = ((Number) result.getOrDefault("nextPage", 1)).intValue();
        boolean dryRun = Boolean.TRUE.equals(context.target().connection().settings().get("dryRun"));
        Duration readTimeout = Duration.ofMillis(context.integration().policy().readTimeoutMs());
        try {
            PosPort.CatalogPage catalog = pos.fetchCatalog(context.source(), readTimeout, page, PAGE_SIZE);
            GiftHubPort.BulkResult pushed = catalog.items().isEmpty()
                    ? new GiftHubPort.BulkResult(0, 0, 0, List.of(), Map.of())
                    : giftHub.upsertProducts(context.target(), readTimeout, catalog.items().stream().map(CatalogSyncSaga::product).toList(), dryRun);
            if (!dryRun && !pushed.productIds().isEmpty()) {
                mappings.saveAll(op.tenantId(), context.target().connection().id(), EntityType.PRODUCT, pushed.productIds());
            }
            accumulate(result, page, catalog, pushed, dryRun);
            boolean more = (long) page * PAGE_SIZE < catalog.total() && !catalog.items().isEmpty();
            log.log(INFO, "[NEXUS-CATALOG] op={0} tenant={1} página {2}: {3} productos, creados={4} actualizados={5} errores={6}{7}",
                    op.id(), op.tenantId(), page, catalog.items().size(), pushed.created(), pushed.updated(),
                    pushed.errors().size(), dryRun ? " (dry-run)" : "");
            if (more) {
                result.put("nextPage", page + 1);
                return new StepResult.Next(SYNC_PAGE, null, "page-" + page, result);
            }
            result.remove("nextPage");
            return new StepResult.Completed("page-" + page, result);
        } catch (ConnectorException e) {
            return new StepResult.Failed(e.error());
        }
    }

    /**
     * POS → GiftHub product. {@code available = !tracked || stock > 0}; GiftHub stock is an integer copy
     * (fractions are rounded down) or null when the POS sells without stock control. Only {@code https}
     * images are sent: GiftHub downloads nothing else, and omitting the field keeps the current photo.
     */
    static GiftHubPort.Product product(PosPort.CatalogItem item) {
        BigDecimal stock = item.stockQty() == null ? BigDecimal.ZERO : item.stockQty().max(BigDecimal.ZERO);
        Long stockQty = item.tracked() ? stock.setScale(0, RoundingMode.DOWN).longValueExact() : null;
        String imageUrl = item.imageUrl() != null && item.imageUrl().startsWith("https://") ? item.imageUrl() : null;
        String category = item.category() == null || item.category().isBlank() ? DEFAULT_CATEGORY : truncate(item.category(), 60);
        return new GiftHubPort.Product(item.code(), truncate(item.name(), 120), category,
                item.price().setScale(0, RoundingMode.HALF_UP).longValueExact(), !item.tracked() || stock.signum() > 0,
                stockQty, imageUrl);
    }

    private static void accumulate(Map<String, Object> result, int page, PosPort.CatalogPage catalog,
                                   GiftHubPort.BulkResult pushed, boolean dryRun) {
        result.put("pages", page);
        result.put("total", catalog.total());
        result.put("dryRun", dryRun);
        add(result, "fetched", catalog.items().size());
        add(result, "created", pushed.created());
        add(result, "updated", pushed.updated());
        add(result, "stale", pushed.stale());
        add(result, "errors", pushed.errors().size());
        List<Object> samples = new ArrayList<>(result.get("errorSamples") instanceof List<?> list ? list : List.of());
        pushed.errors().stream().limit(Math.max(0, ERROR_SAMPLES - samples.size()))
                .forEach(error -> samples.add(Map.of("externalId", String.valueOf(error.externalId()), "error", String.valueOf(error.error()))));
        result.put("errorSamples", samples);
    }

    private static void add(Map<String, Object> result, String key, int value) {
        result.put(key, ((Number) result.getOrDefault(key, 0)).longValue() + value);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
