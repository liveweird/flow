import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Group, Stack, Table, Text, VisuallyHidden } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import type { CycleTimeReport } from "../api/reports";
import { cycleTrendRows, trendHasPoints } from "../utils/cycleTimeReport";
import { formatDays } from "../utils/reportFormat";
import HomeTile from "./HomeTile";
import LoadingBlock from "./LoadingBlock";

// The trend chart (and with it recharts) rides its own lazy chunk — the Cycle time page's own chart.
const CycleTimeTrendChart = lazy(() => import("./CycleTimeTrendChart"));

const MISSING = "—";

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text fz={28} fw={700} lh={1.2}>
        {value}
      </Text>
    </Stack>
  );
}

/**
 * Tile 2 — the cycle-time median and p90 (working days) of the period's finished tasks, and their
 * weekly trend: the Cycle time page's own chart, smaller, with the trend's numbers as a visually hidden
 * table (the chart's text alternative). A week below the minimum sample is a gap, never a zero.
 */
export default function HomeCycleTimeTile({
  query,
  to,
}: {
  query: { data?: CycleTimeReport; isPending: boolean; error: unknown };
  to: string;
}) {
  const { t } = useTranslation();
  const report = query.data;
  const rows = report ? cycleTrendRows(report.trend, "WEEK") : [];
  return (
    <HomeTile
      title={t("home.cycleTime.title")}
      to={to}
      caption={
        report?.meta.from != null && report.meta.to != null
          ? t("home.cycleTime.captionDates", { from: report.meta.from, to: report.meta.to })
          : t("home.cycleTime.caption")
      }
      isPending={query.isPending}
      error={query.error}
    >
      {report &&
        (report.excluded.population === 0 ? (
          <Text size="sm" c="dimmed">
            {t("home.cycleTime.empty")}
          </Text>
        ) : (
          <Stack gap="md">
            <Group gap="xl" role="group" aria-label={t("home.cycleTime.statsLabel")}>
              <Stat label={t("reports.cycleTime.column.p50")} value={report.workingDays.p50 == null ? MISSING : formatDays(report.workingDays.p50)} />
              <Stat label={t("reports.cycleTime.column.p90")} value={report.workingDays.p90 == null ? MISSING : formatDays(report.workingDays.p90)} />
              <Stat label={t("reports.cycleTime.column.n")} value={String(report.workingDays.n)} />
            </Group>
            {report.workingDays.hidden && (
              <Text size="xs" c="dimmed">
                {t("home.cycleTime.hidden", { min: report.meta.minSampleSize })}
              </Text>
            )}
            {trendHasPoints(rows) ? (
              <>
                <Suspense fallback={<LoadingBlock />}>
                  <CycleTimeTrendChart rows={rows} height={200} />
                </Suspense>
                <VisuallyHidden>
                  <Table aria-label={t("reports.cycleTime.trendTableLabel")}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{t("reports.cycleTime.column.bucketWEEK")}</Table.Th>
                        <Table.Th>{t("reports.cycleTime.column.n")}</Table.Th>
                        <Table.Th>{t("reports.cycleTime.column.p50")}</Table.Th>
                        <Table.Th>{t("reports.cycleTime.column.p90")}</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {rows.map((row, index) => (
                        // Buckets are ordered and fixed per response; position is the identity.
                        <Table.Tr key={index}>
                          <Table.Td>{row.label}</Table.Td>
                          <Table.Td>{row.n}</Table.Td>
                          <Table.Td>{row.p50 === null ? MISSING : formatDays(row.p50)}</Table.Td>
                          <Table.Td>{row.p90 === null ? MISSING : formatDays(row.p90)}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </VisuallyHidden>
              </>
            ) : (
              <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
                {t("reports.cycleTime.trendAllHidden")}
              </Alert>
            )}
          </Stack>
        ))}
    </HomeTile>
  );
}
