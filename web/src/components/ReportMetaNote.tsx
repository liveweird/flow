import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { ReportMeta } from "../api/reports";
import { formatDate } from "../utils/formatDate";

/** One dimmed line under the filter bar: when the numbers were derived, and from which config. */
export default function ReportMetaNote({ meta, timeZone }: { meta: ReportMeta; timeZone: string }) {
  const { t } = useTranslation();
  return (
    <Text size="xs" c="dimmed">
      {meta.derivedAt === null
        ? t("reports.meta.notDerived")
        : t("reports.meta.derived", { date: formatDate(meta.derivedAt, "—", timeZone), revision: meta.configRevision })}
    </Text>
  );
}
