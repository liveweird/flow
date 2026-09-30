import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { ParseKeys } from "i18next";
import { Alert, Button, Chip, Group, Input, NumberInput, Paper, Select, Stack, TagsInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getMetricsSettings, updateMetricsSettings } from "../api/metrics";
import { useAdmin } from "../auth";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import {
  EMPTY_METRICS_SETTINGS_FORM,
  fromMetricsSettingsResponse,
  metricsSettingsFormValidation,
  metricsSettingsSaveErrorMessage,
  supportedTimeZones,
  toMetricsSettingsRequest,
  WEEKEND_DAY_OPTIONS,
  type MetricsSettingsFormValues,
} from "../utils/metricsForm";
import { showSuccessToast } from "../utils/toast";
import ErrorAlert from "../components/ErrorAlert";

/**
 * The global `metrics.settings` singleton (`.claude/docs/domain-model.md` "Configuration") —
 * calendar/threshold configuration the whole metrics layer is built against. ADMIN only
 * (`RequireAdmin`, `/metrics-settings`). A save bumps `configRevision` server-side and, once the
 * DERIVE job lands (M3), re-derives every enabled connection's reports — the toast says so now so
 * the wording doesn't need a follow-up change.
 */
export default function MetricsSettings() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Route-guarded by RequireAdmin already — this additionally stops a redirected caller from
  // firing a request (the Data-sources/Users precedent).
  const settings = useQuery({ queryKey: ["metrics-settings"], queryFn: getMetricsSettings, enabled: admin });

  const form = useForm<MetricsSettingsFormValues>({
    initialValues: EMPTY_METRICS_SETTINGS_FORM,
    validate: metricsSettingsFormValidation(t),
  });

  // Derived, not effect-set: initialize applies once (the guarded-initialize idiom).
  if (settings.data && !form.initialized) {
    form.initialize(fromMetricsSettingsResponse(settings.data));
  }

  async function onSubmit(values: MetricsSettingsFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateMetricsSettings(toMetricsSettingsRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["metrics-settings"] });
      showSuccessToast(t("metrics.settings.toast.saved"));
    } catch (err) {
      setError(metricsSettingsSaveErrorMessage(err, t));
    } finally {
      setSubmitting(false);
    }
  }

  const timeZoneOptions = supportedTimeZones();

  return (
    <Stack gap="md">
      <PageHeader title={t("metrics.settings.title")} description={t("metrics.settings.intro")} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        {settings.isLoading ? (
          <LoadingBlock />
        ) : settings.isError ? (
          <ErrorAlert error={settings.error} />
        ) : (
          <form onSubmit={form.onSubmit(onSubmit)} noValidate>
            <Stack>
              <NumberInput
                label={t("metrics.settings.field.hoursPerDay")}
                description={t("metrics.settings.field.hoursPerDayHint")}
                min={0}
                max={24}
                step={0.5}
                {...form.getInputProps("hoursPerDay")}
              />
              <Select
                label={t("metrics.settings.field.timeZone")}
                data={timeZoneOptions}
                searchable
                allowDeselect={false}
                {...form.getInputProps("timeZone")}
              />
              <Chip.Group multiple {...form.getInputProps("weekendDays")}>
                <Stack gap={4}>
                  <Input.Label>{t("metrics.settings.field.weekendDays")}</Input.Label>
                  <Group gap="xs">
                    {WEEKEND_DAY_OPTIONS.map((option) => (
                      <Chip key={option.value} value={option.value}>
                        {t(`metrics.settings.weekday.${option.dayNumber}` as ParseKeys)}
                      </Chip>
                    ))}
                  </Group>
                  {form.errors.weekendDays && (
                    <Alert color="red" variant="light" py={4}>
                      {form.errors.weekendDays}
                    </Alert>
                  )}
                </Stack>
              </Chip.Group>
              <TagsInput
                label={t("metrics.settings.field.holidays")}
                description={t("metrics.settings.field.holidaysHint")}
                placeholder={t("common.dateFormatHint")}
                {...form.getInputProps("holidays")}
              />
              <NumberInput
                label={t("metrics.settings.field.commitmentGraceMinutes")}
                min={0}
                allowDecimal={false}
                {...form.getInputProps("commitmentGraceMinutes")}
              />
              <NumberInput
                label={t("metrics.settings.field.minSampleSize")}
                min={1}
                allowDecimal={false}
                {...form.getInputProps("minSampleSize")}
              />
              <NumberInput
                label={t("metrics.settings.field.agingWindowItems")}
                min={1}
                allowDecimal={false}
                {...form.getInputProps("agingWindowItems")}
              />
              <TagsInput
                label={t("metrics.settings.field.agingPercentiles")}
                description={t("metrics.settings.field.agingPercentilesHint")}
                {...form.getInputProps("agingPercentiles")}
              />
              <NumberInput
                label={t("metrics.settings.field.backlogWindowSprints")}
                min={1}
                allowDecimal={false}
                {...form.getInputProps("backlogWindowSprints")}
              />
              <NumberInput
                label={t("metrics.settings.field.epicDriftDays")}
                min={0}
                allowDecimal={false}
                {...form.getInputProps("epicDriftDays")}
              />
              {error && (
                <Alert color="red" variant="light">
                  {error}
                </Alert>
              )}
              <Group justify="flex-end" gap="sm">
                <Button type="submit" loading={submitting}>
                  {t("common.action.save")}
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Paper>
    </Stack>
  );
}
