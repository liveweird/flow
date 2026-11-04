import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { HomeOverview } from "../api/home";
import { formatDateTime } from "../utils/formatDate";

/**
 * Section 0 — how fresh the numbers are, as one dimmed line on top: each active connection's last successful sync and last
 * derive (read in the configured zone; "never" where a connection has not completed one), and the two windows every figure on the
 * page is read over (the trailing 90 days and the 90 before, which the trend arrows compare with), plus, while a history horizon is set, since when the reports show work.
 */
export default function HomeFreshness({ overview }: { overview: HomeOverview }) {
  const { t } = useTranslation();
  const never = t("home.freshness.never");
  const at = (millis: number | null) => (millis === null ? never : formatDateTime(millis, overview.timeZone));
  return (
    <Stack gap={2} aria-label={t("home.freshness.label")} role="group">
      <Text size="sm" c="dimmed">
        {overview.freshness.length === 0
          ? t("home.freshness.none")
          : overview.freshness
              .map((connection) => t("home.freshness.connection", { name: connection.name, sync: at(connection.lastSyncAt), derive: at(connection.lastDeriveAt) }))
              .join(" · ")}
      </Text>
      {overview.code.sources.length > 0 && (
        <Text size="sm" c="dimmed">
          {overview.code.sources
            .map((source) =>
              t("home.freshness.codeSource", { name: source.name, sync: source.lastSyncAt === undefined ? never : formatDateTime(source.lastSyncAt, overview.timeZone) }),
            )
            .join(" · ")}
        </Text>
      )}
      <Text size="xs" c="dimmed">
        {t("home.freshness.windows", {
          from: overview.window.from,
          to: overview.window.to,
          previousFrom: overview.previousWindow.from,
          previousTo: overview.previousWindow.to,
          zone: overview.timeZone,
        })}
      </Text>
      {overview.meta.historyHorizon !== null && (
        <Text size="xs" c="dimmed">
          {t("reports.meta.historyHorizon", { from: overview.meta.historyHorizon.from, count: overview.meta.historyHorizon.months })}
        </Text>
      )}
    </Stack>
  );
}
