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
import java.util.UUID;

import static java.lang.System.Logger.Level.INFO;

/**
 * {@code [NEXUS-CATALOG]} full catalog push POS → GiftHub (spec §10.2). One step {@code SYNC_PAGE} per POS
 * page of {@value #PAGE_SIZE} (the POS and GiftHub bulk maximum): read the page, upsert it with
 * {@code PUT /products/bulk}, record the counts and move to the next page until the POS total is covered.
 * A failure retries the same page (both calls are idempotent), never the pages already pushed. No
 * compensation: an upsert leaves nothing to undo.
 * <p>
 * Every page marks its POS codes as seen by the run (its start time). After the last page,
 * {@code ARCHIVE_MISSING} records a miss on the mapped products this run did not see, and takes off sale in
 * GiftHub (forgetting their mappings) only those the previous complete run had missed too, so the next run that
 * sees them again puts them back on sale. Two misses in a row are required because the POS only pages by offset
 * ({@code page}/{@code limit} ordered by code, no keyset cursor): a product created or retired while a run pages
 * shifts the rest, and one run can skip a product it still sells. A run whose POS catalog came back empty
 * archives nothing: an empty catalog is far likelier a POS fault than a shop with nothing to sell.
 */
public class CatalogSyncSaga implements Saga {

    static final String SYNC_PAGE = "SYNC_PAGE";
    static final String ARCHIVE_MISSING = "ARCHIVE_MISSING";
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
        if (ARCHIVE_MISSING.equals(context.step())) {
            return archiveMissing(context, result);
        }
        int page = ((Number) result.getOrDefault("nextPage", 1)).intValue();
        boolean dryRun = Boolean.TRUE.equals(context.target().connection().settings().get("dryRun"));
        Duration readTimeout = Duration.ofMillis(context.integration().policy().readTimeoutMs());
        try {
            PosPort.CatalogPage catalog = pos.fetchCatalog(context.source(), readTimeout, page, PAGE_SIZE);
            GiftHubPort.BulkResult pushed = catalog.items().isEmpty()
                    ? new GiftHubPort.BulkResult(0, 0, 0, List.of(), Map.of())
                    : giftHub.upsertProducts(context.target(), readTimeout, catalog.items().stream().map(CatalogSyncSaga::product).toList(), dryRun);
            if (!dryRun) {
                UUID giftHubId = context.target().connection().id();
                mappings.saveAll(op.tenantId(), giftHubId, EntityType.PRODUCT, pushed.productIds());
                mappings.markSeen(op.tenantId(), giftHubId, EntityType.PRODUCT,
                        catalog.items().stream().map(PosPort.CatalogItem::code).toList(), op.createdAt());
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
            if (!dryRun && ((Number) result.get("fetched")).longValue() > 0) {
                return new StepResult.Next(ARCHIVE_MISSING, null, "page-" + page, result);
            }
            return new StepResult.Completed("page-" + page, result);
        } catch (ConnectorException e) {
            return new StepResult.Failed(e.error());
        }
    }

    /**
     * Archives one chunk of {@value #PAGE_SIZE} per step, so a step makes one GiftHub call and stays well inside
     * the lease; it moves on while more remain. A chunk forgets the mappings GiftHub archived; the rows it did not
     * archive keep their mapping, count in {@code archiveErrors} and are marked seen by this run, so the next
     * chunk does not pick them again (they start over: two more complete runs must miss them).
     */
    private StepResult archiveMissing(StepContext context, Map<String, Object> result) {
        Operation op = context.operation();
        UUID giftHubId = context.target().connection().id();
        Duration readTimeout = Duration.ofMillis(context.integration().policy().readTimeoutMs());
        mappings.markMissed(op.tenantId(), giftHubId, EntityType.PRODUCT, op.createdAt());
        List<String> missing = mappings.findMissedTwice(op.tenantId(), giftHubId, EntityType.PRODUCT, op.createdAt());
        List<String> chunk = missing.subList(0, Math.min(PAGE_SIZE, missing.size()));
        if (!chunk.isEmpty()) {
            try {
                GiftHubPort.BulkResult archived = giftHub.archiveProducts(context.target(), readTimeout, chunk);
                mappings.deleteAll(op.tenantId(), giftHubId, EntityType.PRODUCT, archived.productIds().keySet());
                mappings.markSeen(op.tenantId(), giftHubId, EntityType.PRODUCT,
                        chunk.stream().filter(code -> !archived.productIds().containsKey(code)).toList(), op.createdAt());
                add(result, "archived", archived.productIds().size());
                add(result, "archiveErrors", chunk.size() - archived.productIds().size());
            } catch (ConnectorException e) {
                return new StepResult.Failed(e.error());
            }
        }
        log.log(INFO, "[NEXUS-CATALOG] op={0} tenant={1} {2} productos que el POS ya no lista en este tramo: archivados={3} errores={4}",
                op.id(), op.tenantId(), chunk.size(), result.getOrDefault("archived", 0), result.getOrDefault("archiveErrors", 0));
        result.putIfAbsent("archived", 0L);
        result.putIfAbsent("archiveErrors", 0L);
        if (missing.size() > chunk.size()) {
            return new StepResult.Next(ARCHIVE_MISSING, null, "archived-" + chunk.size(), result);
        }
        return new StepResult.Completed("archived-" + chunk.size(), result);
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
