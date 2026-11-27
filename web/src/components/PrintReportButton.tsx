import { useTranslation } from "react-i18next";
import { Box, Button, Group } from "@mantine/core";
import { IconPrinter } from "@tabler/icons-react";

/**
 * "Print / save as PDF" for a report: the browser's own print dialog (vector text and SVG charts, no dependency, CSP-safe), styled
 * for paper by `print.css`. The button is not itself printed (`data-print="hide"`).
 */
export default function PrintReportButton() {
  const { t } = useTranslation();
  const label = t("reports.print.button");
  return (
    <Button
      variant="default"
      size="xs"
      px={{ base: 6, sm: "xs" }}
      aria-label={label}
      data-print="hide"
      onClick={() => window.print()}
    >
      <Group component="span" gap={6} wrap="nowrap">
        <IconPrinter size={16} aria-hidden />
        <Box component="span" visibleFrom="sm">
          {label}
        </Box>
      </Group>
    </Button>
  );
}
