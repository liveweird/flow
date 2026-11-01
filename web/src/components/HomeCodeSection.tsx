import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Stack, Table, Text } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import type { HomeFigure, HomeOverview, HomeTeam } from "../api/home";
import { useAdmin } from "../auth";
import classes from "../theme.module.css";
import { overviewLink, sectionAnchor, trendOf, windowFilter, type OpenDrill } from "../utils/homeOverview";
import { dataSourceIdentitiesPath } from "../utils/dataSourceLinks";
import { codeActivityPath } from "../utils/reportLinks";
import { formatDays, formatPercent } from "../utils/reportFormat";
import DrillFigure from "./DrillFigure";
import HomeTile from "./HomeTile";
import HomeTrendMark from "./HomeTrendMark";
import ScrollRegion from "./ScrollRegion";

const ANCHOR = sectionAnchor("code");

/** The columns of the figures (the team and the "no linked identities" row aside). */
const COLUMN_COUNT = 5;

/**
 * Section 7 — the code, per team: ONE table (a row per team) of Report 21's own figures over the same window, read from the connected
 * GitLab. Five mixed-unit columns do not fit small multiples, so there is no chart and no hidden twin: this table IS the accessible
 * one. The merged count, the open count and the merged-without-review count are drill buttons (the merge requests behind them, linked
 * to GitLab); the cycle and age medians have no drill of their own (their merge requests are the first two lists). Three figures
 * carry a trend (arrow plus the verdict as a word; the directions are in the caption in words, never a colour); the open ones are a
 * snapshot of now. A statistic under the minimum sample reads "Below the minimum sample", but the merged-without-review COUNT stays.
 * A team with no linked GitLab identity has no figures to show and says so (an administrator gets the way to link them). When the
 * code read exceeded the server's row budget the section keeps its title and says so instead of a table (the report has the detail).
 */
export default function HomeCodeSection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  // Code activity is read for one team: the link opens on the first picked team, one that is in the table.
  const report = overviewLink(codeActivityPath, { ...windowFilter(overview), teamId: overview.teams[0]?.teamId });
  const caption = t("home.code.caption", {
    from: overview.window.from,
    to: overview.window.to,
    previousFrom: overview.previousWindow.from,
    previousTo: overview.previousWindow.to,
  });
  if (overview.code.overBudget) {
    return (
      <HomeTile title={t("home.code.title")} to={report} caption={caption} drillAnchor={ANCHOR}>
        <Text size="sm" c="dimmed" role="note">
          {t("home.code.overBudget")}
        </Text>
      </HomeTile>
    );
  }
  const label = t("home.code.tableAria");
  const none = <Text component="span">—</Text>;

  /** What a trend was: "(was 3.1)", in the figure's own format. */
  const was = (value: number | null, format: (value: number) => string) =>
    value !== null && (
      <Text component="span" size="xs" c="dimmed">
        {" "}
        {t("home.throughput.previousTotal", { value: format(value) })}
      </Text>
    );
  /** A median: the value, "Below the minimum sample" under the minimum, a dash for nothing at all; with a trend where it has one. */
  const median = (figure: HomeFigure) => {
    if (figure.value === null) {
      return figure.n === 0 ? none : <Text component="span" size="sm" c="dimmed">{t("home.table.belowMinimum")}</Text>;
    }
    const previous = figure.previous;
    return (
      <>
        {formatDays(figure.value)}
        {previous !== null && (
          <Text component="span" size="xs">
            <HomeTrendMark trend={trendOf(previous)} />
            {was(previous.value, formatDays)}
          </Text>
        )}
      </>
    );
  };
  /** A count: the drill button (a zero is dimmed plain text), what sits beside it ([extra]) and its trend with what it was. */
  const count = (
    team: HomeTeam,
    value: number,
    figure: HomeFigure | null,
    column: string,
    view: "CODE_MERGED" | "CODE_OPEN" | "CODE_MERGED_WITHOUT_REVIEW",
    extra: ReactNode = null,
    format: (value: number) => string = String,
  ) => {
    const previous = figure?.previous ?? null;
    const showTrend = previous !== null && !(value === 0 && previous.value === 0);
    return (
      <>
        <Text component="span" c={value > 0 ? undefined : "dimmed"}>
          <DrillFigure
            label={t("reports.drill.figureAria", { figure: String(value), column, name: team.name })}
            count={value}
            onOpen={(trigger) => open({ kind: "home", view, teamId: team.teamId }, { trigger, anchor: ANCHOR })}
          >
            {value}
          </DrillFigure>
        </Text>
        {extra}
        {showTrend && previous !== null && (
          <Text component="span" size="xs">
            <HomeTrendMark trend={trendOf(previous)} />
            {was(previous.value, format)}
          </Text>
        )}
      </>
    );
  };
  const columns = {
    merged: t("home.code.merged"),
    cycle: t("home.code.cycle"),
    open: t("home.code.open"),
    age: t("home.code.age"),
    withoutReview: t("home.code.withoutReview"),
  };
  const row = (team: HomeTeam) => {
    const code = team.code;
    if (code === null) return null;
    if (code.linkedMembers === 0) {
      return (
        <Table.Td colSpan={COLUMN_COUNT}>
          <Text size="sm" c="dimmed">
            {t("home.code.noLinks")}
          </Text>
          {admin &&
            overview.code.sources.map((source) => (
              <Anchor key={source.connectionId} component={RouterLink} to={dataSourceIdentitiesPath(source.connectionId)} size="sm" display="block">
                {t("home.code.linkIdentities", { name: source.name })}
              </Anchor>
            ))}
        </Table.Td>
      );
    }
    const review = code.mergedWithoutReview;
    return (
      <>
        <Table.Td ta="right">{count(team, code.merged.n, code.merged, columns.merged, "CODE_MERGED")}</Table.Td>
        <Table.Td ta="right">{median(code.cycleP50)}</Table.Td>
        <Table.Td ta="right">
          {count(team, code.openCount.n, null, columns.open, "CODE_OPEN")}
          {code.openDrafts > 0 && (
            <Text component="span" size="xs" c="dimmed">
              {" "}
              {t("home.code.drafts", { count: code.openDrafts })}
            </Text>
          )}
        </Table.Td>
        <Table.Td ta="right">{median(code.openAgeP50)}</Table.Td>
        <Table.Td ta="right">
          {count(
            team,
            review.numerator ?? 0,
            review,
            columns.withoutReview,
            "CODE_MERGED_WITHOUT_REVIEW",
            review.n > 0 && (
              <Text component="span" size="xs" c="dimmed">
                {" "}
                {review.value === null
                  ? t("home.code.shareHidden", { n: review.n })
                  : t("home.code.share", { share: formatPercent(review.value), n: review.n })}
              </Text>
            ),
            formatPercent,
          )}
        </Table.Td>
      </>
    );
  };
  return (
    <HomeTile title={t("home.code.title")} to={report} caption={caption} drillAnchor={ANCHOR}>
      <Stack gap="sm">
        <ScrollRegion label={label} minWidth={640}>
          <Table aria-label={label} verticalSpacing="xs" className={classes.findingTable}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">{t("home.table.team")}</Table.Th>
                {Object.entries(columns).map(([key, title]) => (
                  <Table.Th key={key} scope="col" ta="right">
                    {title}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {overview.teams.map((team) => (
                <Table.Tr key={team.teamId}>
                  <Table.Th scope="row" fw={400} ta="left">
                    {team.name}
                  </Table.Th>
                  {row(team)}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollRegion>
      </Stack>
    </HomeTile>
  );
}
