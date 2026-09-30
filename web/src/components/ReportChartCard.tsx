import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Box, Paper, Stack, Text, Title } from "@mantine/core";
import { IconChartBar } from "@tabler/icons-react";
import EmptyState from "./EmptyState";
import LoadingBlock from "./LoadingBlock";
import ErrorAlert from "./ErrorAlert";

/**
 * The shell every report block lives in: a title, an optional caption (the domain-view label, a
 * unit note), and the ONE load/empty/error triage — a red-light Alert (`loadErrorMessage`, never
 * `error.message`), a spinner for the first load, the "No data in this period" empty state, and,
 * while a filter change refetches over `keepPreviousData`, the previous body dimmed and marked
 * `aria-busy` instead of blanked.
 */
export default function ReportChartCard({
  title,
  caption,
  isPending,
  isRefreshing = false,
  error,
  empty,
  children,
}: {
  title: string;
  caption?: string;
  /** No data at all yet (first load). */
  isPending: boolean;
  /** Showing the previous filter's data while the new one loads. */
  isRefreshing?: boolean;
  error?: unknown;
  empty: boolean;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  let body: ReactNode;
  if (error) {
    body = <ErrorAlert error={error} />;
  } else if (isPending) {
    body = <LoadingBlock />;
  } else if (empty) {
    body = <EmptyState icon={IconChartBar} label={t("reports.empty")} />;
  } else {
    body = (
      <Box aria-busy={isRefreshing} opacity={isRefreshing ? 0.5 : 1}>
        {children}
      </Box>
    );
  }
  return (
    <Paper withBorder p="md">
      <Stack gap="sm">
        <Stack gap={0}>
          <Title order={3} size="h4">
            {title}
          </Title>
          {caption && (
            <Text size="xs" c="dimmed">
              {caption}
            </Text>
          )}
        </Stack>
        {body}
      </Stack>
    </Paper>
  );
}
