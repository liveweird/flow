import type { TFunction } from "i18next";
import type {
  DataSourceKind,
  DataSourceRequest,
  DataSourceResponse,
  GitLabConnectionRequest,
  JiraConnectionRequest,
} from "../api/dataSources";
import { ApiError } from "../api/http";
import { nameRule } from "./formRules";
import { todayIsoDate } from "./isoDate";
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

/**
 * A GitLab instance origin as the server accepts it (`gitlab/GitLabBaseUrl.kt`): http(s), a host, an optional port - no path, query,
 * fragment or credentials. The operator's allow-list (the `/kinds` read) narrows it further; the server re-checks both.
 */
const GITLAB_BASE_URL_PATTERN = /^https?:\/\/[a-z0-9.-]+(:\d{1,5})?$/i;
const MAX_GITLAB_BASE_URL_LENGTH = 300;
const MIN_GROUP_PATHS = 1;
export const MAX_GROUP_PATHS = 10;
const MAX_GROUP_PATH_LENGTH = 255;
/** One segment of a GitLab group path (`ingest/GitLabDataSource.kt` `GROUP_SEGMENT_PATTERN`). */
const GROUP_SEGMENT_PATTERN = /^[a-z0-9_][a-z0-9_.-]*$/;
/** The excluded-path list bounds (`infra/validation/PathGlob.kt`): at most 100 patterns of 1..255 characters each. */
export const MAX_PATH_EXCLUSIONS = 100;
const MAX_PATH_EXCLUSION_LENGTH = 255;

const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const MAX_BACKFILL_YEARS_BACK = 10;
const BACKFILL_FLOOR_DATE = "2000-01-01";

// Linear-time shape check (no catastrophic backtracking); the server's rule is looser (just '@'
// and a length cap) — this is UX-level guidance, not the gate (mirrors utils/userForm.ts).
const EMAIL_RE = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/;

/** The later (stricter) of a fixed floor and N years before today — mirrors `backfillFloor()`. */
function legacyBackfillFloorIso(): string {
  const today = new Date();
  const rolling = new Date(Date.UTC(today.getUTCFullYear() - MAX_BACKFILL_YEARS_BACK, today.getUTCMonth(), today.getUTCDate()));
  const rollingIso = rolling.toISOString().slice(0, 10);
  return rollingIso > BACKFILL_FLOOR_DATE ? rollingIso : BACKFILL_FLOOR_DATE;
}

/**
 * The earliest `backfillFrom` a created or moved date may take (`BackfillPolicy.floor`, A48): the later of the legacy floor and the
 * history horizon's first day, which the server computes and the metrics settings carry as `historyHorizonFrom` (null/absent = off).
 */
export function backfillFloorIso(horizonFrom?: string | null): string {
  const legacy = legacyBackfillFloorIso();
  return horizonFrom && horizonFrom > legacy ? horizonFrom : legacy;
}

/** What the backfill rule knows beyond the field: the horizon's first day and, when editing, the date already stored. */
export type BackfillContext = { horizonFrom?: string | null; storedBackfillFrom?: string };

export type DataSourceFormValues = {
  /** Fixed at create (the editor disables the picker on edit); switching it empties the other kind's fields. */
  kind: DataSourceKind;
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
  /** GitLab: the instance origin (`https://gitlab.example.com`), fixed once created. */
  baseUrl: string;
  /** GitLab: blank on edit means "keep the current token" - write-only, like [apiToken]. */
  token: string;
  /** GitLab: full group paths, lowercased (subgroups are included by the server). */
  groupPaths: string[];
  /** GitLab: glob patterns of files left out of the line counts; prefilled from the `/kinds` defaults on create. */
  pathExclusions: string[];
};

export const EMPTY_DATA_SOURCE_FORM: DataSourceFormValues = {
  kind: "JIRA_CLOUD",
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
  baseUrl: "",
  token: "",
  groupPaths: [],
  pathExclusions: [],
};

/** The form values of a stored data source: the kind's own block fills its fields, the other kind's stay empty. */
export function fromDataSourceResponse(dataSource: DataSourceResponse): DataSourceFormValues {
  const { jira, gitlab } = dataSource;
  return {
    ...EMPTY_DATA_SOURCE_FORM,
    kind: dataSource.kind,
    name: dataSource.name,
    enabled: dataSource.enabled,
    syncIntervalMinutes: dataSource.syncIntervalMinutes,
    backfillFrom: dataSource.backfillFrom,
    reconcileHourUtc: dataSource.reconcileHourUtc,
    siteUrl: jira?.siteUrl ?? "",
    email: jira?.email ?? "",
    projectKeys: jira?.projectKeys ?? [],
    authScheme: jira?.authScheme ?? "BASIC",
    baseUrl: gitlab?.baseUrl ?? "",
    groupPaths: gitlab?.groupPaths ?? [],
    pathExclusions: gitlab?.pathExclusions ?? [],
  };
}

/** The host a data source lives on (the list's Site column): the Jira site or the GitLab instance. */
export function dataSourceHost(dataSource: DataSourceResponse): string {
  const url = dataSource.jira?.siteUrl ?? dataSource.gitlab?.baseUrl;
  return url ? (parsedUrl(url)?.host ?? url) : "";
}

/** `new URL` without the throw: a malformed or out-of-range value (`:99999`, `999.1.1.1`) is `null`, never a render crash. */
function parsedUrl(value: string): URL | null {
  try {
    return new URL(value);
  } catch {
    return null;
  }
}

/** What a data source reads: the Jira project keys or the GitLab group paths. */
export function dataSourceScope(dataSource: DataSourceResponse): string[] {
  return dataSource.jira?.projectKeys ?? dataSource.gitlab?.groupPaths ?? [];
}

/** Trims the slashes off both ends (a loop, not a regex: no super-linear backtracking on a long run of `/`). */
function stripSlashes(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value[start] === "/") start += 1;
  while (end > start && value[end - 1] === "/") end -= 1;
  return value.slice(start, end);
}

/** The server's group-path normalisation (`normalizeGroupPaths`): trim, strip leading/trailing `/`, lowercase, de-duplicate. */
export function normalizeGroupPaths(paths: string[]): string[] {
  return [...new Set(paths.map((path) => stripSlashes(path.trim()).toLowerCase()))];
}

/** A base URL as typed: trimmed, with the one trailing `/` the server also drops. */
function trimmedBaseUrl(value: string): string {
  const v = value.trim();
  return v.endsWith("/") ? v.slice(0, -1) : v;
}

type Rule<T> = (value: T, values: DataSourceFormValues) => string | null;

/** A rule that only binds while the form is on [kind]: the other kind's fields are never validated (nor sent). */
function whenKind<T>(kind: DataSourceKind, rule: (value: T) => string | null): Rule<T> {
  return (value, values) => (values.kind === kind ? rule(value) : null);
}

/** The GitLab base-URL rule: the origin shape, then the operator's allow-list when the `/kinds` read named one. */
function gitlabBaseUrlRule(t: TFunction, allowedOrigins: string[]) {
  return (value: string) => {
    const v = trimmedBaseUrl(value);
    if (v.length > MAX_GITLAB_BASE_URL_LENGTH || !GITLAB_BASE_URL_PATTERN.test(v)) return t("dataSources.validation.baseUrlInvalid");
    // `URL.origin` lowercases the host and drops a default port, the canonical form the server compares; a port or octet
    // out of range passes the pattern but not the parser.
    const parsed = parsedUrl(v);
    if (parsed === null) return t("dataSources.validation.baseUrlInvalid");
    const origin = parsed.origin.toLowerCase();
    const allowed = allowedOrigins.map((entry) => entry.toLowerCase());
    return allowed.length === 0 || allowed.includes(origin)
      ? null
      : t("dataSources.validation.baseUrlNotAllowed", { origins: allowedOrigins.join(", ") });
  };
}

/** The GitLab group-path rule: 1..10 normalised paths, each segment well-formed, none equal to or inside another. */
function groupPathsRule(t: TFunction) {
  return (value: string[]) => {
    const paths = normalizeGroupPaths(value);
    if (paths.length < MIN_GROUP_PATHS || paths.length > MAX_GROUP_PATHS) return t("dataSources.validation.groupPathsCount");
    const bad = paths.find(
      (path) => path === "" || path.length > MAX_GROUP_PATH_LENGTH || !path.split("/").every((segment) => GROUP_SEGMENT_PATTERN.test(segment)),
    );
    if (bad !== undefined) return t("dataSources.validation.groupPathsInvalid", { path: bad });
    const nested = paths.find((path) => paths.some((other) => other !== path && path.startsWith(`${other}/`)));
    return nested ? t("dataSources.validation.groupPathsNested", { path: nested }) : null;
  };
}

/** The GitLab excluded-path rule: at most 100 patterns, each 1..255 characters and not blank or made of slashes alone. */
function pathExclusionsRule(t: TFunction) {
  return (value: string[]) => {
    if (value.length > MAX_PATH_EXCLUSIONS) return t("dataSources.validation.pathExclusionsCount");
    const bad = value.find((pattern) => {
      const trimmed = pattern.trim();
      return trimmed === "" || trimmed.length > MAX_PATH_EXCLUSION_LENGTH || stripSlashes(trimmed) === "";
    });
    return bad === undefined ? null : t("dataSources.validation.pathExclusionsInvalid", { pattern: bad });
  };
}

/**
 * Validation rules shared by the create/edit modal AND the Test-connection button (mirrors the server's checks). The Jira rules
 * bind only on a `JIRA_CLOUD` form and the GitLab rules (`baseUrl`, `token`, `groupPaths`) only on a `GITLAB` one - Mantine hands
 * every rule the whole `values`. [allowedOrigins] is the operator's GitLab allow-list from `GET /data-sources/kinds` (empty = no check here).
 */
export function dataSourceFormValidation(
  t: TFunction,
  isEdit: boolean,
  backfill: BackfillContext = {},
  allowedOrigins: string[] = [],
) {
  return {
    name: nameRule(t, "dataSources.validation.nameLength", MAX_DATA_SOURCE_NAME_LENGTH),
    siteUrl: whenKind("JIRA_CLOUD", (value: string) => {
      const v = value.trim();
      if (v.length > MAX_SITE_URL_LENGTH) return t("dataSources.validation.siteUrlInvalid");
      const label = JIRA_SITE_URL_PATTERN.exec(v)?.[1];
      return label && !ATLASSIAN_RESERVED_SITE_LABELS.has(label) ? null : t("dataSources.validation.siteUrlInvalid");
    }),
    email: whenKind("JIRA_CLOUD", (value: string) => {
      const v = value.trim();
      if (v.length > MAX_JIRA_EMAIL_LENGTH) return t("dataSources.validation.emailTooLong");
      return EMAIL_RE.test(v) ? null : t("dataSources.validation.emailInvalid");
    }),
    apiToken: whenKind("JIRA_CLOUD", (value: string) => {
      const v = value.trim();
      if (v === "") return isEdit ? null : t("dataSources.validation.apiTokenRequired");
      return v.length <= MAX_API_TOKEN_LENGTH ? null : t("dataSources.validation.apiTokenTooLong");
    }),
    projectKeys: whenKind("JIRA_CLOUD", (value: string[]) => {
      if (value.length < MIN_PROJECT_KEYS || value.length > MAX_PROJECT_KEYS) {
        return t("dataSources.validation.projectKeysCount");
      }
      const bad = value.find((key) => !PROJECT_KEY_PATTERN.test(key));
      return bad ? t("dataSources.validation.projectKeysInvalid", { key: bad }) : null;
    }),
    baseUrl: whenKind("GITLAB", gitlabBaseUrlRule(t, allowedOrigins)),
    token: whenKind("GITLAB", (value: string) => {
      const v = value.trim();
      if (v === "") return isEdit ? null : t("dataSources.validation.tokenRequired");
      return v.length <= MAX_API_TOKEN_LENGTH ? null : t("dataSources.validation.tokenTooLong");
    }),
    groupPaths: whenKind("GITLAB", groupPathsRule(t)),
    pathExclusions: whenKind("GITLAB", pathExclusionsRule(t)),
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
      if (v > todayIsoDate()) return t("dataSources.validation.backfillFromFuture");
      const legacyFloor = backfillFloorIso();
      if (v < legacyFloor) return t("dataSources.validation.backfillFromTooOld", { floor: legacyFloor });
      // The horizon floor binds only a date that MOVES (the server's rule): an older stored date stays legal on edit.
      const floor = backfillFloorIso(backfill.horizonFrom);
      const unchanged = isEdit && v === backfill.storedBackfillFrom;
      return v < floor && !unchanged ? t("dataSources.validation.backfillFromBeforeHorizon", { floor }) : null;
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

/**
 * The `gitlab` block alone - shared by the save mapper and the ad-hoc Test-connection call. A blank token is omitted (kept on edit).
 * `pathExclusions` is sent only when the user changed it: [baselinePathExclusions] is what an untouched form holds - the `/kinds`
 * defaults on create (so the source keeps following them), the stored list on edit (a PUT without it keeps the stored value). Any
 * other list, an empty one included, is sent as given.
 */
export function toGitLabConnectionRequest(values: DataSourceFormValues, baselinePathExclusions: string[] = []): GitLabConnectionRequest {
  const token = values.token.trim();
  const pathExclusions = values.pathExclusions.map((pattern) => pattern.trim());
  const untouched =
    pathExclusions.length === baselinePathExclusions.length && pathExclusions.every((pattern, index) => pattern === baselinePathExclusions[index]);
  return {
    baseUrl: trimmedBaseUrl(values.baseUrl),
    token: token === "" ? undefined : token,
    groupPaths: normalizeGroupPaths(values.groupPaths),
    ...(untouched ? {} : { pathExclusions }),
  };
}

/** The wire body: a blank `backfillFrom` is omitted so the server computes its own default; exactly one connection block, by kind. */
export function toDataSourceRequest(values: DataSourceFormValues, baselinePathExclusions: string[] = []): DataSourceRequest {
  const backfillFrom = values.backfillFrom.trim();
  return {
    name: values.name.trim(),
    enabled: values.enabled,
    syncIntervalMinutes: values.syncIntervalMinutes,
    backfillFrom: backfillFrom === "" ? undefined : backfillFrom,
    reconcileHourUtc: values.reconcileHourUtc,
    ...(values.kind === "GITLAB"
      ? { gitlab: toGitLabConnectionRequest(values, baselinePathExclusions) }
      : { jira: toJiraConnectionRequest(values) }),
  };
}

/** The RFC 7807 `type` URIs of the data-source 409s that are not the name clash (`ingest/` scope rules). */
export const DATA_SOURCE_PROBLEM_TYPES = {
  otherSite: "urn:flow:problem:data-source-other-site",
  projectOverlap: "urn:flow:problem:data-source-project-overlap",
  otherInstance: "urn:flow:problem:data-source-other-instance",
  groupOverlap: "urn:flow:problem:data-source-group-overlap",
} as const;

/**
 * The form field a data-source 409 is about, from its problem `type`: another Jira site (create only) marks `siteUrl`,
 * a project key already in another data source marks `projectKeys`, another GitLab instance (create only) marks `baseUrl`,
 * a group path another GitLab source already reads marks `groupPaths`, and anything else (`about:blank`, the
 * case-insensitive name clash) marks `name`. The fixed vocabulary only — the server's `detail` is never rendered.
 */
export function dataSourceConflictField(err: ApiError): "name" | "siteUrl" | "projectKeys" | "baseUrl" | "groupPaths" {
  if (err.type === DATA_SOURCE_PROBLEM_TYPES.otherSite) return "siteUrl";
  if (err.type === DATA_SOURCE_PROBLEM_TYPES.projectOverlap) return "projectKeys";
  if (err.type === DATA_SOURCE_PROBLEM_TYPES.otherInstance) return "baseUrl";
  if (err.type === DATA_SOURCE_PROBLEM_TYPES.groupOverlap) return "groupPaths";
  return "name";
}

/** The data-source save vocabulary; a 409 is handled by the editor per field ([dataSourceConflictField]). */
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
