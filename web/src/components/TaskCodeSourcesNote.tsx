import { useTranslation } from "react-i18next";
import { Alert, List, Stack, Text } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import type { TaskCodeReport } from "../api/reports";
import { formatDate } from "../utils/formatDate";

/**
 * Where the merge requests stand, for Tasks and code, in two parts. (1) The sources' freshness (`sources[]`, dimmed lines): the last successful sync,
 * because the report's `meta` is the Jira data's (the done tasks). (2) A gray note about the done tasks the link between a task and its merge requests
 * cannot use (`quality`): they never started (no code lead time), have only unmerged merge requests, or carry an ambiguous key. Neither part is a warning:
 * both are facts about the selection, and a zero is not mentioned.
 */
export default function TaskCodeSourcesNote({ report, timeZone }: { report: TaskCodeReport; timeZone: string }) {
  const { t } = useTranslation();
  const { quality, sources } = report;
  const gaps = (
    [
      { key: "neverStarted", count: quality.neverStarted },
      { key: "onlyUnmergedMrs", count: quality.onlyUnmergedMrs },
      { key: "tasksWithAmbiguousKey", count: quality.tasksWithAmbiguousKey },
    ] as const
  ).filter((item) => item.count > 0);
  return (
    <Stack gap="xs">
      <Stack gap={2}>
        {sources.map((source) => (
          <Text key={source.connectionId} size="xs" c="dimmed">
            {source.lastSyncAt === undefined
              ? t("reports.codeActivity.sources.neverSynced", { name: source.name })
              : t("reports.codeActivity.sources.synced", { name: source.name, date: formatDate(source.lastSyncAt, "—", timeZone) })}
          </Text>
        ))}
        <Text size="xs" c="dimmed">
          {t("reports.taskCode.sources.metaHint")}
        </Text>
      </Stack>
      {gaps.length > 0 && (
        <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} title={t("reports.taskCode.quality.title")} role="note">
          <List size="sm" spacing={2}>
            {gaps.map((item) => (
              <List.Item key={item.key}>{t(`reports.taskCode.quality.${item.key}`, { n: item.count })}</List.Item>
            ))}
          </List>
        </Alert>
      )}
    </Stack>
  );
}
