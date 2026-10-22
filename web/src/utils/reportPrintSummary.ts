import type { TFunction } from "i18next";
import type { ReportFilters } from "../api/reports";
import type { DeepDiveLayers } from "./deepDiveCell";
import type { DeepDiveSelection } from "./deepDiveFilter";
import { todayIsoDate } from "./isoDate";
import {
  activePeriodChoice,
  DEFAULT_TAIL_PERCENT,
  domainStandsForTeam,
  presetRange,
  type DatePreset,
  type ReportControls,
  type ReportFilterState,
} from "./reportFilter";
import { sprintLabel } from "./sprintLabel";

/** One `label: value` pair of the print header's filter line. */
export interface PrintFilterLine {
  label: string;
  value: string;
}

function periodText(filter: ReportFilterState, filters: ReportFilters, t: TFunction): string {
  const today = todayIsoDate(filters.timeZone);
  const choice = activePeriodChoice(filter, today);
  if (choice === "sprint") {
    const sprint = filters.teams.flatMap((team) => team.sprints).find((candidate) => candidate.sprintId === filter.sprintId);
    return sprint === undefined ? `#${filter.sprintId}` : sprintLabel(sprint, filters.timeZone);
  }
  if (choice.startsWith("lastSprints:")) return t("reports.filters.lastSprints", { count: filter.lastSprints });
  if (choice === "custom") return `${filter.from ?? "…"} → ${filter.to ?? today}`;
  const preset = choice as DatePreset;
  const range = presetRange(preset, today);
  return `${t(`reports.filters.preset.${preset}`)} (${range.from} → ${range.to})`;
}

function teamText(filter: ReportFilterState, filters: ReportFilters, t: TFunction): string {
  if (filter.teamId === undefined) return t("reports.filters.allTeams");
  if (filter.teamId === 0) return t("reports.groups.unassigned");
  return filters.teams.find((candidate) => candidate.id === filter.teamId)?.name ?? `#${filter.teamId}`;
}

function peopleText(filter: ReportFilterState, filters: ReportFilters): string {
  const ids = filter.accountIds ?? (filter.accountId === undefined ? [] : [filter.accountId]);
  const members = filters.teams.flatMap((candidate) => candidate.members);
  return ids.map((id) => members.find((member) => member.accountId === id)?.displayName ?? id).join(", ");
}

/**
 * The words of the filter a report page is showing, for the print header (the bar itself is hidden on paper): the period, the team,
 * the members, then every other dimension the page OFFERS (`controls`). A toggle is printed at its EFFECTIVE value (what the bar shows:
 * the filter's, else the control's default); the pickers (domain, connection, activity type, work category) only when narrowed. A
 * `teamOrDomain` report read for a domain alone says the domain stands in for the team, and one read for a single epic (`epicScope`) names the epic the same way.
 */
export function reportFilterLines(filter: ReportFilterState, filters: ReportFilters, controls: ReportControls, t: TFunction): PrintFilterLine[] {
  const lines: PrintFilterLine[] = [];
  const add = (label: string, value: string | undefined) => {
    if (value !== undefined && value !== "") lines.push({ label, value });
  };
  const byDomain = domainStandsForTeam(controls, filter);
  const domainName =
    filter.domain === undefined ? undefined : (filters.domains.find((domain) => domain.domainKey === filter.domain)?.domainName ?? filter.domain);
  if (!controls.noPeriod) add(t("reports.filters.period"), periodText(filter, filters, t));
  if (!controls.peopleOnly && !byDomain) add(t("reports.filters.team"), teamText(filter, filters, t));
  if (byDomain && filter.domain === undefined) add(t("reports.print.epic"), t("reports.print.standsForTeam", { name: filter.epicId }));
  if (controls.peopleOnly) add(t("reports.print.people"), peopleText(filter, filters));
  else if (!controls.noMember && filter.teamId !== undefined && filter.teamId > 0) add(t("reports.filters.member"), peopleText(filter, filters));
  if (controls.domain && domainName !== undefined) {
    add(t("reports.filters.domain"), byDomain ? t("reports.print.standsForTeam", { name: domainName }) : domainName);
  }
  if (controls.connection && filters.connections.length > 1 && filter.connectionId !== undefined) {
    add(t("reports.filters.connection"), filters.connections.find((connection) => connection.id === filter.connectionId)?.name ?? `#${filter.connectionId}`);
  }
  if (controls.activityType) add(t("reports.filters.activityType"), filter.activityType);
  if (controls.workCategory) add(t("reports.filters.workCategory"), filter.workCategory);
  if (controls.domainView !== undefined) {
    const view = filter.domainView ?? controls.domainView;
    add(t("reports.filters.domainView"), t(view === "TASK" ? "reports.filters.deliveredIn" : "reports.filters.earnedIn"));
  }
  if (controls.bucket) add(t("reports.filters.bucket"), t(`reports.filters.bucketOption.${filter.bucket ?? "WEEK"}`));
  if (controls.wipBy) add(t("reports.filters.wipBy"), t(`reports.filters.wipByOption.${filter.by ?? "STAGE"}`));
  if (controls.itemKind) add(t("reports.filters.itemKind"), t(`reports.filters.itemKindOption.${filter.itemKind ?? "TASK"}`));
  return lines;
}

/** The distribution reports' long-tail share: its control sits outside the filter bar and is hidden on paper, yet the figures depend on it. */
export function tailPercentLine(filter: ReportFilterState, t: TFunction): PrintFilterLine {
  return { label: t("reports.distribution.tail.label"), value: `${filter.tailPercent ?? DEFAULT_TAIL_PERCENT}%` };
}

/** The Deep dive matrix's switched-on layers (a page-local toggle that changes what the grid shows). */
export function deepDiveLayerLine(layers: DeepDiveLayers, t: TFunction): PrintFilterLine {
  const shown = (["pv", "aev", "cost"] as const).filter((key) => layers[key]).map((key) => t(`reports.deepDive.matrix.layer.${key}`));
  return { label: t("reports.print.deepDive.layers"), value: shown.join(", ") };
}

/** The Deep dive's selection in words: its domain, the sprints or epics it names (by `labels` where known), and the clipped range. */
export function deepDiveFilterLines(
  selection: DeepDiveSelection,
  labels: Readonly<Record<string, string>>,
  filters: ReportFilters | undefined,
  t: TFunction,
): PrintFilterLine[] {
  const lines: PrintFilterLine[] = [];
  const named = (keys: ReadonlyArray<string | number>) => keys.map((key) => labels[String(key)] ?? String(key)).join(", ");
  if (selection.domain !== undefined) {
    const domainName = filters?.domains.find((domain) => domain.domainKey === selection.domain)?.domainName;
    lines.push({ label: t("reports.filters.domain"), value: domainName ?? selection.domain });
  }
  if (selection.sprintIds !== undefined && selection.sprintIds.length > 0) {
    lines.push({ label: t("reports.print.deepDive.sprints"), value: named(selection.sprintIds) });
  }
  if (selection.epicIds !== undefined && selection.epicIds.length > 0) {
    lines.push({ label: t("reports.print.deepDive.epics"), value: named(selection.epicIds) });
  }
  if (selection.from !== undefined || selection.to !== undefined) {
    lines.push({ label: t("reports.filters.range"), value: `${selection.from ?? "…"} → ${selection.to ?? "…"}` });
  }
  return lines;
}
