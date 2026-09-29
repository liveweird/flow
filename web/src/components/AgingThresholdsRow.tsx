import { useTranslation } from "react-i18next";
import { Group, Paper, Stack, Text, Title } from "@mantine/core";
import type { AgingThresholds } from "../api/reports";
import { formatDays } from "../utils/reportFormat";
import MinSampleNotice from "./MinSampleNotice";

/**
 * One kind's thresholds as a small tile row (p50 · p85 · p95 in working days, whichever the
 * configuration lists), with the size of the window they came from. Hidden thresholds (a window
 * below the minimum sample) are a gray note instead — and no item is banded by them.
 */
export default function AgingThresholdsRow({
  title,
  thresholds,
  minSampleSize,
}: {
  title: string;
  thresholds: AgingThresholds;
  minSampleSize: number;
}) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Title order={5} size="h6">
        {title}
      </Title>
      {thresholds.hidden ? (
        <MinSampleNotice n={thresholds.n} minSampleSize={minSampleSize} subject="thresholds" />
      ) : (
        <>
          <Group gap="sm" wrap="wrap" role="group" aria-label={title}>
            {thresholds.percentiles.map((entry) => (
              <Paper key={entry.percentile} withBorder p="sm" miw={96}>
                <Text size="xs" c="dimmed">{`p${entry.percentile}`}</Text>
                <Text fw={700} fz="xl" lh={1.2}>
                  {entry.workingDays == null ? "—" : formatDays(entry.workingDays)}
                </Text>
                <Text size="xs" c="dimmed">
                  {t("reports.agingWip.thresholdUnit")}
                </Text>
              </Paper>
            ))}
          </Group>
          <Text size="xs" c="dimmed">
            {t("reports.agingWip.basis", { count: thresholds.n })}
          </Text>
        </>
      )}
    </Stack>
  );
}
