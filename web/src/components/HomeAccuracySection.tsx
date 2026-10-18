import { lazy, Suspense } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import type { HomeOverview, HomeTeam } from "../api/home";
import { useHomeWindowDays } from "../hooks/useHomeWindowDays";
import type { ChartBarHit } from "../utils/chartDrill";
import { figureColumns } from "../utils/homeColumns";
import { overviewLink, sectionAnchor, trendOf, windowFilter, type OpenDrill } from "../utils/homeOverview";
import { predPoint, predRows, predSeries, predView } from "../utils/homePred";
import { epicAccuracyPath, taskAccuracyPath } from "../utils/reportLinks";
import { formatPercent } from "../utils/reportFormat";
import HomeHiddenTable from "./HomeHiddenTable";
import HomeTile from "./HomeTile";
import HomeTrendMark from "./HomeTrendMark";
import LoadingBlock from "./LoadingBlock";
import NearViewport from "./NearViewport";

// The chart (and with it recharts) rides its own lazy chunk.
const HomePredChart = lazy(() => import("./HomePredChart"));

const ANCHOR = sectionAnchor("accuracy");

/** One team's PRED curve: its name (the way into the task accuracy report for that team and window), the chart, a ±25 % summary and the hidden table. */
function TeamPred({ team, overview, open }: { team: HomeTeam; overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const label = (line: "tasks" | "epics", share: number) => t(`home.accuracy.row.${line}`, { percent: share });
  const rows = predRows(team, label);
  const onDotDoubleClick = (hit: ChartBarHit) => {
    const point = predPoint(hit.rowId, hit.series);
    // A point with no item within its share has nothing to list.
    if (point && predSeries(team, point.line, point.share).count > 0) {
      open({ kind: "home", view: predView(point.line, point.share), teamId: team.teamId }, { anchor: ANCHOR });
    }
  };
  const columns = figureColumns({
    t,
    format: formatPercent,
    days: useHomeWindowDays(),
    labelHeader: t("home.table.measure"),
    valueHeader: t("home.accuracy.valueHeader"),
    itemsHeader: t("home.accuracy.itemsHeader"),
    withTrend: true,
    withSample: true,
    nameOf: (row) => `${row.team} · ${row.label}`,
    onOpen: (row, trigger) => open({ kind: "home", view: row.view, teamId: row.teamId }, { trigger, anchor: ANCHOR }),
  });
  const summary = (line: "tasks" | "epics") => {
    const figure = team.accuracy[line].pred25;
    return (
      <>
        {t(`home.accuracy.summary.${line}`)} {figure.value === null ? "—" : formatPercent(figure.value)}
        <HomeTrendMark trend={trendOf(figure.previous)} />
      </>
    );
  };
  return (
    <Stack gap="xs">
      <Title order={4} size="h5">
        <Anchor
          component={RouterLink}
          to={overviewLink(taskAccuracyPath, { ...windowFilter(overview), teamId: team.teamId })}
          inherit
        >
          {team.name}
        </Anchor>
      </Title>
      <Text size="xs" c="dimmed">
        {t("home.accuracy.within", { percent: 25 })}: {summary("tasks")}, {summary("epics")}
      </Text>
      <NearViewport height={200}>
        <Suspense fallback={<LoadingBlock />}>
          <HomePredChart team={team} onDotDoubleClick={onDotDoubleClick} />
        </Suspense>
      </NearViewport>
      <HomeHiddenTable
        label={t("home.accuracy.tableLabel", { team: team.name })}
        columns={columns}
        rows={rows}
        rowKey={(row) => row.key}
      />
    </Stack>
  );
}

/**
 * Section 3 — how well the estimates hold: per team, PRED(10/25/50) at start — the share of items whose actual landed within ±10, 25
 * and 50 % of the estimate — as two lines (tasks solid, epics dashed). The team name opens the task accuracy report for that team
 * and window; the ±25 % summary carries the trend (higher is better).
 */
export default function HomeAccuracySection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const period = windowFilter(overview);
  return (
    <HomeTile
      title={t("home.accuracy.title")}
      to={overviewLink(taskAccuracyPath, period)}
      caption={t("home.accuracy.caption", {
        from: overview.window.from,
        to: overview.window.to,
        previousFrom: overview.previousWindow.from,
        previousTo: overview.previousWindow.to,
      })}
      drillAnchor={ANCHOR}
      links={[{ to: overviewLink(epicAccuracyPath, period), label: t("home.accuracy.epicLink") }]}
    >
      <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }} spacing="lg">
        {overview.teams.map((team) => (
          <TeamPred key={team.teamId} team={team} overview={overview} open={open} />
        ))}
      </SimpleGrid>
    </HomeTile>
  );
}
