import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { loadErrorMessage, saveErrorMessage } from "./saveError";

const t = i18n.t;

const KEYS = {
  forbidden: "flowUsers.lastAdminError",
  notFound: "flowUsers.userGone",
  conflict: "flowUsers.emailAlreadyInUse",
  invalid: "common.error.actionFailed",
  tooLarge: "dataSources.inspect.tooLarge",
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
    expect(saveErrorMessage(new ApiError(413, null), t, KEYS)).toBe(t(KEYS.tooLarge));
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
    expect(loadErrorMessage(new ApiError(429, null), t)).toBe("Too many requests. Wait a minute and try again.");
    expect(loadErrorMessage(timeout, t)).toBe(t("common.error.timeout"));
    expect(loadErrorMessage(new Error("net"), t)).toBe(t("common.error.network"));
  });

  test("the code reads' two refusals get a sentence of their own, told by status and the problem's detail", () => {
    const budget = new ApiError(400, {
      status: 400,
      detail: "The selection reads more than 100000 code rows (commits, merge requests and review events) - narrow the period or pick fewer people",
    });
    const busy = new ApiError(429, { status: 429, detail: "Too many code activity reports are being computed at once - retry in a moment" });
    expect(loadErrorMessage(budget, t)).toBe("This selection is too large to read. Narrow the period or pick fewer people.");
    expect(loadErrorMessage(busy, t)).toBe("The server is busy computing other reports. Retry in a moment.");
    // Any other 400, and the per-user rate limit's 429 (a bare problem body), keep the generic mapping.
    expect(loadErrorMessage(new ApiError(400, { status: 400, detail: "Unknown or inactive connectionId" }), t)).toBe("Load failed (400)");
    expect(loadErrorMessage(new ApiError(400, null), t)).toBe("Load failed (400)");
    expect(loadErrorMessage(new ApiError(429, { status: 429, detail: "Too many requests" }), t)).toBe("Too many requests. Wait a minute and try again.");
    // The phrase alone is not enough: the status decides.
    expect(loadErrorMessage(new ApiError(500, { status: 500, detail: "narrow the period or pick fewer people" }), t)).toBe("Load failed (500)");
  });
});
