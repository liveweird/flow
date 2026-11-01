// The Home overview API: ONE any-authenticated, read-only request answers the whole page (the picked teams' figures over
// the trailing 90 days beside the previous 90), plus the drill that lists the items behind one team's figure.

import { jsonRequest } from "./http";
import type { ReportDrillItems } from "./reportDrill";
import type { paths } from "./schema";

export type HomeOverview = paths["/api/v1/reports/home"]["get"]["responses"]["200"]["content"]["application/json"];
export type HomeTeam = HomeOverview["teams"][number];
export type HomeSprint = NonNullable<HomeTeam["lastSprint"]>;
export type HomeFigure = HomeTeam["granularity"]["estimateMd"];
export type HomePrevious = NonNullable<HomeFigure["previous"]>;
export type HomeWip = HomeTeam["wipTasks"];
export type HomeWeek = HomeTeam["throughput"]["weeks"][number];
export type HomeDrillView = paths["/api/v1/reports/home/items"]["get"]["parameters"]["query"]["view"];

/** Every view the server's drill answers, in the order of the page's sections (the codec and its tests iterate this). */
export const HOME_DRILL_VIEWS: readonly HomeDrillView[] = [
  "TASK_ESTIMATE",
  "TASK_CYCLE",
  "TASK_COST",
  "TASK_PRED10",
  "TASK_PRED25",
  "TASK_PRED50",
  "EPIC_PRED10",
  "EPIC_PRED25",
  "EPIC_PRED50",
  "WIP_TASKS",
  "WIP_EPICS",
  "QUALITY_TASKS_NO_ESTIMATE",
  "QUALITY_TASKS_UNASSIGNED",
  "QUALITY_TASKS_ESTIMATED_LATE",
  "QUALITY_EPICS_NO_ESTIMATE",
  "QUALITY_EPICS_NO_DATES",
  "QUALITY_EPICS_ESTIMATE_GAP",
  "CODE_MERGED",
  "CODE_OPEN",
  "CODE_MERGED_WITHOUT_REVIEW",
];

/** The overview of the given teams (1 to 3 ids; `teamId` repeated); no ids = the bare URL, the server's caller-agnostic default. */
export async function getHomeOverview(teamIds: readonly number[]): Promise<HomeOverview> {
  const params = new URLSearchParams();
  for (const id of teamIds) params.append("teamId", String(id));
  const query = params.toString();
  return jsonRequest<HomeOverview>(query === "" ? "/api/v1/reports/home" : `/api/v1/reports/home?${query}`);
}

/** The items behind one figure (`view`) of one team. */
export async function getHomeItems(view: HomeDrillView, teamId: number): Promise<ReportDrillItems> {
  const params = new URLSearchParams({ view, teamId: String(teamId) });
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/home/items?${params.toString()}`);
}
