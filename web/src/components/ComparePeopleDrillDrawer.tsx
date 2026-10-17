import { useEffect } from "react";
import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getComparePeopleItems } from "../api/reportDrill";
import type { ComparePeopleReport, ReportFilters } from "../api/reports";
import { cellCountOf, mixKeyLabel, viewColumns } from "../utils/comparePeopleReport";
import { distributionPeople } from "../utils/distributionPeople";
import { serializeDrill, type CellDrillTarget, type DrillTarget, type MixDrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { formatDays } from "../utils/reportFormat";
import DistributionDrillDrawer, { type DistributionDrillView } from "./DistributionDrillDrawer";
import ReportDrillDrawer from "./ReportDrillDrawer";

interface Props {
  report: ComparePeopleReport;
  filters: ReportFilters;
  filter: ReportFilterState;
  /** Whether the drill is open; `target` is the hook's `shown` (the last target, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  onClose: () => void;
  onSegmentChange: (accountId: string | undefined) => void;
}

/** The two people numbered like the histograms' legend ("1. Ann"): who a drawer's person select can move between. */
function sidesOf(report: ComparePeopleReport) {
  return report.people.map((person, index) => ({ accountId: person.accountId, label: person.label, position: index + 1 }));
}

/**
 * The items behind one row cell (`cell`) or one work-mix share (`mix`): a SINGLE person's list (the API requires the person), fetched with
 * the page's own filter so it is exactly what the table counted. A target the SHOWN report did not draw — a row it does not carry (the
 * capacity rows are an editor's), a mix key it does not list, a person outside the two — opens nothing and, once the report is in hand, is
 * dropped from the URL (never a drawer that answers a 400).
 */
function CellDrillDrawer({
  report,
  filters,
  filter,
  opened,
  target,
  timeZone,
  onClose,
  onSegmentChange,
}: Omit<Props, "target"> & { target: CellDrillTarget | MixDrillTarget | null; timeZone: string }) {
  const { t } = useTranslation();
  const sides = sidesOf(report);
  const side = sides.find((candidate) => candidate.accountId === target?.segmentAccountId);
  const mixRow = target?.kind === "mix" ? report.workMix[target.by].find((row) => row.key === target.key) : undefined;
  // The row that offers the view (its `drill` is the typed view): a view the shown report has no row for was not drawn.
  const viewRow = target?.kind === "cell" ? report.rows.find((row) => row.drill === target.view) : undefined;
  const drawn = side !== undefined && (target?.kind === "mix" ? mixRow !== undefined : viewRow?.drill !== undefined);
  const open = drawn ? target : null;
  const stale = opened && target !== null && !drawn;
  useEffect(() => {
    if (stale) onClose();
  }, [stale, onClose]);
  const query = useQuery({
    queryKey: ["reports", "compare-people", "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => getComparePeopleItems(filter, open) : skipToken,
  });
  const index = side === undefined ? 0 : side.position - 1;
  const columns = viewColumns(open?.kind === "cell" ? open.view : "DELIVERED");
  const who = side === undefined ? "" : t("reports.comparePeople.side", { side: index === 0 ? "A" : "B", name: side.label });
  let title = "";
  let chartCount: number | undefined;
  if (open?.kind === "cell" && viewRow?.drill) {
    title = t("reports.comparePeople.drill.cell", { view: t(`reports.comparePeople.view.${viewRow.drill}`), person: who });
    chartCount = cellCountOf(report, open.view, index);
  } else if (open?.kind === "mix" && mixRow) {
    const slice = t(`reports.comparePeople.mix.${open.by}`);
    title = t("reports.comparePeople.drill.mix", { slice, key: mixKeyLabel(open.by, open.key, t, filters.domains), person: who });
    chartCount = (index === 0 ? mixRow.a : mixRow.b).items;
  }
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={title}
      query={query}
      unit={columns.unit}
      anchor={columns.anchor}
      timeZone={timeZone}
      chartCount={chartCount}
      people={sides}
      segmentAccountId={open?.segmentAccountId}
      onSegmentChange={onSegmentChange}
      allowEveryone={false}
    />
  );
}

/**
 * The drill drawer of Compare people. Three kinds of target share the page's `drill` param: a histogram bar of one of the two cycle-time
 * charts (`hist`, a person's bar: the shared `DistributionDrillDrawer`), a row cell (`cell`) and a work-mix share (`mix`, the
 * `CellDrillDrawer`). Every list is one person's, so the person select moves between the two and has no "Everyone".
 */
export default function ComparePeopleDrillDrawer({ report, filters, filter, opened, target, onClose, onSegmentChange }: Props) {
  const { t } = useTranslation();
  const people = distributionPeople(report.cycleTime.workingDays);
  const sides = sidesOf(report);
  const views: DistributionDrillView[] = [
    {
      view: "CYCLE_WORKING_DAYS",
      title: t("reports.cycleTime.workingTitle"),
      distribution: report.cycleTime.workingDays,
      format: formatDays,
      unit: "wd",
      people: people.length > 0 ? people : sides,
    },
    {
      view: "CYCLE_ELAPSED_DAYS",
      title: t("reports.cycleTime.elapsedTitle"),
      distribution: report.cycleTime.elapsedDays,
      format: formatDays,
      unit: "days",
      people: people.length > 0 ? people : sides,
    },
  ];
  const cellTarget = target?.kind === "cell" || target?.kind === "mix" ? target : null;
  // A bar names one person of the two (the API requires the segment): without one, or with a stranger, the link is stale — closed, never a 400.
  const strangerBar = target?.kind === "hist" && !sides.some((side) => side.accountId === target.segmentAccountId);
  useEffect(() => {
    if (opened && strangerBar) onClose();
  }, [opened, strangerBar, onClose]);
  return (
    <>
      <DistributionDrillDrawer
        report="compare-people"
        fetchItems={getComparePeopleItems}
        opened={opened && !strangerBar}
        target={strangerBar ? null : target}
        views={views}
        filter={filter}
        timeZone={filters.timeZone}
        onClose={onClose}
        onSegmentChange={onSegmentChange}
        segmentRequired
      />
      <CellDrillDrawer
        report={report}
        filters={filters}
        filter={filter}
        opened={opened}
        target={cellTarget}
        timeZone={filters.timeZone}
        onClose={onClose}
        onSegmentChange={onSegmentChange}
      />
    </>
  );
}
