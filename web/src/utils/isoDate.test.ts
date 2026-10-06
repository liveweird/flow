import { afterEach, describe, expect, test, vi } from "vitest";
import { epochMillisToIsoDate, epochMillisToIsoDateInZone, isoDateToEpochMillis, isoDateToEpochMillisInZone, isValidIsoDate, startOfDayEpochMillisInZone, startOfTodayEpochMillis, todayIsoDate } from "./isoDate";

describe("isValidIsoDate", () => {
  test("rejects a malformed shape", () => {
    expect(isValidIsoDate("2024-1-1")).toBe(false);
    expect(isValidIsoDate("not-a-date")).toBe(false);
    expect(isValidIsoDate("")).toBe(false);
  });

  test("rejects an impossible calendar date (overflow normalizes to a different date)", () => {
    expect(isValidIsoDate("2024-02-30")).toBe(false);
    expect(isValidIsoDate("2024-13-01")).toBe(false);
  });

  test("accepts a real date, including a leap day", () => {
    expect(isValidIsoDate("2024-02-29")).toBe(true);
    expect(isValidIsoDate("2024-01-01")).toBe(true);
  });
});

describe("isoDateToEpochMillis / epochMillisToIsoDate", () => {
  test("round-trips through UTC midnight", () => {
    const ms = isoDateToEpochMillis("2024-01-15");
    expect(ms).toBe(Date.UTC(2024, 0, 15));
    expect(epochMillisToIsoDate(ms)).toBe("2024-01-15");
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("todayIsoDate", () => {
  test("is the UTC date under a fixed clock", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2024-06-15T13:45:00Z"));
    expect(todayIsoDate()).toBe("2024-06-15");
  });
});

describe("startOfTodayEpochMillis / startOfDayEpochMillisInZone", () => {
  const at = (iso: string) => Date.parse(iso);

  function startOfToday(nowIso: string, timeZone: string): string {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(nowIso));
    return new Date(startOfTodayEpochMillis(timeZone)).toISOString();
  }

  test("UTC is UTC midnight", () => {
    expect(startOfToday("2024-06-15T13:45:00Z", "UTC")).toBe("2024-06-15T00:00:00.000Z");
  });

  test("a zone ahead of UTC: Warsaw is already on the 16th at 22:30Z on the 15th", () => {
    expect(startOfToday("2026-06-15T22:30:00Z", "Europe/Warsaw")).toBe("2026-06-15T22:00:00.000Z");
    // Kiritimati (UTC+14): 12:00Z is 02:00 on the 16th, so its midnight is 10:00Z on the 15th.
    expect(startOfToday("2026-06-15T12:00:00Z", "Pacific/Kiritimati")).toBe("2026-06-15T10:00:00.000Z");
  });

  test("a zone behind UTC: Los Angeles is still on the 14th at 03:00Z on the 15th", () => {
    expect(startOfToday("2026-06-15T03:00:00Z", "America/Los_Angeles")).toBe("2026-06-14T07:00:00.000Z");
    // Standard time (-08:00) in winter.
    expect(startOfToday("2026-01-15T03:00:00Z", "America/Los_Angeles")).toBe("2026-01-14T08:00:00.000Z");
  });

  test("the instant of midnight belongs to the new day, the millisecond before to the old one", () => {
    const midnight = at("2026-06-15T22:00:00Z"); // 00:00 on the 16th in Warsaw
    expect(startOfDayEpochMillisInZone(midnight, "Europe/Warsaw")).toBe(midnight);
    expect(startOfDayEpochMillisInZone(midnight - 1, "Europe/Warsaw")).toBe(at("2026-06-14T22:00:00Z"));
  });

  test("DST change days in Warsaw: the 25-hour and the 23-hour day cut at the right offset", () => {
    // Fall back 2026-10-25 (03:00 CEST -> 02:00 CET): midnight is still CEST (+2), the next one CET (+1).
    expect(new Date(startOfDayEpochMillisInZone(at("2026-10-25T12:00:00Z"), "Europe/Warsaw")).toISOString()).toBe("2026-10-24T22:00:00.000Z");
    expect(new Date(startOfDayEpochMillisInZone(at("2026-10-26T10:00:00Z"), "Europe/Warsaw")).toISOString()).toBe("2026-10-25T23:00:00.000Z");
    // Spring forward 2026-03-29 (02:00 CET -> 03:00 CEST).
    expect(new Date(startOfDayEpochMillisInZone(at("2026-03-29T12:00:00Z"), "Europe/Warsaw")).toISOString()).toBe("2026-03-28T23:00:00.000Z");
    expect(new Date(startOfDayEpochMillisInZone(at("2026-03-30T10:00:00Z"), "Europe/Warsaw")).toISOString()).toBe("2026-03-29T22:00:00.000Z");
  });

  test("a zone whose midnight is skipped starts the day at the transition (Sao Paulo, 2018-11-04: 00:00 -> 01:00)", () => {
    expect(new Date(startOfDayEpochMillisInZone(at("2018-11-04T15:00:00Z"), "America/Sao_Paulo")).toISOString()).toBe("2018-11-04T03:00:00.000Z");
  });
});

describe("zone-aware dates", () => {
  test("todayIsoDate follows the zone, not UTC: 23:30Z on Oct 31 is 00:30 on Nov 1 in Warsaw", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-31T23:30:00Z"));
    expect(todayIsoDate()).toBe("2026-10-31");
    expect(todayIsoDate("Europe/Warsaw")).toBe("2026-11-01");
    // A zone behind UTC keeps the previous day.
    expect(todayIsoDate("America/New_York")).toBe("2026-10-31");
  });

  test("epochMillisToIsoDateInZone handles the DST change day", () => {
    // Warsaw leaves DST on 2026-10-25: 00:30 local (22:30Z the day before) is still the 25th.
    expect(epochMillisToIsoDateInZone(Date.UTC(2026, 9, 24, 22, 30), "Europe/Warsaw")).toBe("2026-10-25");
  });
});

describe("isoDateToEpochMillisInZone", () => {
  const iso = (value: string, timeZone: string) => new Date(isoDateToEpochMillisInZone(value, timeZone)).toISOString();

  test("a calendar day is the zone's midnight, ahead of or behind UTC", () => {
    expect(iso("2024-06-01", "UTC")).toBe("2024-06-01T00:00:00.000Z");
    expect(iso("2024-06-01", "Europe/Warsaw")).toBe("2024-05-31T22:00:00.000Z");
    expect(iso("2024-01-01", "Europe/Warsaw")).toBe("2023-12-31T23:00:00.000Z");
    expect(iso("2026-06-15", "Pacific/Kiritimati")).toBe("2026-06-14T10:00:00.000Z");
    expect(iso("2026-06-15", "America/Los_Angeles")).toBe("2026-06-15T07:00:00.000Z");
  });

  test("DST change days use the offset in force at that midnight", () => {
    expect(iso("2026-10-25", "Europe/Warsaw")).toBe("2026-10-24T22:00:00.000Z");
    expect(iso("2026-10-26", "Europe/Warsaw")).toBe("2026-10-25T23:00:00.000Z");
    expect(iso("2026-03-29", "Europe/Warsaw")).toBe("2026-03-28T23:00:00.000Z");
    expect(iso("2026-03-30", "Europe/Warsaw")).toBe("2026-03-29T22:00:00.000Z");
  });

  // Every 2026 DST change of these zones (±1 day) plus a weekly sample of the year: the transitions are where a
  // conversion can slip, and a whole year per zone (~1.5k bisections) ran past the 5 s timeout under CI coverage.
  const TRANSITIONS = ["2026-03-08", "2026-03-29", "2026-04-05", "2026-10-04", "2026-10-25", "2026-11-01"];
  const SAMPLED_DAYS = [
    ...new Set([
      ...Array.from({ length: 53 }, (_, week) => new Date(Date.UTC(2026, 0, 1 + week * 7)).toISOString().slice(0, 10)),
      ...TRANSITIONS.flatMap((day) =>
        [-1, 0, 1].map((shift) => new Date(Date.parse(`${day}T00:00:00Z`) + shift * 86_400_000).toISOString().slice(0, 10)),
      ),
    ]),
  ].filter((day) => day.startsWith("2026"));

  test("round-trips through epochMillisToIsoDateInZone around every DST change and weekly through a year in several zones", () => {
    for (const zone of ["Europe/Warsaw", "America/Los_Angeles", "Pacific/Kiritimati", "Australia/Lord_Howe"]) {
      for (const date of SAMPLED_DAYS) {
        expect(epochMillisToIsoDateInZone(isoDateToEpochMillisInZone(date, zone), zone)).toBe(date);
      }
    }
  });
});
