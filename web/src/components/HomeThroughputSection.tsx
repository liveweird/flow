import { lazy, Suspense, type ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import type { HomeFigure, HomeOverview, HomeTeam, HomeWeek } from "../api/home";
import { CHART_COLORS } from "../utils/chartColors";
import type { ChartBarHit } from "../utils/chartDrill";
import { drillFigureColumn } from "../utils/drillFigure";
import { overviewLink, sectionAnchor, teamFigureRows, trendOf, windowFilter, type OpenDrill } from "../utils/homeOverview";
import { cycleTimePath, throughputPath } from "../utils/reportLinks";
import { formatDays, formatMd } from "../utils/reportFormat";
import type { ColumnDef } from "./ColumnTable";
import HomeHiddenTable from "./HomeHiddenTable";
import HomeTeamBars from "./HomeTeamBars";
import HomeTile from "./HomeTile";
import HomeTrendMark from "./HomeTrendMark";
import LoadingBlock from "./LoadingBlock";
import NearViewport from "./NearViewport";

// The chart (and with it recharts) rides its own lazy chunk.
const HomeWeeklyChart = lazy(() => import("./HomeWeeklyChart"));

const ANCHOR = sectionAnchor("throughput");

/** A total with its trend mark and the previous window's value, in the flow of the team's summary line. */
function Total({ figure, unit, format }: { figure: HomeFigure; unit: (value: number) => ReactNode; format: (value: number) => string }) {
  const { t } = useTranslation();
  const previous = figure.previous?.value;
  return (
    <>
      {format(figure.value ?? 0)} {unit(figure.value ?? 0)}
      <HomeTrendMark trend={trendOf(figure.previous)} />
      {previous != null && <> {t("home.throughput.previousTotal", { value: format(previous) })}</>}
    </>
  );
}

/** One team's delivery: its name (the way into Throughput for that team and window), the totals with their trends, the weekly bars and the hidden weeks table. */
function TeamWeeks({ team, overview, open }: { team: HomeTeam; overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const weeks = team.throughput.weeks;
  const onBarDoubleClick = (hit: ChartBarHit) => {
    const week = weeks.find((candidate) => candidate.bucketStart === hit.rowId);
    if (week && week.deliveredItems > 0) open({ kind: "week", teamId: team.teamId, bucketStart: week.bucketStart }, { anchor: ANCHOR });
  };
  const figure = (column: ColumnDef<HomeWeek>): ColumnDef<HomeWeek> =>
    drillFigureColumn(column, {
      label: (week, text, header) =>
        t("reports.drill.figureAria", { figure: text, column: header, name: `${team.name} · ${t("home.throughput.weekOf", { date: week.bucketStart })}` }),
      count: (week) => week.deliveredItems,
      onOpen: (week, trigger) => open({ kind: "week", teamId: team.teamId, bucketStart: week.bucketStart }, { trigger, anchor: ANCHOR }),
    });
  const columns: ColumnDef<HomeWeek>[] = [
    { key: "week", header: t("home.throughput.weekHeader"), render: (week) => week.bucketStart },
    // One figure per week is the button (the items; the man-days are the same week's list): two buttons per row would double the Tab stops.
    { key: "md", header: t("home.throughput.mdHeader"), render: (week) => formatMd(week.deliveredMd), align: "right" },
    figure({ key: "items", header: t("home.throughput.itemsHeader"), render: (week) => String(week.deliveredItems), align: "right" }),
  ];
  return (
    <Stack gap="xs">
      <Title order={4} size="h5">
        <Anchor component={RouterLink} to={overviewLink(throughputPath, { ...windowFilter(overview), teamId: team.teamId })} inherit>
          {team.name}
        </Anchor>
      </Title>
      <Text size="xs" c="dimmed">
        {t("home.throughput.total")}: <Total figure={team.throughput.deliveredMd} unit={() => t("reports.unitMd")} format={formatMd} />
        {" · "}
        <Total figure={team.throughput.deliveredItems} unit={(count) => t("home.throughput.itemsUnit", { count })} format={String} />
      </Text>
      <NearViewport height={170}>
        <Suspense fallback={<LoadingBlock />}>
          <HomeWeeklyChart team={team} onBarDoubleClick={onBarDoubleClick} />
        </Suspense>
      </NearViewport>
      <HomeHiddenTable label={t("home.throughput.tableLabel", { team: team.name })} columns={columns} rows={weeks} rowKey={(week) => week.bucketStart} />
    </Stack>
  );
}

/**
 * Section 6 — how much each team delivers and how fast: per team the tasks it finished each week of the window (man-days, teal,
 * zero weeks included) with the window's totals beside the previous window's, then the cycle time p50 and p85 (working days, lower is
 * better) of every team as two small multiples. A bar of a week opens that week's tasks; the cycle bars open the tasks behind the
 * percentile.
 */
export default function HomeThroughputSection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const period = windowFilter(overview);
  const common = { color: CHART_COLORS.estimate, withTrend: true, anchor: ANCHOR, onOpen: open, format: formatDays };
  return (
    <HomeTile
      title={t("home.throughput.title")}
      to={overviewLink(throughputPath, period)}
      caption={t("home.throughput.caption", {
        from: overview.window.from,
        to: overview.window.to,
        previousFrom: overview.previousWindow.from,
        previousTo: overview.previousWindow.to,
      })}
      drillAnchor={ANCHOR}
      links={[{ to: overviewLink(cycleTimePath, period), label: t("home.throughput.cycleLink") }]}
    >
      <Stack gap="lg">
        <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }} spacing="lg">
          {overview.teams.map((team) => (
            <TeamWeeks key={team.teamId} team={team} overview={overview} open={open} />
          ))}
        </SimpleGrid>
        <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
          <HomeTeamBars
            {...common}
            title={t("home.throughput.cycleP50")}
            rows={teamFigureRows(overview.teams, "TASK_CYCLE", (team) => team.throughput.cycleP50)}
            valueHeader={t("home.throughput.cycleP50")}
          />
          <HomeTeamBars
            {...common}
            title={t("home.throughput.cycleP85")}
            rows={teamFigureRows(overview.teams, "TASK_CYCLE", (team) => team.throughput.cycleP85)}
            valueHeader={t("home.throughput.cycleP85")}
          />
        </SimpleGrid>
      </Stack>
    </HomeTile>
  );
}
