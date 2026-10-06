import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Alert, Badge, Button, Group, Select, Stack, Text, Title, VisuallyHidden } from "@mantine/core";
import { IconX } from "@tabler/icons-react";
import {
  currentDomainKeys,
  METRICS_STAGES,
  orphanOverrideDomains,
  orphanOverrideStatuses,
  setDomainStage,
  type DomainRowState,
  type DomainStageRowState,
  type MetricsStage,
  type StatusRowState,
} from "../utils/metricsConfigForm";
import ColumnTable, { type ColumnDef } from "./ColumnTable";

/**
 * The "Per-domain overrides" section of the metrics-config Statuses tab (`.claude/docs/metrics.md`
 * "Per-domain stage overrides"): pick a domain, then give any status a stage that differs from the
 * every-domain one above. "Same as all domains" is the ABSENCE of an override, so clearing the
 * select (or the row's remove button) deletes the row; a status without one keeps the stage in the
 * every-domain table. Callers own all state — the selected domain is the only local state.
 */
export default function DomainStageOverrides({
  statuses,
  domains,
  overrides,
  onChange,
}: {
  statuses: StatusRowState[];
  domains: DomainRowState[];
  overrides: DomainStageRowState[];
  onChange: (next: DomainStageRowState[]) => void;
}) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<string | null>(null);
  const domainKeys = currentDomainKeys(domains);
  const orphans = orphanOverrideDomains(domains, overrides);
  const orphanStatuses = orphanOverrideStatuses(statuses, overrides);
  const allKeys = [...domainKeys, ...orphans];
  const domainKey = selected !== null && allKeys.includes(selected) ? selected : (allKeys[0] ?? null);

  const countIn = (key: string) => overrides.filter((o) => o.domainKey === key).length;
  const domainOptions = allKeys.map((key) => {
    const total = countIn(key);
    const base = orphans.includes(key) ? t("metrics.config.statuses.overrides.orphanOption", { domain: key }) : key;
    return { value: key, label: total > 0 ? t("metrics.config.statuses.overrides.domainOption", { domain: base, total }) : base };
  });
  const stageLabel = (stage: MetricsStage | "") =>
    stage === "" ? t("metrics.config.statuses.overrides.unmapped") : t(`metrics.config.statuses.stage.${stage}`);
  const stageOptions = METRICS_STAGES.map((s) => ({ value: s, label: stageLabel(s) }));

  const columnsFor = (domainKey: string): ColumnDef<StatusRowState>[] => [
    { key: "status", header: t("metrics.config.statuses.columnStatus"), render: (status) => status.name },
    {
      key: "allDomains",
      header: t("metrics.config.statuses.overrides.columnAllDomains"),
      render: (status) => <Text size="sm">{stageLabel(status.stage)}</Text>,
    },
    {
      key: "thisDomain",
      header: t("metrics.config.statuses.overrides.columnThisDomain"),
      render: (status) => {
        const override = overrides.find((o) => o.domainKey === domainKey && o.statusId === status.statusId);
        const differs = override !== undefined && override.stage !== status.stage;
        return (
          <Group gap="xs" wrap="nowrap">
            <Select
              aria-label={t("metrics.config.statuses.overrides.stageAria", { name: status.name, domain: domainKey })}
              value={override?.stage ?? null}
              onChange={(value) =>
                onChange(setDomainStage(overrides, domainKey, status.statusId, (value ?? "") as MetricsStage | ""))
              }
              data={stageOptions}
              placeholder={t("metrics.config.statuses.overrides.samePlaceholder")}
              clearable
            />
            {differs && <Badge size="sm">{t("metrics.config.statuses.overrides.differs")}</Badge>}
          </Group>
        );
      },
    },
    {
      key: "actions",
      header: <VisuallyHidden>{t("metrics.config.statuses.overrides.columnActions")}</VisuallyHidden>,
      render: (status) => {
        const override = overrides.find((o) => o.domainKey === domainKey && o.statusId === status.statusId);
        return (
          override && (
            <ActionIcon
              aria-label={t("metrics.config.statuses.overrides.removeAria", { name: status.name, domain: domainKey })}
              onClick={() => onChange(setDomainStage(overrides, domainKey, status.statusId, ""))}
            >
              <IconX size={16} />
            </ActionIcon>
          )
        );
      },
    },
  ];

  return (
    <Stack gap="xs" mt="xl" data-testid="domain-stage-overrides">
      <Title order={3} size="h4">{t("metrics.config.statuses.overrides.title")}</Title>
      <Text size="sm" c="dimmed">
        {t("metrics.config.statuses.overrides.hint")}
      </Text>
      <Text size="sm">
        {overrides.length === 0
          ? t("metrics.config.statuses.overrides.summaryNone")
          : t("metrics.config.statuses.overrides.summary", { total: overrides.length })}
      </Text>
      {orphans.map((key) => (
        <Alert key={key} color="red" variant="light">
          {t("metrics.config.statuses.overrides.orphanError", { domain: key })}
        </Alert>
      ))}
      {orphanStatuses.map((o) => (
        <Alert key={`${o.domainKey}/${o.statusId}`} color="red" variant="light">
          <Group justify="space-between" wrap="nowrap">
            <Text size="sm">
              {t("metrics.config.statuses.overrides.orphanStatus", {
                status: o.statusId,
                domain: o.domainKey,
                stage: t(`metrics.config.statuses.stage.${o.stage}`),
              })}
            </Text>
            <Button
              size="compact-xs"
              color="red"
              aria-label={t("metrics.config.statuses.overrides.removeOrphanStatusAria", { status: o.statusId, domain: o.domainKey })}
              onClick={() => onChange(setDomainStage(overrides, o.domainKey, o.statusId, ""))}
            >
              {t("metrics.config.statuses.overrides.removeOrphanStatus")}
            </Button>
          </Group>
        </Alert>
      ))}
      {domainKey === null ? (
        <Text size="sm" c="dimmed">
          {t("metrics.config.statuses.overrides.noDomains")}
        </Text>
      ) : (
        <>
          <Select
            label={t("metrics.config.statuses.overrides.domainLabel")}
            data={domainOptions}
            value={domainKey}
            onChange={(value) => setSelected(value)}
            allowDeselect={false}
            maw={360}
          />
          <ColumnTable
            aria-label={t("metrics.config.statuses.overrides.tableLabel", { domain: domainKey })}
            columns={columnsFor(domainKey)}
            rows={statuses}
            rowKey={(status) => status.statusId}
          />
        </>
      )}
    </Stack>
  );
}
