import { skipToken, useQuery } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { getCostsItems } from "../api/reportDrill";
import type { CostsSprint } from "../api/reports";
import { breakdownHeading, keyLabel } from "../utils/costBreakdown";
import { costsChartMd, costsSprintConnection, costsSprintName } from "../utils/costsSprints";
import { epicSegmentKeyName } from "../utils/epicSegments";
import { epicLabeller } from "../utils/sprintEpics";
import { serializeDrill, type CostSprintDrillTarget, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import ReportDrillDrawer from "./ReportDrillDrawer";

/**
 * The Costs drill drawer: the tasks behind one slice of an activity-type or work-category donut, or behind a sprint's actual-cost bar (whole,
 * or one epic segment of it), fetched from `GET /reports/costs/items` with the SAME filter the report was requested with (a sprint bar with
 * the period narrowed to that sprint), so the list is the bar's own worklogs. The title of a slice is the slice ("Development") over the breakdown as a dimmed subtitle ("Activity type"); the folded "Other"
 * slice says how many types it holds and lists them under the title. A sprint bar is "Actual cost" (or "Actual cost — <epic>") over the sprint, and the
 * drawer's recomputed total is held against the chart's `acMd` (`sprints`) so a refreshed dataset is announced rather than silently different. Tasks and epics mix (an epic is listed when work was logged
 * on it), so the Kind column is always shown; the figure is the man-days logged in the slice.
 */
export default function CostBreakdownDrillDrawer({
  opened,
  target,
  filter,
  timeZone,
  sprints,
  onClose,
}: {
  /** Whether the drill is open; `target` is the hook's `shown` (the last slice, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  filter: ReportFilterState;
  timeZone: string;
  /** The shown report's sprints: the sprint bars' names, epic segments and the figures their drills are held against. */
  sprints?: readonly CostsSprint[];
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const open = target?.kind === "cost" || target?.kind === "cost-sprint" ? target : null;
  const connectionId = open?.kind === "cost-sprint" ? costsSprintConnection(sprints, open) : undefined;
  const query = useQuery({
    queryKey: ["reports", "costs", "items", reportQuery(filter), open ? serializeDrill(open) : null, connectionId ?? null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => getCostsItems(filter, open, connectionId) : skipToken,
  });
  const sprintBar = open?.kind === "cost-sprint" ? open : null;
  const heading = open?.kind === "cost" ? breakdownHeading(t, open) : sprintBar ? sprintHeading(t, sprintBar, sprints) : undefined;
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={heading?.title ?? ""}
      subtitle={heading?.subtitle}
      caption={open?.kind === "cost" && open.keys.length > 1 ? t("reports.costs.breakdown.includes", { keys: open.keys.map((key) => keyLabel(t, key)).join(", ") }) : undefined}
      query={query}
      unit="loggedMd"
      anchor="logged"
      timeZone={timeZone}
      chartMd={sprintBar ? costsChartMd(sprints, sprintBar) : undefined}
      showKind
    />
  );
}

/** The heading of a sprint bar: "Actual cost" (or "Actual cost — <epic segment>") over the sprint's name. */
function sprintHeading(t: TFunction, target: CostSprintDrillTarget, sprints: readonly CostsSprint[] | undefined): { title: string; subtitle: string } {
  const bucket = t("reports.drill.actualCost");
  const title =
    target.epicSegment === undefined
      ? bucket
      : t("reports.drill.epicTitle", { bucket, epic: epicSegmentKeyName(t, target.epicSegment, epicLabeller(sprints)(target.epicSegment)) });
  return { title, subtitle: costsSprintName(sprints, target) ?? t("reports.drill.sprintFallback", { id: target.sprintId }) };
}
