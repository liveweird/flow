import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import type { DataSourceResponse } from "../api/dataSources";
import {
  backfillFloorIso,
  dataSourceHost,
  dataSourceScope,
  normalizeGroupPaths,
  toGitLabConnectionRequest,
  DATA_SOURCE_PROBLEM_TYPES,
  dataSourceConflictField,
  dataSourceFormValidation,
  dataSourceSaveErrorMessage,
  EMPTY_DATA_SOURCE_FORM,
  fromDataSourceResponse,
  MAX_API_TOKEN_LENGTH,
  MAX_DATA_SOURCE_NAME_LENGTH,
  MAX_PROJECT_KEYS,
  testConnectionErrorMessage,
  toDataSourceRequest,
  toJiraConnectionRequest,
  type DataSourceFormValues,
} from "./dataSourceForm";

const t = i18n.t;

const VALID: DataSourceFormValues = {
  ...EMPTY_DATA_SOURCE_FORM,
  name: "Acme Jira",
  siteUrl: "https://acme.atlassian.net",
  email: "svc@acme.com",
  apiToken: "a-token",
  projectKeys: ["ENG"],
};

const GITLAB: DataSourceFormValues = {
  ...EMPTY_DATA_SOURCE_FORM,
  kind: "GITLAB",
  name: "Acme GitLab",
  baseUrl: "https://gitlab.acme.com",
  token: "glpat-token",
  groupPaths: ["platform", "apps/web"],
};

type Rules = ReturnType<typeof dataSourceFormValidation>;

/** The kind-gated rules take the whole form (Mantine passes it); the Jira/GitLab fields are bound to one fixed `values` here. */
function bound(rules: Rules, values: DataSourceFormValues) {
  return {
    ...rules,
    siteUrl: (v: string) => rules.siteUrl(v, values),
    email: (v: string) => rules.email(v, values),
    apiToken: (v: string) => rules.apiToken(v, values),
    projectKeys: (v: string[]) => rules.projectKeys(v, values),
    baseUrl: (v: string) => rules.baseUrl(v, values),
    token: (v: string) => rules.token(v, values),
    groupPaths: (v: string[]) => rules.groupPaths(v, values),
  };
}

describe("dataSourceFormValidation", () => {
  const rulesCreate = bound(dataSourceFormValidation(t, false), VALID);
  const rulesEdit = bound(dataSourceFormValidation(t, true), VALID);

  test("name: rejects blank/too-long, accepts a normal value", () => {
    expect(rulesCreate.name("")).toBe("Name must be 1–100 characters");
    expect(rulesCreate.name("a".repeat(MAX_DATA_SOURCE_NAME_LENGTH + 1))).toBe("Name must be 1–100 characters");
    expect(rulesCreate.name("Acme Jira")).toBeNull();
  });

  test("siteUrl: rejects an over-length value before the pattern even runs", () => {
    const tooLong = `https://${"a".repeat(250)}.atlassian.net`;
    expect(rulesCreate.siteUrl(tooLong)).toBe("Site URL must be exactly https://<tenant>.atlassian.net");
  });

  test("siteUrl: rejects a non-matching shape (path, port, http, wrong domain)", () => {
    const msg = "Site URL must be exactly https://<tenant>.atlassian.net";
    expect(rulesCreate.siteUrl("http://acme.atlassian.net")).toBe(msg);
    expect(rulesCreate.siteUrl("https://acme.atlassian.net/path")).toBe(msg);
    expect(rulesCreate.siteUrl("https://acme.atlassian.net:8080")).toBe(msg);
    expect(rulesCreate.siteUrl("https://acme.example.com")).toBe(msg);
    expect(rulesCreate.siteUrl("not a url")).toBe(msg);
  });

  test("siteUrl: rejects every Atlassian-reserved tenant label", () => {
    const msg = "Site URL must be exactly https://<tenant>.atlassian.net";
    expect(rulesCreate.siteUrl("https://api.atlassian.net")).toBe(msg);
    expect(rulesCreate.siteUrl("https://admin.atlassian.net")).toBe(msg);
    expect(rulesCreate.siteUrl("https://team.atlassian.net")).toBe(msg);
  });

  test("siteUrl: accepts a genuine tenant, trimmed", () => {
    expect(rulesCreate.siteUrl("  https://acme.atlassian.net  ")).toBeNull();
  });

  test("email: rejects too-long and malformed, accepts valid", () => {
    expect(rulesCreate.email(`${"a".repeat(250)}@example.com`)).toBe("Email must be at most 254 characters");
    expect(rulesCreate.email("not-an-email")).toBe("Enter a valid email address");
    expect(rulesCreate.email("svc@acme.com")).toBeNull();
  });

  test("apiToken: blank is required on create, allowed (keep current) on edit", () => {
    expect(rulesCreate.apiToken("")).toBe("An API token is required");
    expect(rulesCreate.apiToken("   ")).toBe("An API token is required");
    expect(rulesEdit.apiToken("")).toBeNull();
    expect(rulesEdit.apiToken("   ")).toBeNull();
  });

  test("apiToken: rejects an over-length value, accepts a normal one", () => {
    expect(rulesCreate.apiToken("a".repeat(MAX_API_TOKEN_LENGTH + 1))).toBe("API token must be at most 1000 characters");
    expect(rulesCreate.apiToken("secret-token")).toBeNull();
  });

  test("projectKeys: rejects too few, too many, and an invalid shape", () => {
    expect(rulesCreate.projectKeys([])).toBe("Enter 1–50 project keys");
    expect(rulesCreate.projectKeys(Array.from({ length: MAX_PROJECT_KEYS + 1 }, (_, i) => `K${i}`))).toBe(
      "Enter 1–50 project keys",
    );
    expect(rulesCreate.projectKeys(["1BAD"])).toBe(
      "Invalid project key: 1BAD (uppercase letters, digits and underscores only)",
    );
    expect(rulesCreate.projectKeys(["ENG", "OPS_1"])).toBeNull();
  });

  test("syncIntervalMinutes: rejects below/above range, accepts the bounds", () => {
    expect(rulesCreate.syncIntervalMinutes(4)).toBe("Sync interval must be between 5 and 1440 minutes");
    expect(rulesCreate.syncIntervalMinutes(1441)).toBe("Sync interval must be between 5 and 1440 minutes");
    expect(rulesCreate.syncIntervalMinutes(5)).toBeNull();
    expect(rulesCreate.syncIntervalMinutes(1440)).toBeNull();
  });

  test("reconcileHourUtc: rejects below/above range, accepts the bounds", () => {
    expect(rulesCreate.reconcileHourUtc(-1)).toBe("Reconcile hour must be between 0 and 23");
    expect(rulesCreate.reconcileHourUtc(24)).toBe("Reconcile hour must be between 0 and 23");
    expect(rulesCreate.reconcileHourUtc(0)).toBeNull();
    expect(rulesCreate.reconcileHourUtc(23)).toBeNull();
  });

  test("backfillFrom: blank is omitted (server default), no error", () => {
    expect(rulesCreate.backfillFrom("")).toBeNull();
    expect(rulesCreate.backfillFrom("   ")).toBeNull();
  });

  test("backfillFrom: rejects a non-ISO shape", () => {
    expect(rulesCreate.backfillFrom("2023/01/01")).toBe("Backfill date must be an ISO date (YYYY-MM-DD)");
    expect(rulesCreate.backfillFrom("Jan 1 2023")).toBe("Backfill date must be an ISO date (YYYY-MM-DD)");
  });

  test("backfillFrom: rejects a future date", () => {
    const future = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    expect(rulesCreate.backfillFrom(future)).toBe("Backfill date must not be in the future");
  });

  test("backfillFrom: rejects a date before the rolling 10-year floor", () => {
    const today = new Date();
    const floor = new Date(Date.UTC(today.getUTCFullYear() - 10, today.getUTCMonth(), today.getUTCDate()))
      .toISOString()
      .slice(0, 10);
    const beforeFloor = new Date(Date.UTC(today.getUTCFullYear() - 11, today.getUTCMonth(), today.getUTCDate()))
      .toISOString()
      .slice(0, 10);
    expect(rulesCreate.backfillFrom(beforeFloor)).toBe(`Backfill date must not be before ${floor}`);
  });

  test("backfillFrom: accepts a valid in-range date", () => {
    expect(rulesCreate.backfillFrom("2023-01-01")).toBeNull();
  });

  describe("the history horizon floor", () => {
    const horizon = { horizonFrom: "2025-06-15" };

    test("create: a date before the horizon is refused, naming the floor; the floor day itself passes", () => {
      const rules = dataSourceFormValidation(t, false, horizon);
      expect(rules.backfillFrom("2025-06-14")).toBe("Backfill date must not be before 2025-06-15 (the history horizon)");
      expect(rules.backfillFrom("2025-06-15")).toBeNull();
      expect(rules.backfillFrom("2025-07-01")).toBeNull();
    });

    test("edit: the unchanged stored date stays legal, a different date before the horizon does not", () => {
      const rules = dataSourceFormValidation(t, true, { ...horizon, storedBackfillFrom: "2024-01-01" });
      expect(rules.backfillFrom("2024-01-01")).toBeNull();
      expect(rules.backfillFrom("2023-12-31")).toBe("Backfill date must not be before 2025-06-15 (the history horizon)");
      expect(rules.backfillFrom("2025-06-15")).toBeNull();
    });

    test("edit: the legacy 10-year floor still refuses an unchanged stored date (the server re-checks it)", () => {
      const rules = dataSourceFormValidation(t, true, { storedBackfillFrom: "1999-01-01" });
      expect(rules.backfillFrom("1999-01-01")).toContain("Backfill date must not be before");
    });

    test("a horizon off (null) or earlier than the legacy floor changes nothing", () => {
      expect(dataSourceFormValidation(t, false, { horizonFrom: null }).backfillFrom("2023-01-01")).toBeNull();
      expect(backfillFloorIso("1990-01-01")).toBe(backfillFloorIso());
      expect(backfillFloorIso("2999-01-01")).toBe("2999-01-01");
    });
  });
});

describe("toJiraConnectionRequest", () => {
  test("trims fields, uppercases project keys, and omits a blank token", () => {
    const values: DataSourceFormValues = { ...VALID, siteUrl: " https://acme.atlassian.net ", email: " svc@acme.com ", apiToken: "  ", projectKeys: [" eng ", "ops"] };
    expect(toJiraConnectionRequest(values)).toEqual({
      siteUrl: "https://acme.atlassian.net",
      email: "svc@acme.com",
      apiToken: undefined,
      projectKeys: ["ENG", "OPS"],
      authScheme: "BASIC",
    });
  });

  test("carries a non-blank token through, trimmed", () => {
    expect(toJiraConnectionRequest({ ...VALID, apiToken: "  secret  " }).apiToken).toBe("secret");
  });
});

describe("toDataSourceRequest", () => {
  test("omits a blank backfillFrom", () => {
    const req = toDataSourceRequest({ ...VALID, backfillFrom: "   " });
    expect(req.backfillFrom).toBeUndefined();
  });

  test("carries a non-blank backfillFrom through, trimmed", () => {
    const req = toDataSourceRequest({ ...VALID, backfillFrom: " 2023-01-01 " });
    expect(req.backfillFrom).toBe("2023-01-01");
  });

  test("maps the common fields and nests the jira block", () => {
    const req = toDataSourceRequest(VALID);
    expect(req).toMatchObject({
      name: "Acme Jira",
      enabled: true,
      syncIntervalMinutes: EMPTY_DATA_SOURCE_FORM.syncIntervalMinutes,
      reconcileHourUtc: EMPTY_DATA_SOURCE_FORM.reconcileHourUtc,
      jira: { siteUrl: "https://acme.atlassian.net", email: "svc@acme.com" },
    });
  });
});

describe("fromDataSourceResponse", () => {
  test("maps a response into form values with a blank (write-only) token", () => {
    const response: DataSourceResponse = {
      id: 1,
      kind: "JIRA_CLOUD",
      name: "Acme Jira",
      enabled: true,
      syncIntervalMinutes: 60,
      backfillFrom: "2023-01-01",
      reconcileHourUtc: 3,
      configRevision: 1,
      jira: {
        siteUrl: "https://acme.atlassian.net",
        email: "svc@acme.com",
        hasApiToken: true,
        projectKeys: ["ENG", "OPS"],
        authScheme: "BEARER",
        cloudId: null,
      },
      status: {
        state: "CURRENT",
        lastSyncStartedAt: null,
        lastSyncSucceededAt: null,
        lastSyncErrorCode: null,
        consecutiveFailures: 0,
        limitTrips: 0,
        reconcileLimitTrips: 0,
        runningJobId: null,
      },
      createdAt: 1,
      updatedAt: 2,
    };
    expect(fromDataSourceResponse(response)).toEqual({
      kind: "JIRA_CLOUD",
      baseUrl: "",
      token: "",
      groupPaths: [],
      name: "Acme Jira",
      enabled: true,
      syncIntervalMinutes: 60,
      backfillFrom: "2023-01-01",
      reconcileHourUtc: 3,
      siteUrl: "https://acme.atlassian.net",
      email: "svc@acme.com",
      apiToken: "",
      projectKeys: ["ENG", "OPS"],
      authScheme: "BEARER",
    });
  });
});

describe("the GitLab kind", () => {
  const ORIGINS = ["https://gitlab.acme.com", "https://gitlab.other.org:8443"];
  const create = bound(dataSourceFormValidation(t, false, {}, ORIGINS), GITLAB);
  const edit = bound(dataSourceFormValidation(t, true, {}, ORIGINS), GITLAB);

  test("the Jira rules bind only a Jira form and the GitLab rules only a GitLab form", () => {
    const rules = dataSourceFormValidation(t, false, {}, ORIGINS);
    expect(rules.siteUrl("not a url", GITLAB)).toBeNull();
    expect(rules.email("nope", GITLAB)).toBeNull();
    expect(rules.apiToken("", GITLAB)).toBeNull();
    expect(rules.projectKeys([], GITLAB)).toBeNull();
    expect(rules.siteUrl("not a url", VALID)).not.toBeNull();
    expect(rules.baseUrl("ftp://nope", VALID)).toBeNull();
    expect(rules.token("", VALID)).toBeNull();
    expect(rules.groupPaths([], VALID)).toBeNull();
  });

  test("baseUrl: an origin only - http(s), a host, an optional port, one trailing slash tolerated", () => {
    const msg = "Base URL must be an origin such as https://gitlab.example.com (no path)";
    expect(create.baseUrl("https://gitlab.acme.com")).toBeNull();
    expect(create.baseUrl("  https://gitlab.acme.com/ ")).toBeNull();
    expect(create.baseUrl("HTTPS://GITLAB.ACME.COM")).toBeNull();
    expect(create.baseUrl("https://gitlab.acme.com/group")).toBe(msg);
    expect(create.baseUrl("https://gitlab.acme.com//")).toBe(msg);
    expect(create.baseUrl("ftp://gitlab.acme.com")).toBe(msg);
    expect(create.baseUrl("gitlab.acme.com")).toBe(msg);
    expect(create.baseUrl("https://user@gitlab.acme.com")).toBe(msg);
    expect(create.baseUrl("")).toBe(msg);
    expect(create.baseUrl(`https://${"a".repeat(300)}.com`)).toBe(msg);
    // The pattern passes these; the URL parser does not, and the rule answers instead of throwing.
    expect(create.baseUrl("https://gitlab.acme.com:99999")).toBe(msg);
    expect(create.baseUrl("https://999.1.1.1")).toBe(msg);
  });

  test("baseUrl: must be on the allow-list when the server named one, with the origins in the message", () => {
    expect(create.baseUrl("https://gitlab.other.org:8443")).toBeNull();
    expect(create.baseUrl("https://gitlab.acme.com:443")).toBeNull();
    expect(create.baseUrl("https://evil.example.com")).toBe(
      "This instance is not on the server's allow-list. Allowed: https://gitlab.acme.com, https://gitlab.other.org:8443",
    );
    expect(bound(dataSourceFormValidation(t, false), GITLAB).baseUrl("https://anything.example.com")).toBeNull();
  });

  test("token: required on create, blank keeps the current one on edit, capped at 1000", () => {
    expect(create.token("")).toBe("An access token is required");
    expect(create.token("   ")).toBe("An access token is required");
    expect(edit.token("")).toBeNull();
    expect(create.token("a".repeat(MAX_API_TOKEN_LENGTH + 1))).toBe("Access token must be at most 1000 characters");
    expect(create.token("glpat-secret")).toBeNull();
  });

  test("groupPaths: 1-10 normalised paths", () => {
    expect(create.groupPaths([])).toBe("Enter 1–10 group paths");
    expect(create.groupPaths(Array.from({ length: 11 }, (_, i) => `g${i}`))).toBe("Enter 1–10 group paths");
    expect(create.groupPaths(Array.from({ length: 10 }, (_, i) => `g${i}`))).toBeNull();
    // Normalisation first: duplicates collapse before the count, slashes and case are folded.
    expect(create.groupPaths(["Platform", "/platform/", " PLATFORM "])).toBeNull();
    expect(create.groupPaths(["/", "  "])).toBe("Invalid group path:  (lowercase letters, digits, _ . - and / between subgroups)");
  });

  test("groupPaths: each segment must match the server pattern", () => {
    const bad = (path: string) => `Invalid group path: ${path} (lowercase letters, digits, _ . - and / between subgroups)`;
    expect(create.groupPaths(["platform/tooling", "a_b.c-d"])).toBeNull();
    expect(create.groupPaths(["-bad"])).toBe(bad("-bad"));
    expect(create.groupPaths([".hidden"])).toBe(bad(".hidden"));
    expect(create.groupPaths(["a b"])).toBe(bad("a b"));
    expect(create.groupPaths(["a//b"])).toBe(bad("a//b"));
    expect(create.groupPaths(["a/é"])).toBe(bad("a/é"));
    expect(create.groupPaths(["a".repeat(256)])).toBe(bad("a".repeat(256)));
  });

  test("groupPaths: a path nested inside another is refused, a name sharing a prefix is not", () => {
    expect(create.groupPaths(["platform", "platform/tooling"])).toBe(
      "Group path platform/tooling is inside another listed group — subgroups are included automatically",
    );
    expect(create.groupPaths(["platform/tooling", "platform"])).toBe(
      "Group path platform/tooling is inside another listed group — subgroups are included automatically",
    );
    expect(create.groupPaths(["platform", "platformx", "platform-two"])).toBeNull();
  });

  test("normalizeGroupPaths trims, strips slashes, lowercases and de-duplicates in order", () => {
    expect(normalizeGroupPaths([" /Platform/ ", "apps/Web", "PLATFORM"])).toEqual(["platform", "apps/web"]);
  });

  test("toGitLabConnectionRequest trims, drops the trailing slash and a blank token, normalises paths", () => {
    expect(
      toGitLabConnectionRequest({ ...GITLAB, baseUrl: " https://gitlab.acme.com/ ", token: "  ", groupPaths: ["/Platform/", "apps/web", "platform"] }),
    ).toEqual({ baseUrl: "https://gitlab.acme.com", token: undefined, groupPaths: ["platform", "apps/web"] });
    expect(toGitLabConnectionRequest({ ...GITLAB, token: " glpat-x " }).token).toBe("glpat-x");
  });

  test("toDataSourceRequest emits exactly one connection block, by kind", () => {
    const gitlab = toDataSourceRequest(GITLAB);
    expect(gitlab.gitlab).toEqual({ baseUrl: "https://gitlab.acme.com", token: "glpat-token", groupPaths: ["platform", "apps/web"] });
    expect(gitlab).not.toHaveProperty("jira");
    expect(gitlab.name).toBe("Acme GitLab");
    const jira = toDataSourceRequest(VALID);
    expect(jira.jira?.siteUrl).toBe("https://acme.atlassian.net");
    expect(jira).not.toHaveProperty("gitlab");
  });

  const STORED_GITLAB: DataSourceResponse = {
    id: 5,
    kind: "GITLAB",
    name: "Acme GitLab",
    enabled: false,
    syncIntervalMinutes: 30,
    backfillFrom: "2024-02-01",
    reconcileHourUtc: 4,
    configRevision: 1,
    jira: null,
    gitlab: { baseUrl: "https://gitlab.acme.com", hasToken: true, groupPaths: ["platform", "apps/web"], pathExclusions: [] },
    status: {
      state: "NEVER_SYNCED",
      consecutiveFailures: 0,
      limitTrips: 0,
      reconcileLimitTrips: 0,
    },
    createdAt: 1,
    updatedAt: 2,
  };

  test("fromDataSourceResponse fills the GitLab fields, leaves the Jira ones empty and the token blank (write-only)", () => {
    expect(fromDataSourceResponse(STORED_GITLAB)).toEqual({
      kind: "GITLAB",
      name: "Acme GitLab",
      enabled: false,
      syncIntervalMinutes: 30,
      backfillFrom: "2024-02-01",
      reconcileHourUtc: 4,
      siteUrl: "",
      email: "",
      apiToken: "",
      projectKeys: [],
      authScheme: "BASIC",
      baseUrl: "https://gitlab.acme.com",
      token: "",
      groupPaths: ["platform", "apps/web"],
    });
  });

  test("dataSourceHost and dataSourceScope read the kind's own block", () => {
    expect(dataSourceHost(STORED_GITLAB)).toBe("gitlab.acme.com");
    expect(dataSourceScope(STORED_GITLAB)).toEqual(["platform", "apps/web"]);
    const jira = { ...STORED_GITLAB, kind: "JIRA_CLOUD", gitlab: null, jira: { siteUrl: "https://acme.atlassian.net", email: "a@b.c", hasApiToken: true, projectKeys: ["ENG"], authScheme: "BASIC" } } as DataSourceResponse;
    expect(dataSourceHost(jira)).toBe("acme.atlassian.net");
    expect(dataSourceScope(jira)).toEqual(["ENG"]);
    const neither = { ...STORED_GITLAB, gitlab: null, jira: null } as DataSourceResponse;
    expect(dataSourceHost(neither)).toBe("");
    expect(dataSourceScope(neither)).toEqual([]);
  });
});

describe("dataSourceSaveErrorMessage", () => {
  test("maps 403/404/400 to their fixed vocabulary, and any other status to the generic save failure", () => {
    expect(dataSourceSaveErrorMessage(new ApiError(403, {}), t)).toBe("Only administrators can manage data sources");
    expect(dataSourceSaveErrorMessage(new ApiError(404, {}), t)).toBe(
      "This data source no longer exists — reload the page",
    );
    expect(dataSourceSaveErrorMessage(new ApiError(400, {}), t)).toBe("One or more fields are invalid");
    expect(dataSourceSaveErrorMessage(new ApiError(500, {}), t)).toBe("Save failed (500)");
    expect(dataSourceSaveErrorMessage(new Error("network down"), t)).toBe(
      "Save failed. Check your connection and try again.",
    );
  });
});

describe("testConnectionErrorMessage", () => {
  test("maps 429/403/400 to their fixed vocabulary, and any other status to the generic test failure", () => {
    expect(testConnectionErrorMessage(new ApiError(429, {}), t)).toBe(
      "Too many test attempts — wait a minute and try again",
    );
    expect(testConnectionErrorMessage(new ApiError(403, {}), t)).toBe("Only administrators can manage data sources");
    expect(testConnectionErrorMessage(new ApiError(400, {}), t)).toBe("One or more fields are invalid");
    expect(testConnectionErrorMessage(new ApiError(500, {}), t)).toBe("Action failed (500)");
    expect(testConnectionErrorMessage(new Error("network down"), t)).toBe(
      "Test connection failed. Check your connection and try again.",
    );
  });
});

describe("dataSourceConflictField", () => {
  test("the problem type picks the field; anything else is the name clash", () => {
    expect(dataSourceConflictField(new ApiError(409, { type: DATA_SOURCE_PROBLEM_TYPES.otherSite }))).toBe("siteUrl");
    expect(dataSourceConflictField(new ApiError(409, { type: DATA_SOURCE_PROBLEM_TYPES.projectOverlap }))).toBe("projectKeys");
    expect(dataSourceConflictField(new ApiError(409, { type: DATA_SOURCE_PROBLEM_TYPES.otherInstance }))).toBe("baseUrl");
    expect(dataSourceConflictField(new ApiError(409, { type: DATA_SOURCE_PROBLEM_TYPES.groupOverlap }))).toBe("groupPaths");
    expect(dataSourceConflictField(new ApiError(409, { type: "about:blank" }))).toBe("name");
    expect(dataSourceConflictField(new ApiError(409, null))).toBe("name");
  });
});
