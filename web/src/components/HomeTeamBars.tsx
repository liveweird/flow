import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Title } from "@mantine/core";
import type { ChartBarHit } from "../utils/chartDrill";
import { useHomeWindowDays } from "../hooks/useHomeWindowDays";
import { figureColumns } from "../utils/homeColumns";
import { teamBarsHeight, type HomeFigureRow, type OpenDrill } from "../utils/homeOverview";
import HomeHiddenTable from "./HomeHiddenTable";
import LoadingBlock from "./LoadingBlock";
import NearViewport from "./NearViewport";

// The chart (and with it recharts) rides its own lazy chunk.
const HomeTeamBarsChart = lazy(() => import("./HomeTeamBarsChart"));

/**
 * One small multiple of a Home section: its title (an h4, also the words of the chart's and table's accessible names), the lazy
 * horizontal bar chart of one figure per team, and the chart's hidden table (revealed by keyboard focus) whose figures open the
 * items behind each team's bar — the same list a double-click on the bar opens. [anchor] is the section card focus returns to
 * after a pointer drill.
 */
export default function HomeTeamBars({
  title,
  rows,
  color,
  format,
  integer,
  valueHeader,
  itemsHeader,
  valueIsItems,
  withTrend = false,
  anchor,
  onOpen,
  headingOrder = 4,
}: {
  title: string;
  rows: HomeFigureRow[];
  color: string;
  format: (value: number) => string;
  integer?: boolean;
  valueHeader: string;
  itemsHeader?: string;
  valueIsItems?: boolean;
  withTrend?: boolean;
  anchor: string;
  onOpen: OpenDrill;
  /** The title's heading level: 4 under a section, 5 under a group heading of its own. */
  headingOrder?: 4 | 5;
}) {
  const { t } = useTranslation();
  const days = useHomeWindowDays();
  const onBarDoubleClick = (hit: ChartBarHit) => {
    const row = rows.find((candidate) => String(candidate.teamId) === hit.rowId);
    if (row && row.count > 0) onOpen({ kind: "home", view: row.view, teamId: row.teamId }, { anchor });
  };
  const columns = figureColumns({
    t,
    format,
    labelHeader: t("home.table.team"),
    valueHeader,
    itemsHeader,
    valueIsItems,
    withTrend,
    days,
    nameOf: (row) => `${row.team} · ${title}`,
    onOpen: (row, trigger) => onOpen({ kind: "home", view: row.view, teamId: row.teamId }, { trigger, anchor }),
  });
  return (
    <Stack gap="xs">
      <Title order={headingOrder} size="h5">
        {title}
      </Title>
      <NearViewport height={teamBarsHeight(rows.length)}>
        <Suspense fallback={<LoadingBlock />}>
          <HomeTeamBarsChart
            rows={rows}
            title={title}
            color={color}
            format={format}
            integer={integer}
            onBarDoubleClick={onBarDoubleClick}
          />
        </Suspense>
      </NearViewport>
      <HomeHiddenTable label={t("home.table.label", { title })} columns={columns} rows={rows} rowKey={(row) => row.key} />
    </Stack>
  );
}
