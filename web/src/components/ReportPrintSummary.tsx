import { useEffect, useState } from "react";
import { flushSync } from "react-dom";
import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import { formatDateTime } from "../utils/formatDate";
import type { PrintFilterLine } from "../utils/reportPrintSummary";

/**
 * The print-only header of a report: its name, the filter in words (the filter bar is hidden on paper) and the generation time in the
 * configured zone. Invisible on screen (`print.css` shows `data-print="only"` blocks only under `@media print`). The stamp is taken when
 * the print starts (`beforeprint` — the button and Ctrl/Cmd+P alike), not when the page was drawn.
 */
export default function ReportPrintSummary({
  title,
  lines,
  timeZone,
}: {
  title: string;
  lines: readonly PrintFilterLine[];
  timeZone: string;
}) {
  const { t } = useTranslation();
  const [generatedAt, setGeneratedAt] = useState(() => Date.now());

  useEffect(() => {
    const stamp = () => flushSync(() => setGeneratedAt(Date.now()));
    window.addEventListener("beforeprint", stamp);
    return () => window.removeEventListener("beforeprint", stamp);
  }, []);

  return (
    <div data-print="only">
      <Text fw={600} size="sm">
        {t("reports.print.heading", { report: title })}
      </Text>
      {lines.length > 0 && (
        <Text size="sm">
          {t("reports.print.filters")}: {lines.map((line) => `${line.label}: ${line.value}`).join(" · ")}
        </Text>
      )}
      <Text size="xs" c="dimmed">
        {t("reports.print.generated", { date: formatDateTime(generatedAt, timeZone), zone: timeZone })}
      </Text>
    </div>
  );
}
