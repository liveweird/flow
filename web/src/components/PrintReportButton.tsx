import { useTranslation } from "react-i18next";
import { Button } from "@mantine/core";
import { IconPrinter } from "@tabler/icons-react";

/**
 * "Print / save as PDF" for a report: the browser's own print dialog (vector text and SVG charts, no dependency, CSP-safe), styled
 * for paper by `print.css`. The button is not itself printed (`data-print="hide"`).
 */
export default function PrintReportButton() {
  const { t } = useTranslation();
  return (
    <Button
      variant="default"
      size="xs"
      leftSection={<IconPrinter size={16} />}
      data-print="hide"
      onClick={() => window.print()}
    >
      {t("reports.print.button")}
    </Button>
  );
}
