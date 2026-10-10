import { Connector } from '../../api/connections.api';
import { Flow, Integration } from '../../api/integrations.api';
import { Operation } from '../../api/operations.api';
import { IconName } from '../../shared/ui/icon';

export type Health = 'HEALTHY' | 'PROGRESSING' | 'DEGRADED' | 'IDLE' | 'UNKNOWN';

export const HEALTHS: Health[] = ['HEALTHY', 'PROGRESSING', 'DEGRADED', 'IDLE', 'UNKNOWN'];

/** How far back the console looks at operations to judge health. */
export const HEALTH_WINDOW_MS = 24 * 60 * 60 * 1000;
/** Largest page the admin API returns (OperationQueries.MAX_PAGE_SIZE). */
export const HEALTH_SAMPLE_SIZE = 200;

export const FLOW_META: Record<Flow, { label: string; icon: IconName }> = {
  FISCAL: { label: 'Fiscal', icon: 'receipt' },
  ORDER: { label: 'Pedidos', icon: 'shopping-bag' },
  CATALOG: { label: 'Catálogo', icon: 'package' },
};

export const CONNECTOR_ICONS: Record<Connector, IconName> = { POS: 'store', SIFEN: 'file-text', GIFTHUB: 'gift' };

/**
 * Health in the Argo CD sense, derived from the integration's recent operations:
 * anything in DLQ or failed → degraded; work still moving → progressing; only successes → healthy.
 * A stopped integration is idle whatever its history. No operations in the window → unknown.
 */
export function integrationHealth(integration: Integration, operations: readonly Operation[]): Health {
  if (integration.state === 'PAUSED' || integration.state === 'DISABLED' || integration.state === 'EMERGENCY_STOP') {
    return 'IDLE';
  }
  const own = operations.filter((op) => op.integrationId === integration.id);
  if (own.some((op) => op.status === 'DEAD' || op.status === 'FAILED')) {
    return 'DEGRADED';
  }
  if (integration.state === 'DRAINING' || own.some((op) => op.status !== 'SUCCEEDED')) {
    return 'PROGRESSING';
  }
  return own.length ? 'HEALTHY' : 'UNKNOWN';
}
