import { useTranslation } from "react-i18next";
import { Stack, Table, Text } from "@mantine/core";
import type { HomeDrillView, HomeOverview, HomeTeam, HomeFigure } from "../api/home";
import classes from "../theme.module.css";
import { overviewLink, sectionAnchor, teamFigureRows, windowFilter, type HomeFigureRow, type OpenDrill } from "../utils/homeOverview";
import { dataQualityPath } from "../utils/reportLinks";
import DrillFigure from "./DrillFigure";
import HomeTile from "./HomeTile";
import HomeTrendMark from "./HomeTrendMark";
import ScrollRegion from "./ScrollRegion";

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
 * Section 7 — what is missing from the data, per team: ONE table of counts (a row per team, a column per finding, the three on tasks
 * under one group header and the three on epics under another). The three on tasks count the tasks done in the window and carry a
 * trend (fewer is better; the verdict is a word beside the arrow); the three on epics count the Data quality report's own population
 * (open now or done in the window) and have none. A non-zero count is the button that opens the items behind it, a zero is dimmed
 * plain text; the table is its own accessible table. The note under it counts the work that belongs to no listed team and links to
 * the Data quality report.
 */
export default function HomeQualitySection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  // Data quality is read for one team: the link opens on the first picked team, one that is in the table.
  const dataQuality = overviewLink(dataQualityPath, { ...windowFilter(overview), teamId: overview.teams[0]?.teamId });
  const { unassigned } = overview;
  const columns = [...TASK_FINDINGS, ...EPIC_FINDINGS].map((finding, index) => ({
    finding,
    title: t(`home.quality.${finding.key}`, { percent: overview.epicEstimateGapTolerancePercent }),
    rows: teamFigureRows(overview.teams, finding.view, finding.pick),
    withTrend: index < TASK_FINDINGS.length,
  }));
  const cell = (row: HomeFigureRow, title: string, withTrend: boolean) => {
    const previous = row.previous?.value;
    // Nothing to say: none now and none before.
    const showTrend = withTrend && !(row.count === 0 && previous === 0);
    return (
      <Table.Td key={row.key} ta="right">
        <Text component="span" className={row.count > 0 ? classes.findingCount : undefined} c={row.count > 0 ? undefined : "dimmed"}>
          <DrillFigure
            label={t("reports.drill.figureAria", { figure: String(row.count), column: title, name: row.team })}
            count={row.count}
            onOpen={(trigger) => open({ kind: "home", view: row.view, teamId: row.teamId }, { trigger, anchor: ANCHOR })}
          >
            {row.count}
          </DrillFigure>
        </Text>
        {showTrend && (
          <Text component="span" size="xs">
            <HomeTrendMark trend={row.trend} />
            {previous != null && (
              <Text component="span" size="xs" c="dimmed">
                {" "}
                {t("home.throughput.previousTotal", { value: previous })}
              </Text>
            )}
          </Text>
        )}
      </Table.Td>
    );
  };
  const label = t("home.quality.tableAria");
  return (
    <HomeTile
      title={t("home.quality.title")}
      to={dataQuality}
      caption={t("home.quality.caption", {
        from: overview.window.from,
        to: overview.window.to,
        previousFrom: overview.previousWindow.from,
        previousTo: overview.previousWindow.to,
        percent: overview.epicEstimateGapTolerancePercent,
      })}
      drillAnchor={ANCHOR}
    >
      <Stack gap="sm">
        <ScrollRegion label={label} minWidth={640}>
          <Table aria-label={label} verticalSpacing="xs" className={classes.findingTable}>
            {/* The column groups the two group headers (scope="colgroup") span: the team column, three task findings, three epic findings. */}
            <colgroup span={1} />
            <colgroup span={TASK_FINDINGS.length} />
            <colgroup span={EPIC_FINDINGS.length} />
            <Table.Thead>
              <Table.Tr>
                <Table.Th rowSpan={2} scope="col">
                  {t("home.table.team")}
                </Table.Th>
                <Table.Th colSpan={TASK_FINDINGS.length} scope="colgroup" ta="center">
                  {t("home.quality.tasksGroup")}
                </Table.Th>
                <Table.Th colSpan={EPIC_FINDINGS.length} scope="colgroup" ta="center">
                  {t("home.quality.epicsGroup")}
                </Table.Th>
              </Table.Tr>
              <Table.Tr>
                {columns.map(({ finding, title }) => (
                  <Table.Th key={finding.key} scope="col" ta="right">
                    {title}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {overview.teams.map((team, index) => (
                <Table.Tr key={team.teamId}>
                  <Table.Th scope="row" fw={400} ta="left">
                    {team.name}
                  </Table.Th>
                  {columns.map(({ title, rows, withTrend }) => cell(rows[index], title, withTrend))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollRegion>
        {unassigned.doneTasks + unassigned.openTasks + unassigned.epics > 0 && (
          <Text size="xs" c="dimmed">
            {t("home.quality.unassigned", {
              done: t("home.count.doneTasks", { count: unassigned.doneTasks }),
              open: t("home.count.openTasks", { count: unassigned.openTasks }),
              epics: t("home.count.epics", { count: unassigned.epics }),
            })}
          </Text>
        )}
      </Stack>
    </HomeTile>
  );
}
