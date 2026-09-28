import type { TFunction } from "i18next";
import type {
  DataSourceRequest,
  DataSourceResponse,
  JiraConnectionRequest,
} from "../api/dataSources";
import { nameRule } from "./formRules";
import { saveErrorMessage } from "./saveError";

// Server limits (ingest/DataSource.kt) mirrored client-side.
export const MAX_DATA_SOURCE_NAME_LENGTH = 100;
const MAX_SITE_URL_LENGTH = 253;
const MAX_JIRA_EMAIL_LENGTH = 254;
export const MAX_API_TOKEN_LENGTH = 1000;
const MIN_PROJECT_KEYS = 1;
export const MAX_PROJECT_KEYS = 50;
export const MIN_SYNC_INTERVAL_MINUTES = 5;
export const MAX_SYNC_INTERVAL_MINUTES = 1440;
export const MIN_RECONCILE_HOUR_UTC = 0;
export const MAX_RECONCILE_HOUR_UTC = 23;
const DEFAULT_RECONCILE_HOUR_UTC = 3;
const DEFAULT_SYNC_INTERVAL_MINUTES = 60;

/** The Jira Cloud project-key shape: one uppercase letter, then 1-9 uppercase letters/digits/underscores. */
const PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9_]{1,9}$/;

/**
 * The allow-list boundary's shape (`ingest/DataSource.kt` `JIRA_SITE_URL_PATTERN`): a Jira Cloud
 * site's ORIGIN only — no path, no query, no port, no trailing slash. The single capturing group
 * is the tenant label, checked against [ATLASSIAN_RESERVED_SITE_LABELS] below.
 */
const JIRA_SITE_URL_PATTERN = /^https:\/\/([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.atlassian\.net$/;

/** Labels under `*.atlassian.net` that are Atlassian's own surfaces, never a customer tenant. */
const ATLASSIAN_RESERVED_SITE_LABELS = new Set([
  "api", "id", "admin", "www", "auth", "start", "home", "status", "developer",
  "support", "community", "marketplace", "my", "team",
]);

const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const MAX_BACKFILL_YEARS_BACK = 10;
const BACKFILL_FLOOR_DATE = "2000-01-01";

// Linear-time shape check (no catastrophic backtracking); the server's rule is looser (just '@'
// and a length cap) — this is UX-level guidance, not the gate (mirrors utils/userForm.ts).
const EMAIL_RE = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/;

/** The later (stricter) of a fixed floor and N years before today — mirrors `backfillFloor()`. */
function backfillFloorIso(): string {
  const today = new Date();
  const rolling = new Date(Date.UTC(today.getUTCFullYear() - MAX_BACKFILL_YEARS_BACK, today.getUTCMonth(), today.getUTCDate()));
  const rollingIso = rolling.toISOString().slice(0, 10);
  return rollingIso > BACKFILL_FLOOR_DATE ? rollingIso : BACKFILL_FLOOR_DATE;
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

export type DataSourceFormValues = {
  name: string;
  enabled: boolean;
  syncIntervalMinutes: number;
  /** Blank means "omitted" — the server computes today minus 24 months. */
  backfillFrom: string;
  reconcileHourUtc: number;
  siteUrl: string;
  email: string;
  /** Blank on edit means "keep the current token" — never prefilled from a response (write-only). */
  apiToken: string;
  projectKeys: string[];
  authScheme: "BASIC" | "BEARER";
};

export const EMPTY_DATA_SOURCE_FORM: DataSourceFormValues = {
  name: "",
  enabled: true,
  syncIntervalMinutes: DEFAULT_SYNC_INTERVAL_MINUTES,
  backfillFrom: "",
  reconcileHourUtc: DEFAULT_RECONCILE_HOUR_UTC,
  siteUrl: "",
  email: "",
  apiToken: "",
  projectKeys: [],
  authScheme: "BASIC",
};

export function fromDataSourceResponse(dataSource: DataSourceResponse): DataSourceFormValues {
  return {
    name: dataSource.name,
    enabled: dataSource.enabled,
    syncIntervalMinutes: dataSource.syncIntervalMinutes,
    backfillFrom: dataSource.backfillFrom,
    reconcileHourUtc: dataSource.reconcileHourUtc,
    siteUrl: dataSource.jira.siteUrl,
    email: dataSource.jira.email,
    apiToken: "",
    projectKeys: dataSource.jira.projectKeys,
    authScheme: dataSource.jira.authScheme,
  };
}

/** Validation rules shared by the create/edit modal AND the Test-connection button (mirrors the server's checks). */
export function dataSourceFormValidation(t: TFunction, isEdit: boolean) {
  return {
    name: nameRule(t, "dataSources.validation.nameLength", MAX_DATA_SOURCE_NAME_LENGTH),
    siteUrl: (value: string) => {
      const v = value.trim();
      if (v.length > MAX_SITE_URL_LENGTH) return t("dataSources.validation.siteUrlInvalid");
      const label = JIRA_SITE_URL_PATTERN.exec(v)?.[1];
      return label && !ATLASSIAN_RESERVED_SITE_LABELS.has(label) ? null : t("dataSources.validation.siteUrlInvalid");
    },
    email: (value: string) => {
      const v = value.trim();
      if (v.length > MAX_JIRA_EMAIL_LENGTH) return t("dataSources.validation.emailTooLong");
      return EMAIL_RE.test(v) ? null : t("dataSources.validation.emailInvalid");
    },
    apiToken: (value: string) => {
      const v = value.trim();
      if (v === "") return isEdit ? null : t("dataSources.validation.apiTokenRequired");
      return v.length <= MAX_API_TOKEN_LENGTH ? null : t("dataSources.validation.apiTokenTooLong");
    },
    projectKeys: (value: string[]) => {
      if (value.length < MIN_PROJECT_KEYS || value.length > MAX_PROJECT_KEYS) {
        return t("dataSources.validation.projectKeysCount");
      }
      const bad = value.find((key) => !PROJECT_KEY_PATTERN.test(key));
      return bad ? t("dataSources.validation.projectKeysInvalid", { key: bad }) : null;
    },
    syncIntervalMinutes: (value: number) =>
      value >= MIN_SYNC_INTERVAL_MINUTES && value <= MAX_SYNC_INTERVAL_MINUTES
        ? null
        : t("dataSources.validation.syncIntervalRange"),
    reconcileHourUtc: (value: number) =>
      value >= MIN_RECONCILE_HOUR_UTC && value <= MAX_RECONCILE_HOUR_UTC
        ? null
        : t("dataSources.validation.reconcileHourRange"),
    backfillFrom: (value: string) => {
      const v = value.trim();
      if (v === "") return null; // omitted — the server computes the default
      if (!ISO_DATE_PATTERN.test(v)) return t("dataSources.validation.backfillFromInvalid");
      if (v > todayIso()) return t("dataSources.validation.backfillFromFuture");
      const floor = backfillFloorIso();
      return v < floor ? t("dataSources.validation.backfillFromTooOld", { floor }) : null;
    },
  };
}

/** The `jira` block alone — shared by the save mapper and the ad-hoc Test-connection call. */
export function toJiraConnectionRequest(values: DataSourceFormValues): JiraConnectionRequest {
  const apiToken = values.apiToken.trim();
  return {
    siteUrl: values.siteUrl.trim(),
    email: values.email.trim(),
    apiToken: apiToken === "" ? undefined : apiToken,
    projectKeys: values.projectKeys.map((key) => key.trim().toUpperCase()),
    authScheme: values.authScheme,
  };
}

/** The wire body: a blank `backfillFrom` is omitted so the server computes its own default. */
export function toDataSourceRequest(values: DataSourceFormValues): DataSourceRequest {
  const backfillFrom = values.backfillFrom.trim();
  return {
    name: values.name.trim(),
    enabled: values.enabled,
    syncIntervalMinutes: values.syncIntervalMinutes,
    backfillFrom: backfillFrom === "" ? undefined : backfillFrom,
    reconcileHourUtc: values.reconcileHourUtc,
    jira: toJiraConnectionRequest(values),
  };
}

/** The data-source save vocabulary — a 409 is always the name clash (siteUrl is fixed/disabled on edit). */
export function dataSourceSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "dataSources.saveForbidden",
    notFound: "dataSources.saveGone",
    invalid: "dataSources.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}

/** The Test-connection failure vocabulary (a 429 bucket, a malformed ad-hoc body, …). */
export function testConnectionErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "dataSources.saveForbidden",
    tooManyRequests: "dataSources.test.rateLimited",
    invalid: "dataSources.saveInvalid",
    failedStatus: "common.error.actionFailedStatus",
    failed: "dataSources.test.failedGeneric",
  });
}
