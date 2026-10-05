import { useId, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Box, Button, Stack } from "@mantine/core";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";

/**
 * A daily series can run to a thousand rows, so its full table sits behind a disclosure button
 * (`aria-expanded`/`aria-controls`) instead of pushing the page down; the body is not rendered
 * while closed. The children are the table itself — scroll and sticky header included. The button words
 * default to the daily-series ones; a table of something else (the Deep dive's visible columns) names itself.
 */
export default function DailyTableDisclosure({
  children,
  showLabel,
  hideLabel,
}: {
  children: ReactNode;
  showLabel?: string;
  hideLabel?: string;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  return (
    <Stack gap="xs" align="flex-start">
      <Button
        variant="subtle"
        size="compact-sm"
        leftSection={open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((value) => !value)}
      >
        {open ? (hideLabel ?? t("reports.daily.hide")) : (showLabel ?? t("reports.daily.show"))}
      </Button>
      <Box id={bodyId} w="100%" hidden={!open}>
        {open && children}
      </Box>
    </Stack>
  );
}
