import { describe, expect, it } from 'vitest';
import { defaultPolicy, POLICY_FIELDS } from './integration-form-dialog';

describe('integration policy defaults', () => {
  it('should default FISCAL to the API read timeout of 200 s and the other flows to 150 s', () => {
    expect(defaultPolicy('FISCAL').readTimeoutMs).toBe(200000);
    expect(defaultPolicy('ORDER').readTimeoutMs).toBe(150000);
    expect(defaultPolicy('CATALOG').readTimeoutMs).toBe(150000);
  });

  it('should cap the read timeout at the API limit of 280 s', () => {
    expect(POLICY_FIELDS.find((f) => f.key === 'readTimeoutMs')?.max).toBe(280000);
  });
});
