import { Connector } from '../../api/connections.api';
import { Flow, IntegrationHealthStatus } from '../../api/integrations.api';
import { IconName } from '../../shared/ui/icon';

/** Facet and legend order: what needs attention first. */
export const HEALTHS: IntegrationHealthStatus[] = ['DEGRADED', 'PROGRESSING', 'HEALTHY', 'SUSPENDED', 'NO_ACTIVITY'];

/** Chart fills per health, validated with the status ramps (identity also carried by icon + label). */
export const HEALTH_FILL: Record<IntegrationHealthStatus, string> = {
  DEGRADED: 'var(--chart-danger)',
  PROGRESSING: 'var(--chart-info)',
  HEALTHY: 'var(--chart-ok)',
  SUSPENDED: 'var(--chart-neutral)',
  NO_ACTIVITY: 'var(--chart-muted)',
};

export const FLOW_META: Record<Flow, { label: string; icon: IconName; route: string }> = {
  FISCAL: { label: 'Fiscal', icon: 'receipt', route: 'POS → SIFEN' },
  ORDER: { label: 'Pedidos', icon: 'shopping-bag', route: 'GiftHub → POS' },
  CATALOG: { label: 'Catálogo', icon: 'package', route: 'POS → GiftHub' },
};

export const CONNECTOR_META: Record<Connector, { label: string; icon: IconName }> = {
  POS: { label: 'POS', icon: 'store' },
  SIFEN: { label: 'SIFEN', icon: 'file-text' },
  GIFTHUB: { label: 'GiftHub', icon: 'gift' },
};
