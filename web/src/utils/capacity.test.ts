import { describe, expect, test } from "vitest";
import {
  calendarCapacityMd,
  clampTo,
  dayCapacityTotals,
  isCounted,
  sameDraft,
  toDraft,
  toCalendarBody,
  withCell,
  withDayEnabled,
  withPersonCoefficient,
  withPersonEnabled,
  withTeamCoefficient,
  type CalendarDraft,
} from "./capacity";

const DAYS = ["2026-03-02", "2026-03-03", "2026-03-04", "2026-03-05", "2026-03-06"];

function person(accountId: string, coefficient: number, availability: number[], enabled = true) {
  return {
    accountId,
    displayName: accountId.toUpperCase(),
    member: true,
    enabled,
    coefficient,
    cells: DAYS.map((day, i) => ({ day, availability: availability[i] })),
  };
}

// The SAME document as the server's CapacityKernelsTest: five days with D2 off, two counted people and one off person.
const DOC: CalendarDraft = {
  teamCoefficient: 1.2,
  days: DAYS.map((day, i) => ({ day, enabled: i !== 1, holiday: null, claimedBy: null })),
  people: [
    person("a", 0.8, [1, 1, 0.5, 1, 0.5]),
    person("b", 1.2, [0.5, 0.5, 1, 0.25, 1]),
    person("c", 1, [1, 1, 1, 1, 1], false),
  ],
};

describe("calendarCapacityMd (the server kernel's formula)", () => {
  test("equals the server's figure on the shared hand-built document", () => {
    // a: 0.8 x (1 + 0.5 + 1 + 0.5) = 2.4; b: 1.2 x (0.5 + 1 + 0.25 + 1) = 3.3; c is off, D2 is off; 1.2 x 5.7 = 6.84.
    expect(String(calendarCapacityMd(DOC))).toBe("6.84");
  });

  test("is exact decimal arithmetic rounded half-up to two decimals at the end", () => {
    const half = { teamCoefficient: 1, days: [{ day: DAYS[0], enabled: true }], people: [{ ...person("a", 1.05, [0.5, 0, 0, 0, 0]) }] };
    expect(String(calendarCapacityMd(half))).toBe("0.53");
    const drift = {
      teamCoefficient: 1,
      days: DAYS.slice(0, 2).map((day) => ({ day, enabled: true })),
      people: [person("a", 1, [0.1, 0.2, 0, 0, 0])],
    };
    expect(String(calendarCapacityMd(drift))).toBe("0.3");
    expect(calendarCapacityMd({ teamCoefficient: 1, days: [], people: [] })).toBe(0);
  });

  test("a counted day with no cell for a person counts zero", () => {
    const gap = { ...DOC, people: [{ ...person("a", 1, [1, 0, 0, 0, 0]), cells: [{ day: DAYS[0], availability: 1 }] }] };
    expect(String(calendarCapacityMd(gap))).toBe("1.2");
  });
});

describe("dayCapacityTotals", () => {
  test("is the counted people's cells of every day, before any coefficient", () => {
    const totals = dayCapacityTotals(DOC.people);
    // c is off; D2 is a day like any other here (the day toggle is the caller's concern).
    expect(String(totals.get(DAYS[0]))).toBe("1.5");
    expect(String(totals.get(DAYS[1]))).toBe("1.5");
    expect(String(totals.get(DAYS[3]))).toBe("1.25");
    expect(totals.get("2099-01-01")).toBeUndefined();
  });
});

describe("withheld coefficients (a read-only caller)", () => {
  const withheld: CalendarDraft = { ...DOC, people: DOC.people.map((p) => ({ ...p, coefficient: null })) };

  test("the formula has no figure while a counted person's coefficient is withheld", () => {
    expect(calendarCapacityMd(withheld)).toBeNull();
    // An off person's withheld coefficient does not matter.
    const offOnly = { ...withheld, people: withheld.people.map((p) => (p.enabled ? { ...p, coefficient: 1 } : p)) };
    expect(calendarCapacityMd(offOnly)).not.toBeNull();
  });

  test("the footer sums cells and never needs a coefficient", () => {
    expect(String(dayCapacityTotals(withheld.people).get(DAYS[0]))).toBe("1.5");
  });

  test("a body cannot be built from a draft with withheld coefficients", () => {
    expect(() => toCalendarBody(withheld)).toThrow(/withheld/);
  });
});

describe("draft updaters", () => {
  test("each returns a new draft with exactly one thing changed", () => {
    expect(String(calendarCapacityMd(withTeamCoefficient(DOC, 1)))).toBe("5.7");
    // D2 on: a +0.8 and b +0.6 -> 1.2 x 7.1.
    expect(String(calendarCapacityMd(withDayEnabled(DOC, DAYS[1], true)))).toBe("8.52");
    // c on: four counted days of 1 -> 1.2 x 9.7.
    expect(String(calendarCapacityMd(withPersonEnabled(DOC, "c", true)))).toBe("11.64");
    // a at 1: 3 + 3.3 = 6.3.
    expect(String(calendarCapacityMd(withPersonCoefficient(DOC, "a", 1)))).toBe("7.56");
    // a's D1 cell at 0.5: a = 0.8 x 2.5 = 2.0, b = 3.3.
    expect(String(calendarCapacityMd(withCell(DOC, "a", DAYS[0], 0.5)))).toBe("6.36");
    expect(String(DOC.teamCoefficient)).toBe("1.2");
    expect(DOC.people[0].cells[0].availability).toBe(1);
  });

  test("sameDraft compares by content, and an edit that changes nothing is the same draft", () => {
    expect(sameDraft(DOC, withTeamCoefficient(DOC, 1.2))).toBe(true);
    expect(sameDraft(DOC, withTeamCoefficient(DOC, 1))).toBe(false);
    expect(sameDraft(DOC, JSON.parse(JSON.stringify(DOC)) as CalendarDraft)).toBe(true);
    expect(sameDraft(DOC, withCell(DOC, "a", DAYS[0], 1))).toBe(true);
    expect(sameDraft(DOC, withCell(DOC, "a", DAYS[0], 0.5))).toBe(false);
    expect(sameDraft(DOC, withDayEnabled(DOC, DAYS[1], true))).toBe(false);
    expect(sameDraft(DOC, withPersonEnabled(DOC, "c", true))).toBe(false);
    expect(sameDraft(DOC, withPersonCoefficient(DOC, "a", 0.9))).toBe(false);
    expect(sameDraft(DOC, { ...DOC, people: DOC.people.slice(1) })).toBe(false);
  });

  test("the PUT body is the whole document without the grid-only person fields", () => {
    const body = toCalendarBody(DOC);
    expect(Object.keys(body.people[0]).sort()).toEqual(["accountId", "cells", "coefficient", "enabled"]);
    expect(body.days).toHaveLength(5);
    expect(body.days[1]).toEqual({ day: DAYS[1], enabled: false });
    expect(body.people[2].enabled).toBe(false);
  });

  describe("public holidays (always non-working, locked off)", () => {
    // DOC with D4 (a counted day) a holiday: the draft says enabled, the formula must not count it anyway.
    const withHoliday = (enabled: boolean): CalendarDraft => ({
      ...DOC,
      days: DOC.days.map((d, i) => (i === 3 ? { ...d, enabled, holiday: "Constitution Day" } : d)),
    });

    test("isCounted: on and not a holiday", () => {
      expect(isCounted({ enabled: true, holiday: null })).toBe(true);
      expect(isCounted({ enabled: true })).toBe(true);
      expect(isCounted({ enabled: false, holiday: null })).toBe(false);
      expect(isCounted({ enabled: true, holiday: "New Year" })).toBe(false);
    });

    test("the capacity never counts a holiday day, even if the draft says enabled", () => {
      // D4 (a: 1, b: 0.25) dropped: a = 0.8 x (1 + 0.5 + 0.5) = 1.6; b = 1.2 x (0.5 + 1 + 1) = 3.0; 1.2 x 4.6 = 5.52.
      expect(String(calendarCapacityMd(withHoliday(true)))).toBe("5.52");
      expect(String(calendarCapacityMd(withHoliday(false)))).toBe("5.52");
    });

    test("withDayEnabled leaves a holiday day off", () => {
      const locked = withHoliday(false);
      expect(withDayEnabled(locked, DAYS[3], true).days[3].enabled).toBe(false);
      expect(sameDraft(locked, withDayEnabled(locked, DAYS[3], true))).toBe(true);
    });

    test("toDraft states a holiday day off, so a loaded calendar is never dirty against itself", () => {
      const calendar = { teamCoefficient: 1, days: withHoliday(true).days, people: DOC.people } as Parameters<typeof toDraft>[0];
      const draft = toDraft(calendar);
      expect(draft.days[3]).toMatchObject({ enabled: false, holiday: "Constitution Day" });
      expect(sameDraft(draft, toDraft(calendar))).toBe(true);
      // A calendar the server already projected is passed through untouched.
      const projected = { ...calendar, days: withHoliday(false).days };
      expect(toDraft(projected).days).toBe(projected.days);
    });

    test("sameDraft sees a holiday appearing or disappearing, and the PUT body sends enabled=false for it", () => {
      expect(sameDraft(DOC, withHoliday(false))).toBe(false);
      expect(toCalendarBody(withHoliday(false)).days[3]).toEqual({ day: DAYS[3], enabled: false });
    });
  });

  describe("a day an earlier sprint holds (claimed, locked off like a holiday)", () => {
    const claim = { sprintId: 7, sprintName: "Sprint 6" };
    // DOC with D4 (a counted day) claimed: the draft says enabled, the formula must not count it anyway.
    const withClaim = (enabled: boolean): CalendarDraft => ({
      ...DOC,
      days: DOC.days.map((d, i) => (i === 3 ? { ...d, enabled, claimedBy: claim } : d)),
    });

    test("isCounted: a claimed day is never counted", () => {
      expect(isCounted({ enabled: true, claimedBy: null })).toBe(true);
      expect(isCounted({ enabled: true, claimedBy: claim })).toBe(false);
      expect(isCounted({ enabled: false, claimedBy: claim })).toBe(false);
    });

    test("the capacity never counts a claimed day, even if the draft says enabled", () => {
      // The same figure as the holiday case: D4 dropped.
      expect(String(calendarCapacityMd(withClaim(true)))).toBe("5.52");
      expect(String(calendarCapacityMd(withClaim(false)))).toBe("5.52");
    });

    test("withDayEnabled leaves a claimed day off", () => {
      const locked = withClaim(false);
      expect(withDayEnabled(locked, DAYS[3], true).days[3].enabled).toBe(false);
      expect(sameDraft(locked, withDayEnabled(locked, DAYS[3], true))).toBe(true);
    });

    test("toDraft states a claimed day off, so a loaded calendar is never dirty against itself", () => {
      const calendar = { teamCoefficient: 1, days: withClaim(true).days, people: DOC.people } as Parameters<typeof toDraft>[0];
      const draft = toDraft(calendar);
      expect(draft.days[3]).toMatchObject({ enabled: false, claimedBy: claim });
      expect(sameDraft(draft, toDraft(calendar))).toBe(true);
      // A calendar the server already projected is passed through untouched.
      const projected = { ...calendar, days: withClaim(false).days };
      expect(toDraft(projected).days).toBe(projected.days);
    });

    test("sameDraft sees a claim appearing, changing or disappearing, and the PUT body sends enabled=false for it", () => {
      expect(sameDraft(DOC, withClaim(false))).toBe(false);
      const other = { ...withClaim(false), days: withClaim(false).days.map((d) => (d.claimedBy ? { ...d, claimedBy: { sprintId: 8, sprintName: "Sprint 6" } } : d)) };
      expect(sameDraft(withClaim(false), other)).toBe(false);
      const renamed = { ...withClaim(false), days: withClaim(false).days.map((d) => (d.claimedBy ? { ...d, claimedBy: { ...claim, sprintName: "Renamed" } } : d)) };
      expect(sameDraft(withClaim(false), renamed)).toBe(false);
      expect(toCalendarBody(withClaim(false)).days[3]).toEqual({ day: DAYS[3], enabled: false });
    });
  });

  test("clampTo holds a value to its range and to two decimals", () => {
    expect(clampTo(2.5, 0, 2)).toBe(2);
    expect(clampTo(-1, 0, 1)).toBe(0);
    expect(String(clampTo(0.456, 0, 1))).toBe("0.46");
  });
});
