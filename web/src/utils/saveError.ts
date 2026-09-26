import type { ParseKeys, TFunction } from "i18next";
import { ApiError, isTimeoutError } from "../api/http";

export interface SaveErrorKeys {
  /** 401 — omit to route it through the generic fallbacks (the login page's wrong-credentials). */
  unauthorized?: ParseKeys;
  /** 429 — omit to route it through the generic fallbacks (the login page's account lockout). */
  tooManyRequests?: ParseKeys;
  /** 403. Omit only when the page routes a 403 through the generic fallbacks. */
  forbidden?: ParseKeys;
  /** 404 — omit to route it through the generic fallbacks. */
  notFound?: ParseKeys;
  /** 409 — omit to route it through the generic fallbacks. */
  conflict?: ParseKeys;
  /** 400 — omit to route it through the generic fallbacks. */
  invalid?: ParseKeys;
  /** 503 — omit to route it through the generic fallbacks (mail-less deployments answer 503). */
  unavailable?: ParseKeys;
  /** Any otherwise-unmatched ApiError status; interpolates {{status}}. */
  failedStatus?: ParseKeys;
  /** Generic + network fallback. */
  failed: ParseKeys;
}

/** Maps a mutation failure to the page's fixed vocabulary — never render error.message. */
export function saveErrorMessage(err: unknown, t: TFunction, keys: SaveErrorKeys): string {
  if (err instanceof ApiError) {
    if (err.status === 401 && keys.unauthorized) return t(keys.unauthorized);
    if (err.status === 429 && keys.tooManyRequests) return t(keys.tooManyRequests);
    if (err.status === 403 && keys.forbidden) return t(keys.forbidden);
    if (err.status === 404 && keys.notFound) return t(keys.notFound);
    if (err.status === 409 && keys.conflict) return t(keys.conflict);
    if (err.status === 400 && keys.invalid) return t(keys.invalid);
    if (err.status === 503 && keys.unavailable) return t(keys.unavailable);
    if (keys.failedStatus) return t(keys.failedStatus, { status: err.status });
  }
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t(keys.failed);
}

/** The user-save 409 disambiguator: last-admin demotion vs an email already in use. */
export function isLastAdminConflict(err: unknown): boolean {
  return err instanceof ApiError && err.status === 409 &&
    (err.detail?.toLowerCase().includes("administrator") ?? false);
}

/** The list-load counterpart: status-tagged for ApiErrors, generic otherwise. */
export function loadErrorMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiError) return t("common.error.loadFailedStatus", { status: err.status });
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t("common.error.network");
}
