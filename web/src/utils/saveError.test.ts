import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { loadErrorMessage, saveErrorMessage } from "./saveError";

const t = i18n.t;

const KEYS = {
  forbidden: "users.lastAdminError",
  notFound: "users.userGone",
  conflict: "users.emailAlreadyInUse",
  invalid: "common.error.actionFailed",
  failedStatus: "common.error.createFailedStatus",
  failed: "common.error.createFailedNetwork",
} as const;

const timeout = new DOMException("timed out", "TimeoutError");

describe("saveErrorMessage", () => {
  test("maps each status to its key and falls back per class", () => {
    expect(saveErrorMessage(new ApiError(403, null), t, KEYS)).toBe(t(KEYS.forbidden));
    expect(saveErrorMessage(new ApiError(404, null), t, KEYS)).toBe(t(KEYS.notFound));
    expect(saveErrorMessage(new ApiError(409, null), t, KEYS)).toBe(t(KEYS.conflict));
    expect(saveErrorMessage(new ApiError(400, null), t, KEYS)).toBe(t(KEYS.invalid));
    expect(saveErrorMessage(new ApiError(500, null), t, KEYS)).toBe("Create failed (500)");
    expect(saveErrorMessage(timeout, t, KEYS)).toBe(t("common.error.timeout"));
    expect(saveErrorMessage(new Error("net"), t, KEYS)).toBe(t(KEYS.failed));
  });

  test("an unmapped status routes through the generic fallbacks when keys are omitted", () => {
    const minimal = { failed: KEYS.failed } as const;
    expect(saveErrorMessage(new ApiError(404, null), t, minimal)).toBe(t(KEYS.failed));
  });
});

describe("loadErrorMessage", () => {
  test("tags ApiErrors with the status and falls back per class", () => {
    expect(loadErrorMessage(new ApiError(502, null), t)).toBe("Load failed (502)");
    expect(loadErrorMessage(timeout, t)).toBe(t("common.error.timeout"));
    expect(loadErrorMessage(new Error("net"), t)).toBe(t("common.error.network"));
  });
});
