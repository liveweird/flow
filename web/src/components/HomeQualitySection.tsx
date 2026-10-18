import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import type { HomeDrillView, HomeOverview, HomeTeam, HomeFigure } from "../api/home";
import { CHART_COLORS } from "../utils/chartColors";
import { overviewLink, sectionAnchor, teamFigureRows, windowFilter, type OpenDrill } from "../utils/homeOverview";
import { dataQualityPath } from "../utils/reportLinks";
import HomeTeamBars from "./HomeTeamBars";
import HomeTile from "./HomeTile";

const ANCHOR = sectionAnchor("quality");

type FindingKey = "tasksNoEstimate" | "tasksUnassigned" | "tasksEstimatedLate" | "epicsNoEstimate" | "epicsNoDates" | "epicsEstimateGap";

interface Finding {
  key: FindingKey;
  view: HomeDrillView;
  pick: (team: HomeTeam) => HomeFigure;
}

const TASK_FINDINGS: readonly Finding[] = [
  { key: "tasksNoEstimate", view: "QUALITY_TASKS_NO_ESTIMATE", pick: (team) => team.quality.tasksNoEstimate },
  { key: "tasksUnassigned", view: "QUALITY_TASKS_UNASSIGNED", pick: (team) => team.quality.tasksUnassigned },
  { key: "tasksEstimatedLate", view: "QUALITY_TASKS_ESTIMATED_LATE", pick: (team) => team.quality.tasksEstimatedLate },
];

const EPIC_FINDINGS: readonly Finding[] = [
  { key: "epicsNoEstimate", view: "QUALITY_EPICS_NO_ESTIMATE", pick: (team) => team.quality.epicsNoEstimate },
  { key: "epicsNoDates", view: "QUALITY_EPICS_NO_DATES", pick: (team) => team.quality.epicsNoDates },
  { key: "epicsEstimateGap", view: "QUALITY_EPICS_ESTIMATE_GAP", pick: (team) => team.quality.epicsEstimateGap },
];

/**
 * Section 7 — what is missing from the data, per team: six counts of findings, orange (the soft-finding colour). The three on tasks
 * count the tasks done in the window and carry a trend (fewer is better); the three on epics count the Data quality report's own
 * population (open now or done in the window) and have none. The note under them counts the work that belongs to no listed team and
 * links to the Data quality report.
 */
export default function HomeQualitySection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const period = windowFilter(overview);
  const dataQuality = overviewLink(dataQualityPath, period);
  const { unassigned } = overview;
  const chart = (finding: Finding, withTrend: boolean) => {
    const title = t(`home.quality.${finding.key}`, { percent: overview.epicEstimateGapTolerancePercent });
    return (
      <HomeTeamBars
        key={finding.key}
        title={title}
        rows={teamFigureRows(overview.teams, finding.view, finding.pick)}
        color={CHART_COLORS.finding}
        format={String}
        integer
        valueHeader={title}
        valueIsItems
        withTrend={withTrend}
        anchor={ANCHOR}
        onOpen={open}
        headingOrder={5}
      />
    );
  };
  return (
    <HomeTile
      title={t("home.quality.title")}
      to={dataQuality}
      caption={t("home.quality.caption", {
        from: overview.window.from,
        to: overview.window.to,
        percent: overview.epicEstimateGapTolerancePercent,
      })}
      drillAnchor={ANCHOR}
    >
      <Stack gap="lg">
        <Stack gap="xs">
          <Title order={4} size="h5">
            {t("home.quality.tasksGroup")}
          </Title>
          <SimpleGrid cols={{ base: 1, md: 3 }} spacing="lg">
            {TASK_FINDINGS.map((finding) => chart(finding, true))}
          </SimpleGrid>
        </Stack>
        <Stack gap="xs">
          <Title order={4} size="h5">
            {t("home.quality.epicsGroup")}
          </Title>
          <SimpleGrid cols={{ base: 1, md: 3 }} spacing="lg">
            {EPIC_FINDINGS.map((finding) => chart(finding, false))}
          </SimpleGrid>
        </Stack>
        {unassigned.doneTasks + unassigned.openTasks + unassigned.epics > 0 && (
          <Text size="xs" c="dimmed">
            {t("home.quality.unassigned", {
              done: t("home.count.doneTasks", { count: unassigned.doneTasks }),
              open: t("home.count.openTasks", { count: unassigned.openTasks }),
              epics: t("home.count.epics", { count: unassigned.epics }),
            })}{" "}
            <Anchor component={RouterLink} to={dataQuality} size="xs" underline="always">
              {t("home.quality.unassignedLink")}
            </Anchor>
          </Text>
        )}
      </Stack>
    </HomeTile>
  );
}
