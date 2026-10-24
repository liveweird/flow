import { useState, type ComponentType } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { Accordion, ActionIcon, Alert, AppShell, Group, ScrollArea, SegmentedControl, Stack, Text, Title } from "@mantine/core";
import { IconX } from "@tabler/icons-react";
import { getTelemetryCurrent, type TelemetryCurrent, type TelemetryRange } from "../api/telemetry";
import { isOneOf, useStoredState } from "../hooks/useStoredState";
import { formatDateTime } from "../utils/formatDate";
import { roleName } from "../utils/telemetryRole";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import TelemetryDeriveSection from "./TelemetryDeriveSection";
import TelemetryJvmSection from "./TelemetryJvmSection";
import TelemetryMemorySection from "./TelemetryMemorySection";
import TelemetryRequestsSection from "./TelemetryRequestsSection";
import TelemetryStorageSection from "./TelemetryStorageSection";
import TelemetrySyncSection from "./TelemetrySyncSection";

const SECTION_IDS = ["memory", "storage", "sync", "derive", "requests", "jvm"] as const;
type SectionId = (typeof SECTION_IDS)[number];
const RANGES = ["24h", "7d", "90d"] as const;

/** `current` is refetched every minute while the panel is open (the panel only exists while open), never in a background tab. */
const REFRESH_MS = 60_000;

const VIEWS: Record<SectionId, ComponentType<{ current: TelemetryCurrent; range: TelemetryRange; active: boolean }>> = {
  memory: TelemetryMemorySection,
  storage: TelemetryStorageSection,
  sync: TelemetrySyncSection,
  derive: TelemetryDeriveSection,
  requests: TelemetryRequestsSection,
  jvm: TelemetryJvmSection,
};

const isRange = isOneOf(RANGES);
const isSectionList = (value: unknown): value is SectionId[] =>
  Array.isArray(value) && value.every((entry) => SECTION_IDS.includes(entry as SectionId));

/**
 * The telemetry panel (ADMIN only, the shell's right-hand aside): the latest sample of Flow's own processes and data sources in
 * six accordion sections - memory, storage, sync, DERIVE, requests, JVM - each with its 24 h / 7 d / 90 d trend. The range and the
 * expanded sections are remembered on this device. The component is mounted only while the panel is open, so the one-minute
 * refresh of `current` stops with it, and a section's history is only requested while it is expanded.
 */
export default function TelemetryPanel({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const [range, setRange] = useStoredState<TelemetryRange>("telemetry.range", "24h", isRange);
  const [expanded, setExpanded] = useStoredState<SectionId[]>("telemetry.sections", ["memory"], isSectionList);
  const current = useQuery({
    queryKey: ["telemetry", "current"],
    queryFn: ({ signal }) => getTelemetryCurrent(signal),
    refetchInterval: REFRESH_MS,
    refetchIntervalInBackground: false,
    staleTime: REFRESH_MS / 2,
  });
  // A section stays rendered once opened (so it can finish its close transition and keep its figures); its history queries run
  // only while it is expanded (`active`).
  const [opened, setOpened] = useState<ReadonlySet<SectionId>>(() => new Set(expanded));
  const data = current.data;
  const sampled = data?.processes.reduce<number | null>((newest, p) => (p.sampledAt == null ? newest : Math.max(newest ?? 0, p.sampledAt)), null);
  return (
    <>
      <AppShell.Section p="md" pb="xs" style={{ borderBottom: "1px solid var(--mantine-color-default-border)" }}>
        <Stack gap="xs">
          <Group justify="space-between" wrap="nowrap">
            <Stack gap={0}>
              <Title order={2} size="h4">
                {t("telemetry.title")}
              </Title>
              {sampled != null && (
                <Text size="xs" c="dimmed">
                  {t("telemetry.sampledAt", { time: formatDateTime(sampled) })}
                </Text>
              )}
            </Stack>
            <ActionIcon aria-label={t("common.action.close")} onClick={onClose}>
              <IconX size={18} />
            </ActionIcon>
          </Group>
          <SegmentedControl
            size="xs"
            fullWidth
            aria-label={t("telemetry.range.label")}
            value={range}
            onChange={(value) => setRange(value as TelemetryRange)}
            data={RANGES.map((value) => ({ value, label: t(`telemetry.range.${value}`) }))}
          />
        </Stack>
      </AppShell.Section>
      <AppShell.Section grow component={ScrollArea} type="hover" scrollbarSize={6}>
        <Stack p="md" gap="sm">
          {current.isError && data === undefined && <ErrorAlert error={current.error} />}
          {current.isError && data !== undefined && <ErrorAlert error={current.error} title={t("telemetry.refreshFailed")} />}
          {data === undefined && !current.isError && <LoadingBlock />}
          {data?.processes
            .filter((process) => process.stale)
            .map((process) => (
              <Alert key={process.id} color="orange" variant="light" title={t("telemetry.stale.title", { role: roleName(t, process.role) })}>
                {t("telemetry.stale.message", { instance: process.instance })}
              </Alert>
            ))}
          {data !== undefined && (
            <Accordion
              multiple
              order={3}
              value={expanded}
              variant="separated"
              onChange={(value) => {
                setExpanded(value as SectionId[]);
                setOpened((previous) => new Set([...previous, ...(value as SectionId[])]));
              }}
            >
              {SECTION_IDS.map((id) => {
                const View = VIEWS[id];
                return (
                  <Accordion.Item key={id} value={id}>
                    <Accordion.Control>{t(`telemetry.section.${id}`)}</Accordion.Control>
                    <Accordion.Panel>{(expanded.includes(id) || opened.has(id)) && <View current={data} range={range} active={expanded.includes(id)} />}</Accordion.Panel>
                  </Accordion.Item>
                );
              })}
            </Accordion>
          )}
        </Stack>
      </AppShell.Section>
    </>
  );
}
