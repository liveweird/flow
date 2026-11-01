import { useEffect, useRef } from "react";
import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getHomeItems, type HomeOverview } from "../api/home";
import { getSprintScopeItems, getThroughputItems } from "../api/reportDrill";
import { getReportFilters } from "../api/reports";
import { drillDrawn, HOME_VIEWS, isCodeView, sprintBucketItems } from "../utils/homeOverview";
import { serializeDrill, type DrillTarget, type HomeDrillTarget, type WeekDrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import ReportDrillDrawer from "./ReportDrillDrawer";
import SprintDrillDrawer from "./SprintDrillDrawer";

interface Props {
  overview: HomeOverview;
  /** Whether the drill is open; `target` is the hook's `shown` (the last target, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  onClose: () => void;
}

/** Home has no filter bar: a sprint drill is requested with the sprint alone (its team travels with it). */
const NO_FILTER: ReportFilterState = {};

/** The items behind one team's figure (`home/items`), compared with the overview's own count of the figure. */
function ViewDrawer({ overview, opened, target, onClose }: Pick<Props, "overview" | "opened" | "onClose"> & { target: HomeDrillTarget | null }) {
  const { t } = useTranslation();
  const team = target ? overview.teams.find((candidate) => candidate.teamId === target.teamId) : undefined;
  // A code view of a team the overview drew no code figures for is a stale link: no request (the page drops it from the URL).
  const open = target && team && drillDrawn(target, overview) ? target : null;
  const query = useQuery({
    queryKey: ["home", "items", open ? serializeDrill(open) : null],
    // Closed means no request; the last answer stays cached.
    queryFn: opened && open ? () => getHomeItems(open.view, open.teamId) : skipToken,
  });
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={open ? t(`home.drill.view.${open.view}`) : ""}
      subtitle={team?.name}
      query={query}
      unit={open ? HOME_VIEWS[open.view].unit : "estimateMd"}
      anchor={open ? HOME_VIEWS[open.view].anchor : "done"}
      timeZone={overview.timeZone}
      chartCount={open && team ? HOME_VIEWS[open.view].count(team) : undefined}
      codeSources={open && isCodeView(open.view) ? overview.code.sources : undefined}
    />
  );
}

/** The tasks a team delivered in one week: the Throughput report's own list for that week, requested for the team and the overview's window. */
function WeekDrawer({ overview, opened, target, onClose }: Pick<Props, "overview" | "opened" | "onClose"> & { target: WeekDrillTarget | null }) {
  const { t } = useTranslation();
  const team = target ? overview.teams.find((candidate) => candidate.teamId === target.teamId) : undefined;
  const week = target ? team?.throughput.weeks.find((candidate) => candidate.bucketStart === target.bucketStart) : undefined;
  const open = target && week ? target : null;
  const filter: ReportFilterState = { teamId: open?.teamId, from: overview.window.from, to: overview.window.to, bucket: "WEEK" };
  const query = useQuery({
    queryKey: ["reports", "throughput", "items", reportQuery(filter), open ? serializeDrill(open) : null],
    queryFn: opened && open ? () => getThroughputItems(filter, { kind: "bucket", bucketStart: open.bucketStart }) : skipToken,
  });
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={t("reports.drill.bucket.DELIVERED")}
      subtitle={open && team ? `${team.name} · ${t("home.throughput.weekOf", { date: open.bucketStart })}` : undefined}
      query={query}
      unit="md"
      anchor="done"
      timeZone={overview.timeZone}
      chartCount={week?.deliveredItems}
    />
  );
}

/**
 * The drill drawer of Home. Three kinds of target share the page's `drill` param: a sprint bar (the shared `SprintDrillDrawer`, the
 * Sprint consistency items), a delivery week (the Throughput items for the team and window) and one team's figure (`home/items`).
 * A target the overview did not draw — a team it no longer lists, a sprint that is no longer a team's last, a week outside the
 * window, or a kind Home has no bar for — opens nothing and is dropped from the URL, never a drawer that answers a 400.
 */
export default function HomeDrillDrawer({ overview, opened, target, onClose }: Props) {
  // The issue keys of every list link to Jira from the reference data's connection sites (`JiraKeyLink` reads that cache entry
  // passively and never fetches): Home itself does not need it, so it is asked for when the first list opens.
  useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000, enabled: opened });
  const stale = opened && target !== null && !drillDrawn(target, overview);
  // Close a stale target ONCE: `onClose` is `navigate(-1)` when this page opened the drill, and a second call before the location
  // has changed would step back twice. The ref remembers which target it already closed (and forgets it when nothing is stale).
  const closed = useRef<string | null>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });
  const staleKey = stale && target !== null ? serializeDrill(target) : null;
  useEffect(() => {
    if (staleKey === null) {
      closed.current = null;
    } else if (closed.current !== staleKey) {
      closed.current = staleKey;
      onCloseRef.current();
    }
  }, [staleKey]);
  const sprintLabel = ({ teamId, sprintId }: { teamId: number; sprintId: number }) => {
    const team = overview.teams.find((candidate) => candidate.teamId === teamId);
    return team?.lastSprint?.sprintId === sprintId ? `${team.name} · ${team.lastSprint.name}` : undefined;
  };
  const sprintTarget = target?.kind === "sprint" && drillDrawn(target, overview) ? target : null;
  return (
    <>
      <SprintDrillDrawer
        opened={opened && sprintTarget !== null}
        target={sprintTarget}
        filter={NO_FILTER}
        scope="sprint-consistency"
        fetchItems={getSprintScopeItems}
        sprintLabel={sprintLabel}
        chartCount={(sprint) => {
          const last = overview.teams.find((team) => team.teamId === sprint.teamId)?.lastSprint;
          return last ? sprintBucketItems(last, sprint.bucket) : undefined;
        }}
        timeZone={overview.timeZone}
        onClose={onClose}
        onSegmentChange={() => undefined}
      />
      <WeekDrawer overview={overview} opened={opened} target={target?.kind === "week" ? target : null} onClose={onClose} />
      <ViewDrawer overview={overview} opened={opened} target={target?.kind === "home" ? target : null} onClose={onClose} />
    </>
  );
}
