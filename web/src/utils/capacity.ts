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

/** The earlier sprint of the same team whose window already holds a day (A42); the day is locked off here. */
type Claim = { sprintId: number; sprintName: string };

/**
 * A day of the calendar as the formula reads it: a public holiday (`holiday` set) and a day an earlier sprint
 * already holds (`claimedBy` set) are never counted, whatever `enabled` says.
 */
type CountedDay = { day: string; enabled: boolean; holiday?: string | null; claimedBy?: Claim | null };

/** The slice of a person the formula reads (the grid's person view and the PUT body's person both fit). */
interface CountedPerson {
  enabled: boolean;
  coefficient: number | null;
  cells: ReadonlyArray<{ day: string; availability: number }>;
}

export const COEFFICIENT_MAX = 2;
export const AVAILABILITY_MAX = 1;
export const TEAM_DEFAULT_MD_MAX = 999_999.99;
/** A Polish keyboard types "," — a number field reads it as the dot (react-number-format converts it). */
export const DECIMAL_SEPARATORS = [",", "."];

/** Two-decimal values as whole hundredths: the server sums exact decimals, so the sum is done in integers too. */
const hundredths = (value: number) => Math.round(value * 100);

/**
 * The server kernel's formula (`CapacityKernels.kt` `calendarCapacityMd`): `teamCoefficient × Σ over
 * counted people of (coefficient × Σ over counted days of the person's cell)`, rounded half-up to two
 * decimals once, at the end ([roundScaled] — the one rounding rule, which returns hundredths). A counted day with no cell for a
 * person counts 0, exactly like the server. Every input has at most two decimals, so the integer sum is exact.
 * A public holiday and a day an earlier sprint holds are locked off (the server projects them off whatever was stored), so they never count here either.
 * Null when a counted person's coefficient is withheld (a read-only caller): the figure is then the server's `capacityMd`.
 */
export function calendarCapacityMd(doc: { teamCoefficient: number; days: ReadonlyArray<CountedDay>; people: ReadonlyArray<CountedPerson> }): number | null {
  const counted = new Set(doc.days.filter(isCounted).map((d) => d.day));
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

/**
 * The footer figures: for every day, the man-days the counted people give that day, before any
 * coefficient — one pass over the cells (the grid recomputes it per edit, so no per-day `find`).
 */
export function dayCapacityTotals(people: ReadonlyArray<Pick<CountedPerson, "enabled" | "cells">>): Map<string, number> {
  const totals = new Map<string, number>();
  for (const person of people) {
    if (!person.enabled) continue;
    for (const cell of person.cells) totals.set(cell.day, (totals.get(cell.day) ?? 0) + hundredths(cell.availability));
  }
  for (const [day, total] of totals) totals.set(day, total / 100);
  return totals;
}

/**
 * What the field holds as a figure: a number, `null` for an explicitly cleared (blank) field, and
 * `undefined` for anything else — text that is not a finite number, or a number outside 0..max. An
 * invalid field is never sent as null (that would clear the stored default by accident).
 */
export function parseFigure(value: string | number): number | null | undefined {
  if (value === "") return null;
  const figure = typeof value === "number" ? value : Number(value);
  return Number.isFinite(figure) && figure >= 0 && figure <= TEAM_DEFAULT_MD_MAX ? figure : undefined;
}

/** A typed-in number held to a field's range and to two decimals — the server rejects anything else. */
export function clampTo(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, hundredths(value) / 100));
}

/** A day that cannot be switched on: a public holiday or a day an earlier sprint of the team already counts. */
const isLocked = (day: { holiday?: string | null; claimedBy?: Claim | null }): boolean => !!day.holiday || !!day.claimedBy;

/** Whether a day is counted in the capacity: switched on, not a public holiday (always non-working) and not held by an earlier sprint. */
export const isCounted = (day: { enabled: boolean; holiday?: string | null; claimedBy?: Claim | null }): boolean => day.enabled && !isLocked(day);

/**
 * The draft a loaded calendar starts from. The server already answers a holiday or claimed day `enabled: false`;
 * the draft states it again so a document that somehow said otherwise still compares equal to itself
 * (the base is built the same way) and never reads as an unsaved edit.
 */
export function toDraft(calendar: SprintCalendar): CalendarDraft {
  const days = calendar.days.some((d) => isLocked(d) && d.enabled) ? calendar.days.map((d) => (isLocked(d) ? { ...d, enabled: false } : d)) : calendar.days;
  return { teamCoefficient: calendar.teamCoefficient, days, people: calendar.people };
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

function sameList<T>(a: ReadonlyArray<T>, b: ReadonlyArray<T>, same: (x: T, y: T) => boolean): boolean {
  return a === b || (a.length === b.length && a.every((x, i) => x === b[i] || same(x, b[i])));
}

/**
 * Content equality that short-cuts on identity: an edit replaces only the person (and the cell)
 * it touched, so comparing a draft with the document it was seeded from costs one pass over the
 * people plus the changed rows — never a serialisation of the whole document.
 */
export function sameDraft(a: CalendarDraft, b: CalendarDraft): boolean {
  return (
    a === b ||
    (a.teamCoefficient === b.teamCoefficient &&
      sameList(
        a.days,
        b.days,
        (x, y) =>
          x.day === y.day &&
          x.enabled === y.enabled &&
          (x.holiday ?? null) === (y.holiday ?? null) &&
          (x.claimedBy?.sprintId ?? null) === (y.claimedBy?.sprintId ?? null) &&
          (x.claimedBy?.sprintName ?? null) === (y.claimedBy?.sprintName ?? null),
      ) &&
      sameList(
        a.people,
        b.people,
        (x, y) =>
          x.accountId === y.accountId &&
          x.displayName === y.displayName &&
          x.member === y.member &&
          x.enabled === y.enabled &&
          x.coefficient === y.coefficient &&
          sameList(x.cells, y.cells, (c, d) => c.day === d.day && c.availability === d.availability),
      ))
  );
}

export const withTeamCoefficient = (draft: CalendarDraft, teamCoefficient: number): CalendarDraft => ({ ...draft, teamCoefficient });

/** Turns a day on or off; a public holiday and a day an earlier sprint holds are locked off and stay as they are. */
export const withDayEnabled = (draft: CalendarDraft, day: string, enabled: boolean): CalendarDraft => ({
  ...draft,
  days: draft.days.map((d) => (d.day === day && !isLocked(d) ? { ...d, enabled } : d)),
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
