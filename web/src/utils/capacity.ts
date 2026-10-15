import type { SprintCalendar, SprintCalendarBody } from "../api/capacity";
import { roundScaled } from "./reportFormat";

/**
 * The editable shape of one sprint's capacity calendar (A32): the response's own document fields
 * (the people keep their display name and `member` flag for the grid, `toCalendarBody` strips them).
 * Callers own it; everything here is a pure function of it.
 */
export interface CalendarDraft {
  teamCoefficient: number;
  days: SprintCalendar["days"];
  /** `coefficient` is null for a caller who cannot edit the team: the server withholds per-person coefficients from them. */
  people: ReadonlyArray<Omit<SprintCalendar["people"][number], "coefficient"> & { coefficient: number | null }>;
}

/** The slice of a person the formula reads (the grid's person view and the PUT body's person both fit). */
interface CountedPerson {
  enabled: boolean;
  coefficient: number | null;
  cells: ReadonlyArray<{ day: string; availability: number }>;
}

export const COEFFICIENT_MAX = 2;
export const AVAILABILITY_MAX = 1;
export const TEAM_DEFAULT_MD_MAX = 999_999.99;

/** Two-decimal values as whole hundredths: the server sums exact decimals, so the sum is done in integers too. */
const hundredths = (value: number) => Math.round(value * 100);

/**
 * The server kernel's formula (`CapacityKernels.kt` `calendarCapacityMd`): `teamCoefficient × Σ over
 * counted people of (coefficient × Σ over counted days of the person's cell)`, rounded half-up to two
 * decimals once, at the end ([roundScaled] — the one rounding rule, which returns hundredths). A counted day with no cell for a
 * person counts 0, exactly like the server. Every input has at most two decimals, so the integer sum is exact.
 * Null when a counted person's coefficient is withheld (a read-only caller): the figure is then the server's `capacityMd`.
 */
export function calendarCapacityMd(doc: { teamCoefficient: number; days: ReadonlyArray<{ day: string; enabled: boolean }>; people: ReadonlyArray<CountedPerson> }): number | null {
  const counted = new Set(doc.days.filter((d) => d.enabled).map((d) => d.day));
  let perPerson = 0;
  for (const person of doc.people) {
    if (!person.enabled) continue;
    if (person.coefficient === null) return null;
    let cells = 0;
    for (const cell of person.cells) if (counted.has(cell.day)) cells += hundredths(cell.availability);
    perPerson += hundredths(person.coefficient) * cells;
  }
  return roundScaled((hundredths(doc.teamCoefficient) * perPerson) / 1_000_000) / 100;
}

/** The footer figure of one day: the man-days the counted people give that day, before any coefficient. */
export function dayCapacityMd(people: ReadonlyArray<Pick<CountedPerson, "enabled" | "cells">>, day: string): number {
  let total = 0;
  for (const person of people) {
    if (!person.enabled) continue;
    const cell = person.cells.find((c) => c.day === day);
    if (cell) total += hundredths(cell.availability);
  }
  return total / 100;
}

/** A typed-in number held to a field's range and to two decimals — the server rejects anything else. */
export function clampTo(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, hundredths(value) / 100));
}

export function toDraft(calendar: SprintCalendar): CalendarDraft {
  return { teamCoefficient: calendar.teamCoefficient, days: calendar.days, people: calendar.people };
}

/**
 * The PUT body: the whole document (a full replace) without the grid-only person fields. Only an editor
 * saves, and an editor always has the coefficients — a withheld one fails loudly rather than being sent as a guess.
 */
export function toCalendarBody(draft: CalendarDraft): SprintCalendarBody {
  return {
    teamCoefficient: draft.teamCoefficient,
    days: draft.days.map(({ day, enabled }) => ({ day, enabled })),
    people: draft.people.map(({ accountId, enabled, coefficient, cells }) => ({
      accountId,
      enabled,
      coefficient: coefficient ?? failMissingCoefficient(accountId),
      cells: cells.map(({ day, availability }) => ({ day, availability })),
    })),
  };
}

function failMissingCoefficient(accountId: string): never {
  throw new Error(`coefficient of ${accountId} is withheld`);
}

export function sameDraft(a: CalendarDraft, b: CalendarDraft): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

export const withTeamCoefficient = (draft: CalendarDraft, teamCoefficient: number): CalendarDraft => ({ ...draft, teamCoefficient });

export const withDayEnabled = (draft: CalendarDraft, day: string, enabled: boolean): CalendarDraft => ({
  ...draft,
  days: draft.days.map((d) => (d.day === day ? { ...d, enabled } : d)),
});

function withPerson(draft: CalendarDraft, accountId: string, change: (person: CalendarDraft["people"][number]) => CalendarDraft["people"][number]): CalendarDraft {
  return { ...draft, people: draft.people.map((p) => (p.accountId === accountId ? change(p) : p)) };
}

export const withPersonEnabled = (draft: CalendarDraft, accountId: string, enabled: boolean) =>
  withPerson(draft, accountId, (p) => ({ ...p, enabled }));

export const withPersonCoefficient = (draft: CalendarDraft, accountId: string, coefficient: number) =>
  withPerson(draft, accountId, (p) => ({ ...p, coefficient }));

export const withCell = (draft: CalendarDraft, accountId: string, day: string, availability: number) =>
  withPerson(draft, accountId, (p) => ({ ...p, cells: p.cells.map((c) => (c.day === day ? { ...c, availability } : c)) }));
