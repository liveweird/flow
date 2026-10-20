import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getCostMatrixEpicItems } from "../api/reportDrill";
import type { CostEpicBreakdown } from "../api/reports";
import { epicHeading } from "../utils/costEpics";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import ReportDrillDrawer from "./ReportDrillDrawer";

/**
 * The cost matrix's epic-table drill drawer: the tasks behind one row, cell or total, from `GET /reports/cost-matrix/items` with `by=EPIC`
 * and the SAME filter the matrix was requested with (the matrix-only `split` aside; a cell adds its column's team or author). The title is
 * the row ("FLO-10 Checkout", "No epic", "Other epics (N)"), the dimmed subtitle the column; tasks and epics mix (an epic is listed when
 * work was logged on it), so the Kind column is always shown, and the figure is the man-days logged in the slice.
 */
export default function CostEpicDrillDrawer({
  opened,
  target,
  filter,
  byEpic,
  level,
  timeZone,
  onClose,
}: {
  /** Whether the drill is open; `target` is the hook's `shown` (the last figure, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  filter: ReportFilterState;
  byEpic: CostEpicBreakdown;
  level: "UNIT" | "TEAM" | "USER";
  timeZone: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const open = target?.kind === "epic" ? target : null;
  const query = useQuery({
    queryKey: ["reports", "cost-matrix", "items", reportQuery({ ...filter, split: undefined }), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => getCostMatrixEpicItems(filter, open) : skipToken,
  });
  const heading = open ? epicHeading(t, open, byEpic, level) : undefined;
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={heading?.title ?? ""}
      subtitle={heading?.subtitle}
      query={query}
      unit="loggedMd"
      anchor="logged"
      timeZone={timeZone}
      showKind
    />
  );
}
