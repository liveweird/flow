import { useTranslation } from "react-i18next";
import { Alert, Anchor, List, Stack, Text } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import type { CodeActivityReport } from "../api/reports";
import { useAdmin } from "../auth";
import { formatDate } from "../utils/formatDate";

/**
 * Where the code figures stand, in two parts. (1) The sources' freshness (`sources[]`, dimmed lines): the last successful sync and what is
 * still to be read (the files of commits and the details of merge requests), because the report's `meta` is the Jira data's. (2) A gray note
 * about what the figures do NOT hold yet (`quality`): the identities still to attribute — an administrator decides them, and for one a link
 * to each source's Identities page — and the rows left out of a figure because their data is unknown or unread. Neither is a warning:
 * both are facts about the selection; a zero is not mentioned.
 */
export default function CodeSourcesNote({ report, timeZone }: { report: CodeActivityReport; timeZone: string }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const { quality, sources } = report;
  const unknown = (
    [
      { key: "commitsLinesUnknown", count: quality.commitsLinesUnknown },
      { key: "mrsSizeUnknown", count: quality.mrsSizeUnknown },
      { key: "mrsDetailsNotRead", count: quality.mrsDetailsNotRead },
      { key: "eventsUndated", count: quality.eventsUndated },
    ] as const
  ).filter((item) => item.count > 0);
  const unlinked = quality.unlinkedIdentities > 0;
  return (
    <Stack gap="xs">
      <Stack gap={2}>
        {sources.map((source) => (
          <Text key={source.connectionId} size="xs" c="dimmed">
            {source.lastSyncAt === undefined
              ? t("reports.codeActivity.sources.neverSynced", { name: source.name })
              : t("reports.codeActivity.sources.synced", { name: source.name, date: formatDate(source.lastSyncAt, "—", timeZone) })}
            {source.commitFilesPending > 0 && ` ${t("reports.codeActivity.sources.filesPending", { n: source.commitFilesPending })}`}
            {source.mrDetailsPending > 0 && ` ${t("reports.codeActivity.sources.detailsPending", { n: source.mrDetailsPending })}`}
          </Text>
        ))}
        <Text size="xs" c="dimmed">
          {t("reports.codeActivity.sources.metaHint")}
        </Text>
      </Stack>
      {(unlinked || unknown.length > 0) && (
        <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} title={t("reports.codeActivity.quality.title")} role="note">
          <Stack gap="xs">
            {unlinked && (
              <Text size="sm">
                {t("reports.codeActivity.quality.unlinked", {
                  n: quality.unlinkedIdentities,
                  commits: quality.unlinkedCommits,
                  mrs: quality.unlinkedMergeRequests,
                  events: quality.unlinkedEvents,
                })}
              </Text>
            )}
            {unlinked && admin && (
              <List size="sm" listStyleType="none" spacing={2}>
                {sources.map((source) => (
                  <List.Item key={source.connectionId}>
                    <Anchor component={RouterLink} to={`/data-sources/${source.connectionId}/identities`} size="sm">
                      {t("reports.codeActivity.quality.link", { name: source.name })}
                    </Anchor>
                  </List.Item>
                ))}
              </List>
            )}
            {unknown.length > 0 && (
              <List size="sm" spacing={2}>
                {unknown.map((item) => (
                  <List.Item key={item.key}>{t(`reports.codeActivity.quality.${item.key}`, { n: item.count })}</List.Item>
                ))}
              </List>
            )}
          </Stack>
        </Alert>
      )}
    </Stack>
  );
}
