import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import type { MetricsSettingsResponse } from "../api/metrics";
import {
  EMPTY_METRICS_SETTINGS_FORM,
  fromMetricsSettingsResponse,
  metricsSettingsFormValidation,
  metricsSettingsSaveErrorMessage,
  REQUIRED_AGING_PERCENTILE,
  supportedTimeZones,
  toMetricsSettingsRequest,
  type MetricsSettingsFormValues,
} from "./metricsForm";

const t = i18n.t;

const VALID: MetricsSettingsFormValues = {
  ...EMPTY_METRICS_SETTINGS_FORM,
};

describe("metricsSettingsFormValidation", () => {
  const rules = metricsSettingsFormValidation(t);

  test("hoursPerDay: rejects 0, negative and over 24; accepts the open range", () => {
    expect(rules.hoursPerDay(0)).toBe("Hours per day must be greater than 0 and at most 24");
    expect(rules.hoursPerDay(-1)).toBe("Hours per day must be greater than 0 and at most 24");
    expect(rules.hoursPerDay(24.5)).toBe("Hours per day must be greater than 0 and at most 24");
    expect(rules.hoursPerDay(8)).toBeNull();
    expect(rules.hoursPerDay(24)).toBeNull();
  });

  test("timeZone: rejects an unknown zone id, accepts a known one", () => {
    expect(rules.timeZone("Not/AZone")).toBe("Must be a valid time zone, e.g. Europe/Warsaw");
    expect(rules.timeZone("Europe/Warsaw")).toBeNull();
  });

  test("timeZone: supportedTimeZones names a real IANA zone id", () => {
    expect(supportedTimeZones()).toContain("Europe/Warsaw");
  });

  test("weekendDays: rejects an out-of-range value", () => {
    expect(rules.weekendDays(["0"])).toBe("Weekend days must each be Monday .. Sunday");
    expect(rules.weekendDays(["8"])).toBe("Weekend days must each be Monday .. Sunday");
  });

  test("weekendDays: rejects marking every day of the week", () => {
    expect(rules.weekendDays(["1", "2", "3", "4", "5", "6", "7"])).toBe(
      "Weekend days must not mark every day of the week as non-working",
    );
  });

  test("weekendDays: accepts the default Sat/Sun and an empty set", () => {
    expect(rules.weekendDays(["6", "7"])).toBeNull();
    expect(rules.weekendDays([])).toBeNull();
  });

  test("commitmentGraceMinutes: rejects negative, accepts 0 and positive", () => {
    expect(rules.commitmentGraceMinutes(-1)).toBe("Commitment grace must be 0 or more");
    expect(rules.commitmentGraceMinutes(0)).toBeNull();
    expect(rules.commitmentGraceMinutes(30)).toBeNull();
  });

  test("minSampleSize: rejects below 1", () => {
    expect(rules.minSampleSize(0)).toBe("Minimum sample size must be at least 1");
    expect(rules.minSampleSize(1)).toBeNull();
  });

  test("agingWindowItems: rejects below 1", () => {
    expect(rules.agingWindowItems(0)).toBe("Aging-WIP window must be at least 1");
    expect(rules.agingWindowItems(50)).toBeNull();
  });

  test("agingPercentiles: rejects an empty set", () => {
    expect(rules.agingPercentiles([])).toBe("At least one aging percentile is required");
  });

  test("agingPercentiles: rejects an out-of-range or non-integer value", () => {
    expect(rules.agingPercentiles(["0", "85"])).toBe("Aging percentiles must each be a whole number between 1 and 99");
    expect(rules.agingPercentiles(["100", "85"])).toBe("Aging percentiles must each be a whole number between 1 and 99");
    expect(rules.agingPercentiles(["50.5", "85"])).toBe("Aging percentiles must each be a whole number between 1 and 99");
  });

  test("agingPercentiles: rejects a duplicate", () => {
    expect(rules.agingPercentiles(["85", "85"])).toBe("Aging percentiles must not contain duplicates");
  });

  test("agingPercentiles: rejects a set missing the required 85", () => {
    expect(rules.agingPercentiles(["50", "95"])).toBe(`Aging percentiles must include ${REQUIRED_AGING_PERCENTILE}`);
  });

  test("agingPercentiles: accepts a valid distinct set including 85", () => {
    expect(rules.agingPercentiles(["50", "85", "95"])).toBeNull();
  });

  test("backlogWindowSprints: rejects below 1", () => {
    expect(rules.backlogWindowSprints(0)).toBe("Backlog window must be at least 1 sprint");
    expect(rules.backlogWindowSprints(3)).toBeNull();
  });

  test("epicDriftDays: rejects negative, accepts 0", () => {
    expect(rules.epicDriftDays(-1)).toBe("Epic drift threshold must be 0 or more");
    expect(rules.epicDriftDays(0)).toBeNull();
  });

  test("epicEstimateGapTolerancePercent: accepts 0..100, rejects outside and fractions", () => {
    expect(rules.epicEstimateGapTolerancePercent(-1)).toBe("Epic estimate gap tolerance must be a whole percent between 0 and 100");
    expect(rules.epicEstimateGapTolerancePercent(101)).toBe("Epic estimate gap tolerance must be a whole percent between 0 and 100");
    expect(rules.epicEstimateGapTolerancePercent(12.5)).not.toBeNull();
    expect(rules.epicEstimateGapTolerancePercent(0)).toBeNull();
    expect(rules.epicEstimateGapTolerancePercent(100)).toBeNull();
  });

  test("historyHorizonMonths: accepts 6..120 whole months while the switch is on, ignores the number while it is off", () => {
    const on = { ...VALID, historyHorizonEnabled: true };
    const message = "History horizon must be a whole number of months between 6 and 120";
    expect(rules.historyHorizonMonths(5, on)).toBe(message);
    expect(rules.historyHorizonMonths(121, on)).toBe(message);
    expect(rules.historyHorizonMonths(12.5, on)).toBe(message);
    expect(rules.historyHorizonMonths("", on)).toBe(message);
    expect(rules.historyHorizonMonths(6, on)).toBeNull();
    expect(rules.historyHorizonMonths(120, on)).toBeNull();
    const off = { ...VALID, historyHorizonEnabled: false };
    expect(rules.historyHorizonMonths("", off)).toBeNull();
    expect(rules.historyHorizonMonths(1, off)).toBeNull();
  });

  test("every default form value passes validation", () => {
    for (const [field, rule] of Object.entries(rules)) {
      const value = VALID[field as keyof MetricsSettingsFormValues];
      expect((rule as (v: never, values: MetricsSettingsFormValues) => string | null)(value as never, VALID)).toBeNull();
    }
  });
});

describe("fromMetricsSettingsResponse / toMetricsSettingsRequest", () => {
  const response: MetricsSettingsResponse = {
    configRevision: 3,
    hoursPerDay: 8,
    timeZone: "Europe/Warsaw",
    weekendDays: [6, 7],
    commitmentGraceMinutes: 0,
    minSampleSize: 5,
    agingWindowItems: 50,
    agingPercentiles: [50, 85, 95],
    backlogWindowSprints: 3,
    epicDriftDays: 14,
    epicEstimateGapTolerancePercent: 25,
    historyHorizonMonths: 24,
    historyHorizonFrom: "2024-10-30",
    updatedAt: 1700000000000,
    updatedByUserId: 1,
  };

  test("round-trips the response into form values and back into a request", () => {
    const form = fromMetricsSettingsResponse(response);
    expect(form.weekendDays).toEqual(["6", "7"]);
    expect(form.agingPercentiles).toEqual(["50", "85", "95"]);

    const request = toMetricsSettingsRequest(form);
    expect(request).toEqual({
      hoursPerDay: 8,
      timeZone: "Europe/Warsaw",
      weekendDays: [6, 7],
      commitmentGraceMinutes: 0,
      minSampleSize: 5,
      agingWindowItems: 50,
      agingPercentiles: [50, 85, 95],
      backlogWindowSprints: 3,
      epicDriftDays: 14,
      epicEstimateGapTolerancePercent: 25,
      historyHorizonMonths: 24,
    });
  });

  test("a null horizon is a switched-off form that keeps the default number, and a switched-off form sends null again", () => {
    const form = fromMetricsSettingsResponse({ ...response, historyHorizonMonths: null, historyHorizonFrom: null });
    expect(form.historyHorizonEnabled).toBe(false);
    expect(form.historyHorizonMonths).toBe(24);
    expect(toMetricsSettingsRequest(form).historyHorizonMonths).toBeNull();
    expect(toMetricsSettingsRequest({ ...form, historyHorizonEnabled: true, historyHorizonMonths: 6 }).historyHorizonMonths).toBe(6);
    expect(toMetricsSettingsRequest({ ...form, historyHorizonEnabled: true, historyHorizonMonths: "" }).historyHorizonMonths).toBeNull();
  });

  test("the request carries no holidays: they are the public-holiday dictionary, not a setting", () => {
    expect(toMetricsSettingsRequest(fromMetricsSettingsResponse(response))).not.toHaveProperty("holidays");
  });
});

describe("metricsSettingsSaveErrorMessage", () => {
  test("maps 403 and 400 to the fixed vocabulary, falls back otherwise", () => {
    expect(metricsSettingsSaveErrorMessage(new ApiError(403, {}), t)).toBe(
      "Only administrators can edit metrics settings",
    );
    expect(metricsSettingsSaveErrorMessage(new ApiError(400, {}), t)).toBe("One or more fields are invalid");
    expect(metricsSettingsSaveErrorMessage(new ApiError(500, {}), t)).toBe("Save failed (500)");
  });
});
