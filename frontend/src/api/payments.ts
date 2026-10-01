import { api, ApiException } from './client';

// R17-06: GET /payments/sandbox-status exists only where the backend runs the sandbox payment
// controller (a dev/test/sandbox/docker profile *and* PAYMENT_SANDBOX_ENABLED=true). Everywhere else it
// is a 404, which the browser logs as a console resource error every time it is requested - and the
// Store tab and Studio store used to request it on every (re)load, up to three times per page.
//
// The answer is a property of the deployment, not of the user or the class, so it is asked once per
// page load and shared. Only a definitive answer is remembered (200, or 404 = "no sandbox here"); a
// transient failure (network, 429, 5xx) is not, so the next caller gets to retry.
let cached: Promise<boolean> | null = null;

export function fetchCheckoutAvailability(): Promise<boolean> {
  if (!cached) {
    const attempt: Promise<boolean> = api
      .get<{ checkoutAvailable: boolean }>('/payments/sandbox-status')
      .then((status) => status?.checkoutAvailable === true)
      .catch((err: unknown) => {
        if (!(err instanceof ApiException && err.status === 404) && cached === attempt) cached = null;
        return false;
      });
    cached = attempt;
  }
  return cached;
}

/** Test helper: forget the remembered answer. */
export function resetCheckoutAvailabilityCache(): void {
  cached = null;
}
