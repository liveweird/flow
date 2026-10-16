import type { TFunction } from "i18next";
import type { ReportDrillItem } from "../api/reportDrill";
import type { ColumnDef } from "../components/ColumnTable";
import { formatDate } from "./formatDate";
import { isValidIsoDate } from "./isoDate";
import { formatMd } from "./reportFormat";

/**
 * The drill's whole state is ONE URL param: which bar of the chart the open drawer lists. It is page-local like throughput's
 * `view` (no part of the report filter, never sent with a report request, never in a query key) and `reportHref` drops it on a
 * tab hop, because a bar means nothing on another report.
 */
export const DRILL_PARAM = "drill";

/** What a drill is about. `segmentAccountId` narrows a stacked bar to one person's share of it. */
export type DrillTarget = { kind: "bucket"; bucketStart: string; segmentAccountId?: string };

/** The unit of a drill list's figure column (each report's own per-item value). */
export type DrillUnit = "md";

/** Which instant the date column shows (`anchorAt`): when the item was done. */
export type DrillAnchor = "done";

const SEGMENT_SEPARATOR = ";s=";

/**
 * The URL spelling of a target: `bucket:2026-03-02`, plus `;s=<accountId>` for one person's segment. An account id is the
 * REST of the string after the separator (Jira ids carry colons), so it never needs escaping beyond the URL's own.
 */
export function serializeDrill(target: DrillTarget): string {
  const identity = `bucket:${target.bucketStart}`;
  return target.segmentAccountId ? `${identity}${SEGMENT_SEPARATOR}${target.segmentAccountId}` : identity;
}

/** The target a `drill` param spells, or `null` for anything this module did not write (forgiving, like the report filter parser). */
export function parseDrill(raw: string | null): DrillTarget | null {
  if (!raw) return null;
  const at = raw.indexOf(SEGMENT_SEPARATOR);
  const identity = at < 0 ? raw : raw.slice(0, at);
  const segmentAccountId = at < 0 ? undefined : raw.slice(at + SEGMENT_SEPARATOR.length);
  if (segmentAccountId === "") return null;
  const [kind, bucketStart, ...rest] = identity.split(":");
  if (kind !== "bucket" || rest.length > 0 || !bucketStart || !isValidIsoDate(bucketStart)) return null;
  return segmentAccountId === undefined ? { kind, bucketStart } : { kind, bucketStart, segmentAccountId };
}

/** `target` with its segment set to `accountId` (or removed). */
export function withSegment(target: DrillTarget, accountId: string | undefined): DrillTarget {
  return accountId === undefined ? { kind: target.kind, bucketStart: target.bucketStart } : { ...target, segmentAccountId: accountId };
}

const FIGURE_FORMATTERS: Record<DrillUnit, (value: number) => string> = { md: formatMd };

/** The drawer's columns: issue key, summary, assignee, the anchor date (in the configured zone) and the bar's own figure (a dash = unestimated). */
export function drillItemColumns(t: TFunction, unit: DrillUnit, anchor: DrillAnchor, timeZone: string): ColumnDef<ReportDrillItem>[] {
  const format = FIGURE_FORMATTERS[unit];
  return [
    { key: "issueKey", header: t("reports.drill.columns.issueKey"), render: (item) => item.issueKey },
    { key: "summary", header: t("reports.drill.columns.summary"), render: (item) => item.summary ?? "—" },
    { key: "assignee", header: t("reports.drill.columns.assignee"), render: (item) => item.assignee ?? t("reports.groups.unassigned") },
    { key: "anchor", header: t(`reports.drill.columns.${anchor}`), render: (item) => formatDate(item.anchorAt, "—", timeZone) },
    {
      key: "figure",
      header: t(`reports.drill.columns.figure.${unit}`),
      align: "right",
      render: (item) => (item.figure === null ? "—" : format(item.figure)),
    },
  ];
}
