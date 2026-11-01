import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { CodeActivityGroup, CodeActivityReport, ReportFilters } from "../api/reports";
import { MEMBER_CELLS, memberName, type CodeDrill } from "../utils/codeActivityDrill";
import { drillFigureColumn } from "../utils/drillFigure";
import ReportGroupsTable, { type GroupColumn } from "./ReportGroupsTable";

type ColumnKey = "commits" | "linesAdded" | "linesDeleted" | "mrsMerged" | "mrsOpened" | "openMrs" | "reviewsGiven" | "approvalsGiven" | "comments";

/**
 * The members' volumes over the period — commits, lines, merge requests, reviews, approvals, comments. **NEUTRAL context (A50)**: the columns
 * carry no tint, icon, rank or cue, the rows keep the server's order (alphabetical by label, `orgGroups`; never sorted by volume) and the caption says these are
 * volumes of work, not performance, and that a merge request's size and a commit's lines are different "lines". A member with nothing in the
 * period stays as a row of zeros, so the roster is visible. `teamId=0` (the work of identities no one has linked) comes with its own labels
 * and no member to narrow to. At the USER level the rows are the picked members, plain text: there is nothing further to narrow to.
 * With a `drill`, the counts that stand for commits or merge requests (the cells of `MEMBER_CELLS`) are buttons to the items behind them: the same
 * underlined figure as every table's drill, no colour or rank added, and a member with no linked account (a `teamId=0` row) has none to open.
 */
export default function CodeMembersTable({ report, filters, drill }: { report: CodeActivityReport; filters: ReportFilters; drill?: CodeDrill }) {
  const { t } = useTranslation();
  const roster = filters.teams.flatMap((team) => team.members);
  const column = (key: ColumnKey, render: (group: CodeActivityGroup) => ReactNode): GroupColumn<CodeActivityGroup> => {
    const base: GroupColumn<CodeActivityGroup> = { key, header: t(`reports.codeActivity.members.column.${key}`), render };
    const cell = key in MEMBER_CELLS ? MEMBER_CELLS[key as keyof typeof MEMBER_CELLS] : undefined;
    if (cell === undefined || drill === undefined) return base;
    return drillFigureColumn(base, {
      label: (group, figure, header) => t("reports.drill.figureAria", { figure, column: header, name: memberName(report, roster, group.accountId ?? "") }),
      // A row with no linked account has no one to narrow to: its figure stays text.
      count: (group) => (group.accountId ? cell.count(group) : 0),
      disabled: drill.disabled,
      onOpen: (group, trigger) => {
        if (group.accountId) drill.open({ kind: "code", view: cell.view, segmentAccountId: group.accountId }, { trigger });
      },
    });
  };
  const columns: GroupColumn<CodeActivityGroup>[] = [
    column("commits", (group) => group.commits),
    column("linesAdded", (group) => (
      <>
        {group.linesAdded}
        {group.commitsLinesUnknown > 0 && (
          <Text span size="xs" c="dimmed">
            {" "}
            ({t("reports.codeActivity.members.linesUnknown", { n: group.commitsLinesUnknown })})
          </Text>
        )}
      </>
    )),
    column("linesDeleted", (group) => group.linesDeleted),
    column("mrsMerged", (group) => group.mrsMerged),
    column("mrsOpened", (group) => group.mrsOpened),
    column("openMrs", (group) => group.openMrs),
    column("reviewsGiven", (group) => group.reviewsGiven),
    column("approvalsGiven", (group) => group.approvalsGiven),
    column("comments", (group) => group.comments),
  ];
  return (
    <Stack gap="xs">
      <ReportGroupsTable
        level="TEAM"
        filters={filters}
        groups={report.groups}
        columns={columns}
        drill={report.meta.level !== "USER"}
        wording={{ title: t("reports.codeActivity.members.title"), unassigned: t("reports.codeActivity.members.unlabelled") }}
      />
      <Text size="xs" c="dimmed">
        {t("reports.codeActivity.members.caption")}
      </Text>
      {drill && (
        <Text size="xs" c="dimmed">
          {t("reports.codeActivity.members.hintRows")}
        </Text>
      )}
    </Stack>
  );
}
