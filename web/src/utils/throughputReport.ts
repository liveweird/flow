import type { TFunction } from "i18next";
import type { ReportFilters, ThroughputBucketRow, ThroughputSprint } from "../api/reports";
import type { ColumnDef } from "../components/ColumnTable";
import type { Bucket } from "./reportFilter";
import { formatMd, roundScaled } from "./reportFormat";
import { labelledSprints } from "./reportSprints";

/** A bucket's label: a week by its Monday (`2026-09-28`), a month by `2026-09`. */
export function bucketLabel(bucketStart: string, bucket: Bucket): string {
  return bucket === "MONTH" ? bucketStart.slice(0, 7) : bucketStart;
}

/**
 * What one picked person delivered in a bucket or a sprint (the report's `byUser` entry). The delivered figures are by ASSIGNEE; the
 * actual cost is the time this person LOGGED (worklog author) on the slot's items.
 */
interface ThroughputUserShare {
  accountId: string;
  label: string;
  deliveredMd: number;
  deliveredItems: number;
  actualCostMd: number;
}

/** One time slot as the report states it — a week/month or a sprint — before the running totals are added. */
export interface ThroughputSlot {
  /** The slot's identity in the drill: a bucket's first day, or `<teamId>:<sprintId>`. */
  id: string;
  label: string;
  deliveredMd: number;
  deliveredItems: number;
  /** The MD logged inside the slot on the items not done before the slot began (delivered in it or still open; at the user level the picked authors' own time). */
  actualCostMd: number;
  /** The picked people's shares in request order; `[]` below the user level. */
  byUser: readonly ThroughputUserShare[];
}

/** The slot's own figures and the running totals up to and including it (actual cost accumulates like the delivered scope). */
export interface ThroughputFigures {
  deliveredMd: number;
  deliveredItems: number;
  actualCostMd: number;
  cumulativeMd: number;
  cumulativeItems: number;
  cumulativeCostMd: number;
}

/**
 * A picked person of the people view. `position` (1-based, bottom of the stack first) is their number: the categorical
 * palette repeats from the eighth person on, so the number — `personLabel` — is what tells them apart. `key` is their chart
 * data key for the delivered MD and `costKey` for their actual cost (account ids are not safe data keys).
 */
export interface ThroughputPerson {
  accountId: string;
  label: string;
  position: number;
  key: string;
  costKey: string;
}

interface ThroughputChartRow extends ThroughputFigures {
  id: string;
  label: string;
  /** Each person's figures in this slot, index-aligned with `ThroughputSeries.people` (a person the slot omits counts 0). */
  shares: ThroughputFigures[];
}

/** The slots of one view in time order, with the people who appear in them (empty below the user level). */
export interface ThroughputSeries {
  rows: ThroughputChartRow[];
  people: ThroughputPerson[];
}

/** Sums of MD carry binary noise (0.1 + 0.2); six decimals cleans it without ever touching a real figure. */
const sum = (a: number, b: number) => roundScaled(a + b, 6) / 1e6;

/** The people of a set of slots in stack order: the union of every slot's `byUser`, first seen first. */
function slotPeople(slots: readonly ThroughputSlot[]): ThroughputPerson[] {
  const people: ThroughputPerson[] = [];
  for (const slot of slots) {
    for (const share of slot.byUser) {
      if (people.some((person) => person.accountId === share.accountId)) continue;
      const index = people.length;
      people.push({ accountId: share.accountId, label: share.label, position: index + 1, key: `p${index}`, costKey: `a${index}` });
    }
  }
  return people;
}

/**
 * The running totals over time-ordered slots — the cumulative (CFD-style) view's numbers. Each slot keeps its own
 * figures beside the total so far; when people are picked the same is kept per person (their running totals add up
 * to the slot's, because the report's `byUser` splits the slot exactly). Empty input gives empty output.
 */
export function throughputSeries(slots: readonly ThroughputSlot[]): ThroughputSeries {
  const people = slotPeople(slots);
  let md = 0;
  let items = 0;
  let cost = 0;
  const personMd = people.map(() => 0);
  const personItems = people.map(() => 0);
  const personCost = people.map(() => 0);
  const rows = slots.map((slot) => {
    md = sum(md, slot.deliveredMd);
    items += slot.deliveredItems;
    cost = sum(cost, slot.actualCostMd);
    const shares = people.map((person, index): ThroughputFigures => {
      const share = slot.byUser.find((candidate) => candidate.accountId === person.accountId);
      personMd[index] = sum(personMd[index], share?.deliveredMd ?? 0);
      personItems[index] += share?.deliveredItems ?? 0;
      personCost[index] = sum(personCost[index], share?.actualCostMd ?? 0);
      return {
        deliveredMd: share?.deliveredMd ?? 0,
        deliveredItems: share?.deliveredItems ?? 0,
        actualCostMd: share?.actualCostMd ?? 0,
        cumulativeMd: personMd[index],
        cumulativeItems: personItems[index],
        cumulativeCostMd: personCost[index],
      };
    });
    return {
      id: slot.id,
      label: slot.label,
      deliveredMd: slot.deliveredMd,
      deliveredItems: slot.deliveredItems,
      actualCostMd: slot.actualCostMd,
      cumulativeMd: md,
      cumulativeItems: items,
      cumulativeCostMd: cost,
      shares,
    };
  });
  return { rows, people };
}

/** The period view's series: one slot per week/month, labelled by `bucketLabel`. */
export function throughputBuckets(buckets: readonly ThroughputBucketRow[], bucket: Bucket): ThroughputSeries {
  return throughputSeries(buckets.map((row) => ({ ...row, id: row.bucketStart, label: bucketLabel(row.bucketStart, bucket) })));
}

/** The sprint view's series: one slot per sprint in completion order, labelled like the other sprint charts. */
export function throughputSprints(sprints: readonly ThroughputSprint[], filters: ReportFilters): ThroughputSeries {
  return throughputSeries(labelledSprints(sprints, filters).map(({ sprint, label }) => ({ ...sprint, id: `${sprint.teamId}:${sprint.sprintId}`, label })));
}

/**
 * Whether the period chart has nothing to draw: nothing delivered AND nothing logged. Time can be logged on items still open, so a
 * period that delivered nothing may still have a cost to show.
 */
export function throughputNothingToShow(totals: { items: number; costMd: number } | undefined): boolean {
  return totals !== undefined && totals.items === 0 && totals.costMd === 0;
}

/** The period view's totals — the text alternative to the chart, and the "nothing delivered" test. */
export function throughputTotals(buckets: readonly ThroughputBucketRow[]): { md: number; items: number; costMd: number } {
  return buckets.reduce(
    (total, row) => ({ md: sum(total.md, row.deliveredMd), items: total.items + row.deliveredItems, costMd: sum(total.costMd, row.actualCostMd) }),
    { md: 0, items: 0, costMd: 0 },
  );
}

/** The plotted row's key (and the chart series' name) of the whole team's actual cost; a person's is their `costKey`. */
export const COST_SERIES = "ac";

/**
 * One row of the plotted data: the slot's `label`, its delivered MD under `md` (the single series of a whole team), its actual cost
 * under `ac` and its items under `items` (for the tooltip), plus each person's delivered MD under their `key` and their own actual
 * cost under their `costKey` — the per-slot figure, or the running total.
 */
export type ThroughputPlotRow = { id: string; label: string; md: number; ac: number; items: number } & Record<string, number | string>;

/** The chart's rows for the per-period bars or, with `cumulative`, the running-total areas. */
export function throughputPlot(series: ThroughputSeries, cumulative: boolean): ThroughputPlotRow[] {
  const md = (figures: ThroughputFigures) => (cumulative ? figures.cumulativeMd : figures.deliveredMd);
  const cost = (figures: ThroughputFigures) => (cumulative ? figures.cumulativeCostMd : figures.actualCostMd);
  return series.rows.map((row) => {
    const plotted: ThroughputPlotRow = {
      id: row.id,
      label: row.label,
      md: md(row),
      ac: cost(row),
      items: cumulative ? row.cumulativeItems : row.deliveredItems,
    };
    series.people.forEach((person, index) => {
      plotted[person.key] = md(row.shares[index]);
      plotted[person.costKey] = cost(row.shares[index]);
    });
    return plotted;
  });
}

/** A flat row of a per-person table: one person's share of one slot. */
export interface ThroughputShareRow {
  key: string;
  /** The slot's `id` (what its drill is keyed by). */
  slotId: string;
  label: string;
  person: ThroughputPerson;
  figures: ThroughputFigures;
}

/** The per-person-per-slot rows, slots in time order, people numbered as in the chart. */
export function throughputShareRows(series: ThroughputSeries): ThroughputShareRow[] {
  return series.rows.flatMap((row, slot) =>
    series.people.map((person, index) => ({ key: `${slot}:${person.accountId}`, slotId: row.id, label: row.label, person, figures: row.shares[index] })),
  );
}

/** The delivered MD and item columns every throughput table shares; `pick` finds the figures in a row. */
export function throughputFigureColumns<T>(
  t: TFunction,
  pick: (row: T) => Pick<ThroughputFigures, "deliveredMd" | "deliveredItems">,
): ColumnDef<T>[] {
  return [
    { key: "md", header: t("reports.throughput.column.deliveredMd"), render: (row) => formatMd(pick(row).deliveredMd) },
    { key: "items", header: t("reports.throughput.column.deliveredItems"), render: (row) => pick(row).deliveredItems },
  ];
}

/** The actual-cost column every table beside a chart carries (MD only: there is no cost in items). */
export function throughputCostColumn<T>(t: TFunction, pick: (row: T) => Pick<ThroughputFigures, "actualCostMd">): ColumnDef<T> {
  return { key: "actualCostMd", header: t("reports.throughput.column.actualCostMd"), render: (row) => formatMd(pick(row).actualCostMd) };
}

/** The running-total columns of the cumulative view — the numbers the areas and the cost line are drawn from, so the chart's every number is in a table. */
export function throughputCumulativeColumns<T>(t: TFunction, pick: (row: T) => ThroughputFigures): ColumnDef<T>[] {
  return [
    { key: "cumulativeMd", header: t("reports.throughput.column.cumulativeMd"), render: (row) => formatMd(pick(row).cumulativeMd) },
    { key: "cumulativeItems", header: t("reports.throughput.column.cumulativeItems"), render: (row) => pick(row).cumulativeItems },
    { key: "cumulativeCostMd", header: t("reports.throughput.column.cumulativeCostMd"), render: (row) => formatMd(pick(row).cumulativeCostMd) },
  ];
}
