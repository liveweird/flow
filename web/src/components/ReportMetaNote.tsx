import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { ReportMeta } from "../api/reports";
import { formatDate } from "../utils/formatDate";

/**
 * Dimmed lines under the filter bar: when the numbers were derived, and from which config; and, while a history horizon is set, since when
 * the reports show work (`meta.historyHorizon`, the first day that counts).
 */
export default function ReportMetaNote({ meta, timeZone }: { meta: ReportMeta; timeZone: string }) {
  const { t } = useTranslation();
  return (
    <Stack gap={2}>
      <Text size="xs" c="dimmed">
        {meta.derivedAt === null
          ? t("reports.meta.notDerived")
          : t("reports.meta.derived", { date: formatDate(meta.derivedAt, "—", timeZone), revision: meta.configRevision ?? "—" })}
      </Text>
      {meta.historyHorizon !== null && (
        <Text size="xs" c="dimmed">
          {t("reports.meta.historyHorizon", { from: meta.historyHorizon.from, count: meta.historyHorizon.months })}
        </Text>
      )}
    </Stack>
  );
}
