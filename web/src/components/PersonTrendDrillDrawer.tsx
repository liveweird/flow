import { useEffect, useRef } from "react";
import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getPersonTrendItems } from "../api/reportDrill";
import type { PersonTrendReport } from "../api/reports";
import { viewColumns } from "../utils/comparePeopleReport";
import { pointCountOf } from "../utils/personTrendReport";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import ReportDrillDrawer from "./ReportDrillDrawer";

interface Props {
  report: PersonTrendReport;
  filter: ReportFilterState;
  timeZone: string;
  /** Whether the drill is open; `target` is the hook's `shown` (the last target, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  onClose: () => void;
}

/**
 * The items behind ONE point of a Person trend chart (or the numbers table's figure): the person's Compare-people list for that row over the
 * point's 90-day window, fetched with the page's own filter so it is exactly the set the point counted. The heading is the row's label and the
 * whole 90-day window ("Delivered items · 2025-12-06 – 2026-03-05"). The list is always the one person's (the filter names them), so there is no person select.
 * A target the SHOWN report did not draw — a row it does not carry or a day that is none of its windows (an old link, another range) — opens
 * nothing and, once the report is in hand, is dropped from the URL (never a drawer that answers a 400).
 */
export default function PersonTrendDrillDrawer({ report, filter, timeZone, opened, target, onClose }: Props) {
  const { t } = useTranslation();
  const point = target?.kind === "point" ? target : null;
  const row = point === null ? undefined : report.rows.find((candidate) => candidate.key === point.row);
  const index = point === null ? -1 : report.windows.findIndex((window) => window.end === point.end);
  const view = row?.drill;
  const drawn = point !== null && view !== undefined && index >= 0;
  const open = drawn ? point : null;
  const stale = opened && target !== null && !drawn;
  // Close a stale target ONCE (HomeDrillDrawer's idiom): `onClose` changes identity every render and is `navigate(-1)` when this page opened the
  // drill, so a second call before the location has changed would step back twice. The ref remembers which target it already closed.
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
  const query = useQuery({
    queryKey: ["reports", "person-trend", "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open && view !== undefined ? () => getPersonTrendItems(filter, view, open.end) : skipToken,
  });
  const windowOf = open === null || index < 0 ? undefined : report.windows[index];
  const columns = viewColumns(view ?? "DELIVERED");
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={row === undefined ? "" : t(`reports.comparePeople.row.${row.key}`)}
      subtitle={windowOf === undefined ? undefined : `${windowOf.from} – ${windowOf.end}`}
      query={query}
      unit={columns.unit}
      anchor={columns.anchor}
      timeZone={timeZone}
      chartCount={row === undefined || index < 0 ? undefined : pointCountOf(row, row.points[index].n)}
    />
  );
}
