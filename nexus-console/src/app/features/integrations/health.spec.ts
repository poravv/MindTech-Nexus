import { describe, expect, it } from 'vitest';
import { Integration } from '../../api/integrations.api';
import { Operation, OperationStatus } from '../../api/operations.api';
import { integrationHealth } from './health';

const integration = (state: Integration['state'] = 'ENABLED'): Integration => ({
  id: 'i1',
  tenantId: 't',
  flow: 'FISCAL',
  code: 'fiscal',
  sourceConnectionId: 'a',
  targetConnectionId: 'b',
  state,
  policy: {} as Integration['policy'],
  version: 1,
  updatedAt: '',
});

const op = (status: OperationStatus, integrationId = 'i1') => ({ integrationId, status }) as Operation;

describe('integrationHealth', () => {
  it('is degraded when any recent operation is in DLQ or failed', () => {
    expect(integrationHealth(integration(), [op('SUCCEEDED'), op('DEAD')])).toBe('DEGRADED');
    expect(integrationHealth(integration(), [op('FAILED')])).toBe('DEGRADED');
  });

  it('is progressing while operations are still moving or the integration drains', () => {
    expect(integrationHealth(integration(), [op('SUCCEEDED'), op('RETRY')])).toBe('PROGRESSING');
    expect(integrationHealth(integration('DRAINING'), [])).toBe('PROGRESSING');
  });

  it('is healthy only with successes of its own', () => {
    expect(integrationHealth(integration(), [op('SUCCEEDED'), op('DEAD', 'other')])).toBe('HEALTHY');
  });

  it('is unknown without recent operations and idle when stopped', () => {
    expect(integrationHealth(integration(), [])).toBe('UNKNOWN');
    expect(integrationHealth(integration('PAUSED'), [op('DEAD')])).toBe('IDLE');
  });
});
