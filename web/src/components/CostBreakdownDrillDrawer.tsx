import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getCostsItems } from "../api/reportDrill";
import { breakdownHeading, keyLabel } from "../utils/costBreakdown";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import ReportDrillDrawer from "./ReportDrillDrawer";

/**
 * The Costs drill drawer: the tasks behind one slice of an activity-type or work-category donut, fetched from
 * `GET /reports/costs/items` with the SAME filter the report was requested with, so the list is the slice's own worklogs. The title is the slice ("Development") over the breakdown as a dimmed subtitle ("Activity type"); the folded "Other"
 * slice says how many types it holds and lists them under the title. Tasks and epics mix (an epic is listed when work was logged
 * on it), so the Kind column is always shown; the figure is the man-days logged in the slice.
 */
export default function CostBreakdownDrillDrawer({
  opened,
  target,
  filter,
  timeZone,
  onClose,
}: {
  /** Whether the drill is open; `target` is the hook's `shown` (the last slice, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  filter: ReportFilterState;
  timeZone: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const open = target?.kind === "cost" ? target : null;
  const query = useQuery({
    queryKey: ["reports", "costs", "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => getCostsItems(filter, open) : skipToken,
  });
  const heading = open ? breakdownHeading(t, open) : undefined;
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={heading?.title ?? ""}
      subtitle={heading?.subtitle}
      caption={open && open.keys.length > 1 ? t("reports.costs.breakdown.includes", { keys: open.keys.map((key) => keyLabel(t, key)).join(", ") }) : undefined}
      query={query}
      unit="loggedMd"
      anchor="logged"
      timeZone={timeZone}
      showKind
    />
  );
}
