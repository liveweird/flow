import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Alert, Badge, Group, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import type { EpicProgressEpic } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { formatMd } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" fw={500}>
        {value}
      </Text>
    </Stack>
  );
}

function budgetSourceLabel(source: string | null, t: TFunction): string | null {
  if (source === "OWN") return t("reports.epicProgress.epic.budgetOwn");
  if (source === "CHILDREN") return t("reports.epicProgress.epic.budgetChildren");
  return null;
}

/**
 * The plan behind an epic's curve: the budget and where it comes from (own estimate, else the sum of
 * its tasks), the planned dates, the drift since the FIRST baseline (orange — a soft finding, never
 * red), every baseline, and — when there is no PV curve — the reason, in words. The planned dates
 * are calendar dates stored as UTC midnight, so they are read in UTC; when a baseline took effect
 * is an instant and is read in the report's configured zone.
 */
export default function EpicPlanPanel({ epic, timeZone }: { epic: EpicProgressEpic; timeZone: string }) {
  const { t } = useTranslation();
  const source = budgetSourceLabel(epic.budgetSource, t);
  const baselineColumns: ColumnDef<EpicProgressEpic["baselines"][number]>[] = [
    {
      key: "effectiveFrom",
      header: t("reports.epicProgress.epic.effectiveFrom"),
      render: (baseline) => formatDate(baseline.effectiveFrom, MISSING, timeZone),
    },
    {
      key: "supersededAt",
      header: t("reports.epicProgress.epic.supersededAt"),
      render: (baseline) =>
        baseline.supersededAt == null
          ? t("reports.epicProgress.epic.current")
          : formatDate(baseline.supersededAt, MISSING, timeZone),
    },
    { key: "start", header: t("reports.epicProgress.epic.start"), render: (baseline) => formatDate(baseline.startAt) },
    { key: "due", header: t("reports.epicProgress.epic.due"), render: (baseline) => formatDate(baseline.dueAt) },
    {
      key: "budget",
      header: t("reports.epicProgress.epic.budget"),
      render: (baseline) => (baseline.budgetMd == null ? MISSING : formatMd(baseline.budgetMd)),
      align: "right",
    },
  ];
  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
        <Fact
          label={t("reports.epicProgress.epic.budget")}
          value={
            epic.budgetMd == null
              ? MISSING
              : `${t("reports.epicProgress.epic.budgetValue", { md: formatMd(epic.budgetMd) })}${source ? ` · ${source}` : ""}`
          }
        />
        <Fact label={t("reports.epicProgress.epic.start")} value={formatDate(epic.startAt)} />
        <Fact label={t("reports.epicProgress.epic.due")} value={formatDate(epic.dueAt)} />
      </SimpleGrid>
      {(epic.drift.dates || epic.drift.budget) && (
        <Group gap="xs">
          {epic.drift.dates && <Badge color="orange">{t("reports.epicProgress.epic.driftDates")}</Badge>}
          {epic.drift.budget && <Badge color="orange">{t("reports.epicProgress.epic.driftBudget")}</Badge>}
        </Group>
      )}
      {!epic.inPvHorizon && (
        <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
          {t("reports.epicProgress.epic.noHorizon")}
        </Alert>
      )}
      {epic.inPvHorizon && !epic.hasPvCurve && (
        <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
          {t("reports.epicProgress.epic.noCurve")}
        </Alert>
      )}
      {epic.baselines.length > 0 && (
        <Stack gap="xs">
          <Title order={4}>{t("reports.epicProgress.epic.baselinesTitle")}</Title>
          <ScrollRegion label={t("reports.epicProgress.epic.baselinesLabel")} minWidth={520}>
            <ColumnTable
              aria-label={t("reports.epicProgress.epic.baselinesLabel")}
              columns={baselineColumns}
              rows={epic.baselines}
              rowKey={(baseline) => String(baseline.effectiveFrom)}
            />
          </ScrollRegion>
        </Stack>
      )}
    </Stack>
  );
}
