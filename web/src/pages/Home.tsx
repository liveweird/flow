import { useCallback, useEffect, useMemo, useRef } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Anchor, Box, Group, Paper, Skeleton, Stack, Text, VisuallyHidden } from "@mantine/core";
import { IconDatabaseOff } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { getHomeOverview, type HomeOverview } from "../api/home";
import { getReportFilters } from "../api/reports";
import { getUserId } from "../api/session";
import { useAdmin } from "../auth";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import HomeAccuracySection from "../components/HomeAccuracySection";
import HomeDrillDrawer from "../components/HomeDrillDrawer";
import HomeFreshness from "../components/HomeFreshness";
import HomeGranularitySection from "../components/HomeGranularitySection";
import HomeCodeSection from "../components/HomeCodeSection";
import HomeQualitySection from "../components/HomeQualitySection";
import HomeSprintSection from "../components/HomeSprintSection";
import HomeTeamPicker from "../components/HomeTeamPicker";
import HomeThroughputSection from "../components/HomeThroughputSection";
import HomeWipSection from "../components/HomeWipSection";
import ReportPageHeader from "../components/ReportPageHeader";
import { HomeWindowDays } from "../hooks/useHomeWindowDays";
import { useReportDrill } from "../hooks/useReportDrill";
import { useStoredState } from "../hooks/useStoredState";
import { dataSourcesPath, metricsSettingsPath } from "../utils/dataSourceLinks";
import { drillAnchorOf, hasCodeSection, nothingDerived, windowDays, type OpenDrill } from "../utils/homeOverview";
import { effectiveHomeTeams, isTeamPick, livePick } from "../utils/homeTeams";
import { CONTENT_MAX_WIDTH } from "../utils/layout";

// The overview is a glance, not a workbench: DERIVE runs on a schedule, so a minute of freshness is plenty and a return visit to
// Home does not refetch the whole overview.
const STALE_MS = 60_000;

/** The drill's filter key: Home has no filter bar, so an open drill is never closed by a filter change. */
const DRILL_KEY = "home";

/** The remembered pick (`flow.viewSettings.home.teams`): up to three team ids, or `null` = the default. */
const PICK_KEY = "home.teams";

/**
 * The landing page (`/`) — the overview of up to three teams over the trailing window (90 days), each figure beside the window before
 * (never the remembered report team). The teams are the viewer's pick (remembered in this browser) or, by default, the ones they manage
 * first and then the others by name; the page always sends explicit `teamId`s, so the server's answer never depends on who asks.
 * Load order: the reference data (`["reports","filters"]`, for the team list and each team's manager), then ONE overview request
 * (`GET /reports/home`, keyed by the team ids): a freshness line, then seven sections — each
 * team's last closed sprint, the granularity medians, the PRED accuracy curves, the epics and the tasks in progress, the weekly
 * delivery with the cycle time, and the data-quality counts — as small multiples with a trend arrow beside every windowed figure.
 * Every bar opens the shared drill pane (a double-click; the keyboard route is each chart's table, visually hidden until it takes
 * focus), and each section title is the way into the full report for the same window. Every signed-in user sees it (D12). When
 * nothing has been derived yet the page shows the empty state instead of seven empty sections. A failed background refetch keeps the
 * overview already on screen and shows its alert beside it.
 */
export default function Home() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const filtersQuery = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: STALE_MS });
  const teams = filtersQuery.data?.teams;
  const [stored, setStored] = useStoredState<number[] | null>(PICK_KEY, null, isTeamPick);
  const userId = getUserId();
  const ids = useMemo(() => (teams ? effectiveHomeTeams(stored, teams, userId) : null), [teams, stored, userId]);
  const query = useQuery({
    queryKey: ["home", ids],
    queryFn: () => getHomeOverview(ids ?? []),
    enabled: ids !== null,
    staleTime: STALE_MS,
    // A new pick keeps the overview on screen (marked busy) until the new one arrives.
    placeholderData: keepPreviousData,
  });
  const overview = query.data;
  const busy = query.isPlaceholderData;
  const failure = overview === undefined ? (query.error ?? filtersQuery.error) : query.error;
  const drill = useReportDrill(DRILL_KEY, drillAnchorOf);
  // The sections are memoised on the overview, so opening and closing a drill (a URL change that re-renders this page) does not redraw
  // thirty charts: they get a stable `open` that always calls the hook's latest.
  const openRef = useRef(drill.open);
  useEffect(() => {
    openRef.current = drill.open;
  });
  const open = useCallback<OpenDrill>((target, from) => openRef.current(target, from), []);
  const sections = useMemo(() => (overview ? <Sections overview={overview} open={open} busy={busy} /> : null), [overview, open, busy]);

  let body;
  if (failure && overview === undefined) {
    body = <ErrorAlert error={failure} />;
  } else if (overview === undefined) {
    // Decorative: the page owns the ONE polite "Loading…" live region below.
    body = (
      <Paper withBorder p="md" aria-busy aria-label={t("home.loadingLabel")} role="group">
        <Stack gap="xs" aria-hidden>
          <Skeleton height={28} width="40%" />
          <Skeleton height={180} />
        </Stack>
      </Paper>
    );
  } else if (nothingDerived(overview)) {
    body = (
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack align="center" gap="sm">
          <EmptyState icon={IconDatabaseOff} label={admin ? t("home.emptyAdmin") : t("home.empty")} />
          {admin && (
            <Group gap="md" justify="center">
              <Anchor component={RouterLink} to={dataSourcesPath} size="sm">
                {t("home.emptyAdminLink")}
              </Anchor>
              <Anchor component={RouterLink} to={metricsSettingsPath} size="sm">
                {t("home.emptyAdminSettingsLink")}
              </Anchor>
            </Group>
          )}
        </Stack>
      </Box>
    );
  } else {
    body = (
      <HomeWindowDays.Provider value={windowDays(overview.window)}>
        {query.error && <ErrorAlert error={query.error} />}
        <HomeFreshness overview={overview} />
        {overview.teams.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t("home.noTeams")}
          </Text>
        ) : (
          sections
        )}
        <HomeDrillDrawer overview={overview} opened={drill.target !== null} target={drill.shown} onClose={drill.close} />
      </HomeWindowDays.Provider>
    );
  }

  const printed = overview
    ? [
        { label: t("reports.filters.period"), value: `${overview.window.from} – ${overview.window.to}` },
        { label: t("home.print.previous"), value: `${overview.previousWindow.from} – ${overview.previousWindow.to}` },
        { label: t("home.print.teams"), value: overview.teams.map((team) => team.name).join(", ") },
      ]
    : [];
  return (
    <Stack gap="md">
      <ReportPageHeader
        title={t("home.title")}
        description={t("home.intro", { days: overview ? windowDays(overview.window) : 90 })}
        lines={printed}
        toolbar={
          teams &&
          ids && (
            <HomeTeamPicker
              teams={teams}
              value={ids}
              picked={livePick(stored, teams) !== null}
              onChange={(next) => setStored(next.length === 0 ? null : next)}
              onReset={() => setStored(null)}
            />
          )
        }
      />
      {/* ONE polite live region for the whole page. */}
      <VisuallyHidden role="status">{overview === undefined && !failure ? t("common.loading") : ""}</VisuallyHidden>
      {body}
    </Stack>
  );
}

/** The sections, in order (memoised by the page on the overview); the Code section only while a GitLab source is active. */
function Sections({ overview, open, busy }: { overview: HomeOverview; open: OpenDrill; busy: boolean }) {
  const { t } = useTranslation();
  return (
    <Stack gap="md" role="group" aria-label={t("home.sectionsLabel")} aria-busy={busy}>
      <HomeSprintSection overview={overview} open={open} />
      <HomeGranularitySection overview={overview} open={open} />
      <HomeAccuracySection overview={overview} open={open} />
      <HomeWipSection overview={overview} open={open} kind="epics" />
      <HomeWipSection overview={overview} open={open} kind="tasks" />
      <HomeThroughputSection overview={overview} open={open} />
      {hasCodeSection(overview) && <HomeCodeSection overview={overview} open={open} />}
      <HomeQualitySection overview={overview} open={open} />
    </Stack>
  );
}
