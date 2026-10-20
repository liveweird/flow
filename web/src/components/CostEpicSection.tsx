import { useTranslation } from "react-i18next";
import type { CostMatrixReport } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { EPIC_ANCHOR } from "../utils/costMatrixReport";
import type { ReportFilterState } from "../utils/reportFilter";
import CostEpicDrillDrawer from "./CostEpicDrillDrawer";
import CostEpicTable from "./CostEpicTable";
import ReportChartCard from "./ReportChartCard";

/**
 * The cost matrix's man-days by epic, under the matrix: the very worklogs the matrix reads, one row per epic, the same columns as its authors.
 * Drawn only when some worklog names a row (like the donuts). The drill drawer lives here too, so a deep link (`drill=epic:…`) opens it once the report is in hand; the drill state is the page's.
 */
export default function CostEpicSection({
  report,
  filter,
  timeZone,
  drill,
  drillable,
  isRefreshing,
}: {
  report: CostMatrixReport;
  filter: ReportFilterState;
  timeZone: string;
  /** The page's drill (shared with the donuts: one hook, one focus owner). */
  drill: DrillState;
  /** False while the report shown is kept data of the previous filter: its figures must not open a drill under the new one. */
  drillable: boolean;
  isRefreshing: boolean;
}) {
  const { t } = useTranslation();
  const { level } = report.meta;
  // Nothing logged (a team whose members logged nothing: zero rows, no worklog): no epic to list, and the matrix above already says so.
  if (report.byEpic.rows.length === 0) return null;
  const title = t(`reports.costMatrix.epics.levelTitle.${level}`);
  return (
    <>
      <ReportChartCard
        title={title}
        caption={t(`reports.costMatrix.epics.caption.${level}`)}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={false}
        hint={t("reports.costMatrix.epics.hint")}
        drillAnchor={EPIC_ANCHOR}
      >
        <CostEpicTable report={report} title={title} drillable={drillable} onOpen={(target, trigger) => drill.open(target, { trigger, anchor: EPIC_ANCHOR })} />
      </ReportChartCard>
      <CostEpicDrillDrawer
        opened={drill.target !== null}
        target={drill.shown}
        filter={filter}
        byEpic={report.byEpic}
        level={level}
        timeZone={timeZone}
        onClose={drill.close}
      />
    </>
  );
}
