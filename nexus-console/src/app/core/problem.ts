import { HttpErrorResponse } from '@angular/common/http';

interface ProblemDetails {
  detail?: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

/** Human message for an API error (RFC 9457 problem+json), with the correlation id for support. */
export function problemMessage(error: unknown, fallback = 'No se pudo completar la operación.'): string {
  if (!(error instanceof HttpErrorResponse)) {
    return fallback;
  }
  if (error.status === 0) {
    return 'Sin conexión con el servidor. Reintentá en unos segundos.';
  }
  const problem: ProblemDetails = typeof error.error === 'object' && error.error ? error.error : {};
  const fields = problem.errors?.map((e) => `${e.field}: ${e.message}`).join(' · ');
  const message = [problem.detail ?? fallback, fields].filter(Boolean).join(' — ');
  return problem.correlationId ? `${message} (ref. ${problem.correlationId})` : message;
}
