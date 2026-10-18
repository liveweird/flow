import type { HomeDrillView, HomeFigure, HomeTeam } from "../api/home";
import { figureRow, type HomeFigureRow } from "./homeOverview";

/** The three PRED shares the overview draws: the items whose actual landed within ± this many percent of the estimate at start. */
export const PRED_SHARES = [10, 25, 50] as const;
export type PredShare = (typeof PRED_SHARES)[number];

/** The two lines of a team's PRED curve: its tasks (credit team) and its epics (owner team). */
export type PredLine = "tasks" | "epics";

const VIEWS: Record<PredLine, Record<PredShare, HomeDrillView>> = {
  tasks: { 10: "TASK_PRED10", 25: "TASK_PRED25", 50: "TASK_PRED50" },
  epics: { 10: "EPIC_PRED10", 25: "EPIC_PRED25", 50: "EPIC_PRED50" },
};

/** The drill view of one point of the curve. */
export function predView(line: PredLine, share: PredShare): HomeDrillView {
  return VIEWS[line][share];
}

/** One point of a team's curve: the share, the items within it and the sample. */
export function predSeries(team: HomeTeam, line: PredLine, share: PredShare): { value: number | null; count: number; n: number } {
  const figure = predFigure(team, line, share);
  return { value: figure.value, count: figure.numerator ?? 0, n: figure.n };
}

function predFigure(team: HomeTeam, line: PredLine, share: PredShare): HomeFigure {
  const pred = team.accuracy[line];
  if (share === 10) return pred.pred10;
  return share === 25 ? pred.pred25 : pred.pred50;
}

/** The hidden table's rows for a team: tasks then epics, one row per share ("Tasks ±25%"), each with its trend. */
export function predRows(team: HomeTeam, label: (line: PredLine, share: PredShare) => string): HomeFigureRow[] {
  return (["tasks", "epics"] as const).flatMap((line) =>
    PRED_SHARES.map((share) => figureRow(team, label(line, share), predView(line, share), predFigure(team, line, share))),
  );
}

/** Whether a point of the curve is a real one (a line and share the chart draws). */
export function predPoint(rowId: string, series: string): { line: PredLine; share: PredShare } | null {
  const share = PRED_SHARES.find((candidate) => String(candidate) === rowId);
  const line = series === "tasks" || series === "epics" ? series : undefined;
  return share === undefined || line === undefined ? null : { line, share };
}
