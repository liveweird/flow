import type { TFunction } from "i18next";
import type { MetricsSettingsRequest, MetricsSettingsResponse } from "../api/metrics";
import { isValidIsoDate } from "./isoDate";
import { saveErrorMessage } from "./saveError";

// Server limits (metrics/MetricsSettings.kt's validateMetricsSettings) mirrored client-side.
const MIN_HOURS_PER_DAY = 0;
const MAX_HOURS_PER_DAY = 24;
/** ISO weekday numbers Jira/Java both use: 1 = Monday .. 7 = Sunday. */
const VALID_WEEKDAY_NUMBERS = [1, 2, 3, 4, 5, 6, 7] as const;
/** A generous ceiling, not a real-world expectation — mirrors the server's JSONB array bound. */
const MAX_HOLIDAYS = 366;
const VALID_PERCENTILE_MIN = 1;
const VALID_PERCENTILE_MAX = 99;
/** Report 11 (aging WIP) reads `p85` off this list by name — the overview relies on it always being present. */
export const REQUIRED_AGING_PERCENTILE = 85;

const DEFAULT_HOURS_PER_DAY = 8;
const DEFAULT_TIME_ZONE = "Europe/Warsaw";

/** Chip.Group options for the weekend picker — Mon..Sun, values carried as strings (Mantine's Chip contract). */
export const WEEKEND_DAY_OPTIONS: ReadonlyArray<{ value: string; dayNumber: number }> = VALID_WEEKDAY_NUMBERS.map(
  (dayNumber) => ({ value: String(dayNumber), dayNumber }),
);

let cachedTimeZones: string[] | null = null;

/** `Intl.supportedValuesOf("timeZone")`, cached — every IANA zone id the runtime knows about. */
export function supportedTimeZones(): string[] {
  if (cachedTimeZones) return cachedTimeZones;
  try {
    cachedTimeZones = Intl.supportedValuesOf("timeZone");
  } catch {
    cachedTimeZones = [DEFAULT_TIME_ZONE, "UTC"];
  }
  return cachedTimeZones;
}

function isValidTimeZone(value: string): boolean {
  return supportedTimeZones().includes(value);
}

export type MetricsSettingsFormValues = {
  hoursPerDay: number;
  timeZone: string;
  /** Chip.Group values (strings) for ISO weekday numbers 1..7. */
  weekendDays: string[];
  /** TagsInput values — ISO dates (`YYYY-MM-DD`). */
  holidays: string[];
  commitmentGraceMinutes: number;
  minSampleSize: number;
  agingWindowItems: number;
  /** TagsInput values — percentiles 1..99, stringified. */
  agingPercentiles: string[];
  backlogWindowSprints: number;
  epicDriftDays: number;
};

export const EMPTY_METRICS_SETTINGS_FORM: MetricsSettingsFormValues = {
  hoursPerDay: DEFAULT_HOURS_PER_DAY,
  timeZone: DEFAULT_TIME_ZONE,
  weekendDays: ["6", "7"],
  holidays: [],
  commitmentGraceMinutes: 0,
  minSampleSize: 5,
  agingWindowItems: 50,
  agingPercentiles: ["50", "85", "95"],
  backlogWindowSprints: 3,
  epicDriftDays: 14,
};

export function fromMetricsSettingsResponse(data: MetricsSettingsResponse): MetricsSettingsFormValues {
  return {
    hoursPerDay: data.hoursPerDay,
    timeZone: data.timeZone,
    weekendDays: data.weekendDays.map(String),
    holidays: [...data.holidays],
    commitmentGraceMinutes: data.commitmentGraceMinutes,
    minSampleSize: data.minSampleSize,
    agingWindowItems: data.agingWindowItems,
    agingPercentiles: data.agingPercentiles.map(String),
    backlogWindowSprints: data.backlogWindowSprints,
    epicDriftDays: data.epicDriftDays,
  };
}

/** Dedupes holidays (a client resubmitting the same date twice is not itself an error) — mirrors `sanitizedMetricsSettings`. */
export function toMetricsSettingsRequest(values: MetricsSettingsFormValues): MetricsSettingsRequest {
  return {
    hoursPerDay: values.hoursPerDay,
    timeZone: values.timeZone,
    weekendDays: values.weekendDays.map(Number),
    holidays: [...new Set(values.holidays)],
    commitmentGraceMinutes: values.commitmentGraceMinutes,
    minSampleSize: values.minSampleSize,
    agingWindowItems: values.agingWindowItems,
    agingPercentiles: values.agingPercentiles.map(Number),
    backlogWindowSprints: values.backlogWindowSprints,
    epicDriftDays: values.epicDriftDays,
  };
}

/** Validation rules mirroring `validateMetricsSettings` exactly, field for field. */
export function metricsSettingsFormValidation(t: TFunction) {
  return {
    hoursPerDay: (value: number) =>
      value > MIN_HOURS_PER_DAY && value <= MAX_HOURS_PER_DAY ? null : t("metrics.settings.validation.hoursPerDay"),
    timeZone: (value: string) => (isValidTimeZone(value) ? null : t("metrics.settings.validation.timeZone")),
    weekendDays: (value: string[]) => {
      const numbers = value.map(Number);
      if (numbers.some((n) => !VALID_WEEKDAY_NUMBERS.includes(n as (typeof VALID_WEEKDAY_NUMBERS)[number]))) {
        return t("metrics.settings.validation.weekendDaysInvalid");
      }
      if (new Set(numbers).size === VALID_WEEKDAY_NUMBERS.length) return t("metrics.settings.validation.weekendDaysAllWeek");
      return null;
    },
    holidays: (value: string[]) => {
      if (value.length > MAX_HOLIDAYS) return t("metrics.settings.validation.holidaysCount", { max: MAX_HOLIDAYS });
      const bad = value.find((iso) => !isValidIsoDate(iso));
      return bad ? t("metrics.settings.validation.holidaysInvalid", { date: bad }) : null;
    },
    commitmentGraceMinutes: (value: number) => (value >= 0 ? null : t("metrics.settings.validation.commitmentGraceMinutes")),
    minSampleSize: (value: number) => (value >= 1 ? null : t("metrics.settings.validation.minSampleSize")),
    agingWindowItems: (value: number) => (value >= 1 ? null : t("metrics.settings.validation.agingWindowItems")),
    agingPercentiles: (value: string[]) => {
      if (value.length === 0) return t("metrics.settings.validation.agingPercentilesEmpty");
      const numbers = value.map(Number);
      if (numbers.some((n) => !Number.isInteger(n) || n < VALID_PERCENTILE_MIN || n > VALID_PERCENTILE_MAX)) {
        return t("metrics.settings.validation.agingPercentilesRange");
      }
      if (new Set(numbers).size !== numbers.length) return t("metrics.settings.validation.agingPercentilesDuplicate");
      if (!numbers.includes(REQUIRED_AGING_PERCENTILE)) {
        return t("metrics.settings.validation.agingPercentilesMustInclude85", { required: REQUIRED_AGING_PERCENTILE });
      }
      return null;
    },
    backlogWindowSprints: (value: number) => (value >= 1 ? null : t("metrics.settings.validation.backlogWindowSprints")),
    epicDriftDays: (value: number) => (value >= 0 ? null : t("metrics.settings.validation.epicDriftDays")),
  };
}

export function metricsSettingsSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "metrics.settings.saveForbidden",
    invalid: "metrics.settings.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
