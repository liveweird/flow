import { useTranslation } from "react-i18next";
import { Box, Group, Stack, Text, Title } from "@mantine/core";
import type { AdjustmentFigures } from "../api/reports";
import { formatPercent, formatSignedPercent } from "../utils/reportFormat";
import DistributionPanel from "./DistributionPanel";
import ExcludedList from "./ExcludedList";
import MinSampleNotice from "./MinSampleNotice";

/**
 * One item kind's adjustment figures (tasks or epics): how many started, how many changed after
 * start (with the share, withheld below the minimum sample), how many were estimated late, and the
 * fractional change start → done as a distribution with its counted exclusions.
 */
export default function AdjustmentsBlock({
  title,
  figures,
  minSampleSize,
}: {
  title: string;
  figures: AdjustmentFigures;
  minSampleSize: number;
}) {
  const { t } = useTranslation();
  const stats: [string, string][] = [
    [t("reports.adjustments.started"), String(figures.started)],
    [t("reports.adjustments.changed"), String(figures.changedAfterStart)],
    [t("reports.adjustments.late"), String(figures.estimatedLate)],
  ];
  return (
    <Stack gap="md">
      <Title order={4} size="h5">
        {title}
      </Title>
      <Group gap="lg" align="flex-start" role="group" aria-label={title}>
        {stats.map(([label, value]) => (
          <Box key={label}>
            <Text size="xs" c="dimmed">
              {label}
            </Text>
            <Text fw={600}>{value}</Text>
          </Box>
        ))}
        <Box>
          <Text size="xs" c="dimmed">
            {t("reports.adjustments.share")}
          </Text>
          {figures.share === null ? (
            <MinSampleNotice n={figures.started} minSampleSize={minSampleSize} subject="share" />
          ) : (
            <Text fw={600}>{formatPercent(figures.share)}</Text>
          )}
        </Box>
      </Group>
      <Text size="xs" c="dimmed">
        {t("reports.adjustments.lateNote")}
      </Text>
      <DistributionPanel
        title={t("reports.adjustments.changeTitle")}
        caption={t("reports.adjustments.changeHint")}
        distribution={figures.changeDistribution}
        minSampleSize={minSampleSize}
        format={formatSignedPercent}
        axisLabel={t("reports.adjustments.axis")}
      />
      <ExcludedList
        population={figures.changeExcluded.population}
        measured={figures.changeDistribution.n}
        items={[
          { label: t("reports.adjustments.excluded.estimatedLate"), count: figures.changeExcluded.estimatedLate },
          { label: t("reports.adjustments.excluded.unestimated"), count: figures.changeExcluded.unestimated },
        ]}
      />
    </Stack>
  );
}
