import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { ReportFilters } from "../api/reports";
import type { ReportControls, ReportFilterState } from "../utils/reportFilter";
import { reportFilterLines, tailPercentLine, type PrintFilterLine } from "../utils/reportPrintSummary";
import PageHeader from "./PageHeader";
import PrintReportButton from "./PrintReportButton";
import ReportPrintSummary from "./ReportPrintSummary";

/**
 * Every report page's first row: the `PageHeader` with the "Print / save as PDF" action, plus the print-only summary (report name,
 * the filter in words, generation time). A page passes its `filter` + `filters` + `controls` (the same ones its filter bar takes), or —
 * where the filter is not a `ReportFilterState` (the Deep dive's selection) — the `lines` it wants printed instead. Page-local state that
 * changes the figures but lives outside the filter bar goes in `extraLines` (Throughput's cumulative view), and `tail` adds the
 * long-tail share of the distribution reports. All optional: while the reference data loads the summary has only the name and the stamp.
 */
export default function ReportPageHeader({
  title,
  description,
  filter,
  filters,
  controls = {},
  lines,
  extraLines = [],
  tail = false,
  toolbar,
}: {
  title: string;
  description?: string;
  filter?: ReportFilterState;
  filters?: ReportFilters;
  controls?: ReportControls;
  lines?: readonly PrintFilterLine[];
  extraLines?: readonly PrintFilterLine[];
  tail?: boolean;
  /** A row under the title (Home's team picker); passed straight to `PageHeader`. */
  toolbar?: ReactNode;
}) {
  const { t } = useTranslation();
  const printed = [
    ...(lines ?? (filter && filters ? reportFilterLines(filter, filters, controls, t) : [])),
    ...(tail && filter ? [tailPercentLine(filter, t)] : []),
    ...extraLines,
  ];
  return (
    <>
      <PageHeader title={title} description={description} actions={<PrintReportButton />} toolbar={toolbar} />
      <ReportPrintSummary title={title} lines={printed} timeZone={filters?.timeZone ?? "UTC"} />
    </>
  );
}
