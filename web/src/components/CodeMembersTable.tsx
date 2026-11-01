import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { CodeActivityGroup, CodeActivityReport, ReportFilters } from "../api/reports";
import ReportGroupsTable, { type GroupColumn } from "./ReportGroupsTable";

type ColumnKey = "commits" | "linesAdded" | "linesDeleted" | "mrsMerged" | "mrsOpened" | "openMrs" | "reviewsGiven" | "approvalsGiven" | "comments";

/**
 * The members' volumes over the period — commits, lines, merge requests, reviews, approvals, comments. **NEUTRAL context (A50)**: the columns
 * carry no tint, icon, rank or cue, the rows keep the server's order (the roster, never sorted by volume) and the caption says these are
 * volumes of work, not performance, and that a merge request's size and a commit's lines are different "lines". A member with nothing in the
 * period stays as a row of zeros, so the roster is visible. `teamId=0` (the work of identities no one has linked) comes with its own labels
 * and no member to narrow to. At the USER level the rows are the picked members, plain text: there is nothing further to narrow to.
 */
export default function CodeMembersTable({ report, filters }: { report: CodeActivityReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  const column = (key: ColumnKey, render: (group: CodeActivityGroup) => ReactNode): GroupColumn<CodeActivityGroup> => ({
    key,
    header: t(`reports.codeActivity.members.column.${key}`),
    render,
  });
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
    </Stack>
  );
}
