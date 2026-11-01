import { useTranslation } from "react-i18next";
import { Group, Stack, Text } from "@mantine/core";
import type { CodeActivityReport } from "../api/reports";
import { formatDays } from "../utils/reportFormat";
import KpiFigure from "./KpiFigure";
import ReportChartCard from "./ReportChartCard";

/**
 * The merge requests open NOW whose author belongs to the team now (not a figure of the period): the ones waiting for review as the headline,
 * the drafts counted apart (a draft is not waiting), and the waiting ones' age in working days — the median and the p85, withheld under the
 * minimum sample like every percentile. Lower is better; the caption says so in words.
 */
export default function CodeOpenTile({ report, isRefreshing }: { report: CodeActivityReport; isRefreshing: boolean }) {
  const { t } = useTranslation();
  const { open, meta } = report;
  const { age } = open;
  return (
    <ReportChartCard
      title={t("reports.codeActivity.open.title")}
      caption={t("reports.codeActivity.open.caption")}
      isPending={false}
      isRefreshing={isRefreshing}
      empty={false}
    >
      <Stack gap="sm">
        <Group gap="xl" align="flex-end" wrap="wrap">
          <Stack gap={2}>
            <Text size="xs" c="dimmed">
              {t("reports.codeActivity.open.waiting")}
            </Text>
            <KpiFigure>{open.count}</KpiFigure>
          </Stack>
          <Stack gap={2}>
            <Text size="xs" c="dimmed">
              {t("reports.codeActivity.open.drafts")}
            </Text>
            <Text fw={600}>{open.drafts}</Text>
          </Stack>
          {!age.hidden && age.p50 !== undefined && (
            <Group gap="lg" role="group" aria-label={t("reports.codeActivity.open.ageLabel")}>
              <Stack gap={2}>
                <Text size="xs" c="dimmed">
                  {t("reports.codeActivity.open.ageP50")}
                </Text>
                <Text fw={600}>{formatDays(age.p50)}</Text>
              </Stack>
              <Stack gap={2}>
                <Text size="xs" c="dimmed">
                  {t("reports.codeActivity.open.ageP85")}
                </Text>
                <Text fw={600}>{age.p85 === undefined ? "—" : formatDays(age.p85)}</Text>
              </Stack>
            </Group>
          )}
        </Group>
        {(age.hidden || age.p50 === undefined) && (
          <Text size="xs" c="dimmed">
            {t("reports.codeActivity.open.ageHidden", { min: meta.minSampleSize })}
          </Text>
        )}
        <Text size="xs" c="dimmed">
          {t("reports.codeActivity.open.hint")}
        </Text>
      </Stack>
    </ReportChartCard>
  );
}
