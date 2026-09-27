import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import type { DataSourceResponse } from "../api/dataSources";
import {
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

describe("dataSourceFormValidation", () => {
  const rulesCreate = dataSourceFormValidation(t, false);
  const rulesEdit = dataSourceFormValidation(t, true);

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
        runningJobId: null,
      },
      createdAt: 1,
      updatedAt: 2,
    };
    expect(fromDataSourceResponse(response)).toEqual({
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
