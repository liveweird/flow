import { useTranslation } from "react-i18next";
import { Badge, Group, Stack, Text } from "@mantine/core";
import type { AgingWipReport } from "../api/reports";
import { agingSummary, type PastThreshold } from "../utils/homeOverview";
import HomeTile from "./HomeTile";

const MISSING = "—";

function Stat({ label, value, badge }: { label: string; value: string; badge?: { color: "orange" | "red" } }) {
  return (
    <Stack gap={4} align="flex-start" role="group" aria-label={label}>
      {badge ? (
        // The band's word travels with its colour (light badge: the tint from the hue, the ink from the theme).
        <Badge color={badge.color} variant="light">
          {label}
        </Badge>
      ) : (
        <Text size="xs" c="dimmed" lh="22px">
          {label}
        </Text>
      )}
      <Text fz={28} fw={700} lh={1.2}>
        {value}
      </Text>
    </Stack>
  );
}

/**
 * Tile 3 — the tasks in progress right now and how many are past the aging thresholds (p85 orange,
 * p95 red by default). Read straight off `aging-wip` — the band is the server's (never recomputed from
 * the age); a window below the minimum sample has no thresholds, so the two counts are dashes and
 * the tile says why. The server lists at most 500 items: when it truncated, each figure is "at least".
 */
export default function HomeWipTile({
  query,
  to,
}: {
  query: { data?: AgingWipReport; isPending: boolean; error: unknown };
  to: string;
}) {
  const { t } = useTranslation();
  const report = query.data;
  const summary = report ? agingSummary(report) : undefined;
  const figure = (n: number) => (summary?.atLeast ? `${n}+` : String(n));
  const past = (threshold: PastThreshold | null, color: "orange" | "red") =>
    threshold && (
      <Stat
        label={t("home.wip.past", { percentile: threshold.percentile })}
        value={summary?.hidden ? MISSING : figure(threshold.count)}
        badge={{ color }}
      />
    );
  return (
    <HomeTile title={t("home.wip.title")} to={to} caption={t("home.wip.caption")} isPending={query.isPending} error={query.error}>
      {report && summary && (
        <Stack gap="sm">
          <Group gap="xl" role="group" aria-label={t("home.wip.statsLabel")}>
            <Stat label={t("home.wip.inProgress")} value={figure(summary.wip)} />
            {past(summary.pastOrange, "orange")}
            {past(summary.pastRed, "red")}
          </Group>
          {summary.wip === 0 && (
            <Text size="xs" c="dimmed">
              {t("home.wip.empty")}
            </Text>
          )}
          {summary.hidden && (
            <Text size="xs" c="dimmed">
              {t("home.wip.hidden", { n: report.thresholds.n, min: report.meta.minSampleSize })}
            </Text>
          )}
          {summary.atLeast && (
            <Text size="xs" c="dimmed">
              {t("home.wip.truncated")}
            </Text>
          )}
        </Stack>
      )}
    </HomeTile>
  );
}
