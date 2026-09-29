import { useQuery } from "@tanstack/react-query";
import { Anchor, Box, Grid, Group, Stack, VisuallyHidden } from "@mantine/core";
import { IconDatabaseOff } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  getAgingWipReport,
  getCycleTimeReport,
  getDataQualityReport,
  getReportFilters,
  getSprintConsistencyReport,
} from "../api/reports";
import { useAdmin } from "../auth";
import EmptyState from "../components/EmptyState";
import HomeCycleTimeTile from "../components/HomeCycleTimeTile";
import HomeQualityTile from "../components/HomeQualityTile";
import HomeVelocityTile from "../components/HomeVelocityTile";
import HomeWipTile from "../components/HomeWipTile";
import PageHeader from "../components/PageHeader";
import { dataSourcesPath, metricsSettingsPath } from "../utils/dataSourceLinks";
import { LAST_SPRINT_FILTER, nothingDerived, overviewLink, overviewPeriod } from "../utils/homeOverview";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import {
  agingWipPath,
  cycleTimePath,
  dataQualityPath,
  sprintConsistencyPath,
  throughputPath,
  velocityPath,
} from "../utils/reportLinks";

// The overview is a glance, not a workbench: a report changes slowly (DERIVE runs on a schedule), so a
// minute of freshness is plenty and a return visit to Home does not refetch four reports.
const STALE_MS = 60_000;

/**
 * The landing page (`/`) — the unit overview (plan amendment A9): a compact dashboard of the WHOLE
 * unit (never the remembered team), every tile reading a UNIT-level report endpoint over one fixed
 * default period, and linking into its full report. FIVE requests, no aggregator: the shared reference data
 * (`reports/filters`, only its time zone is read), sprint
 * consistency (`lastSprints=1`: each team's last closed sprint — initial, final and delivered off one
 * row), cycle time (the server's default trailing 90 days, weekly trend), aging WIP (as of now) and
 * data quality (the default 90 days). Every signed-in user sees it (D12). Each tile triages its own
 * load/error, so one failing report leaves the rest; when every answer says nothing has been derived
 * yet (and none failed), the page shows the empty state instead of a grid of empty tiles.
 */
export default function Home() {
  const { t } = useTranslation();
  const admin = useAdmin();

  const sprints = useQuery({ queryKey: ["home", "sprint-consistency"], queryFn: () => getSprintConsistencyReport(LAST_SPRINT_FILTER), staleTime: STALE_MS });
  const cycleTime = useQuery({ queryKey: ["home", "cycle-time"], queryFn: () => getCycleTimeReport({}), staleTime: STALE_MS });
  const agingWip = useQuery({ queryKey: ["home", "aging-wip"], queryFn: () => getAgingWipReport({}), staleTime: STALE_MS });
  const dataQuality = useQuery({ queryKey: ["home", "data-quality"], queryFn: () => getDataQualityReport({}), staleTime: STALE_MS });

  // The reference data every report page shares (same key, so opening a report reuses it): only its time zone is read
  // here, for the closing days and the fallback link period. Nothing waits on it and its failure shows nowhere.
  const filters = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: STALE_MS });
  const timeZone = filters.data?.timeZone;

  const queries = [sprints, cycleTime, agingWip, dataQuality];
  const anyPending = queries.some((query) => query.isPending);
  const empty = nothingDerived(
    queries.map((query) => query.data?.meta),
    anyPending,
    queries.some((query) => query.isError),
  );
  // Every link carries a period (a bare report URL would start on the remembered team): the one the two
  // from/to reports resolved, else the same trailing 90 days computed locally (in the configured zone once known).
  const period = overviewPeriod(timeZone, cycleTime.data?.meta, dataQuality.data?.meta);

  return (
    <Stack gap="md">
      <PageHeader title={t("home.title")} description={t("home.intro")} />
      {/* ONE polite live region for the whole page: five loading tiles must not announce five times. */}
      <VisuallyHidden role="status">{anyPending ? t("common.loading") : ""}</VisuallyHidden>
      {empty ? (
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
      ) : (
        <Grid gap="md" role="group" aria-label={t("home.tilesLabel")}>
          <Grid.Col span={12}>
            <HomeVelocityTile
              query={sprints}
              timeZone={timeZone}
              to={overviewLink(velocityPath, LAST_SPRINT_FILTER)}
              links={[
                { to: overviewLink(throughputPath, LAST_SPRINT_FILTER), label: t("home.velocity.throughputLink") },
                { to: overviewLink(sprintConsistencyPath, LAST_SPRINT_FILTER), label: t("home.velocity.consistencyLink") },
              ]}
            />
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 7 }}>
            <HomeCycleTimeTile query={cycleTime} to={overviewLink(cycleTimePath, period)} />
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 5 }}>
            <Stack gap="md">
              <HomeWipTile query={agingWip} to={overviewLink(agingWipPath, period)} />
              <HomeQualityTile query={dataQuality} to={overviewLink(dataQualityPath, period)} />
            </Stack>
          </Grid.Col>
        </Grid>
      )}
    </Stack>
  );
}
