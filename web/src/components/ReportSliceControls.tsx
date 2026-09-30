import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Box, Group, SegmentedControl, Text } from "@mantine/core";
import type { ReportFilters } from "../api/reports";
import {
  BUCKETS,
  withFilterKey,
  WIP_BYS,
  WIP_ITEM_KINDS,
  wipColumnAvailable,
  type Bucket,
  type ReportControls,
  type DomainView,
  type ReportFilterState,
  type WipBy,
  type WipItemKind,
} from "../utils/reportFilter";
import ReportFilterSelect from "./ReportFilterSelect";

const UNCATEGORIZED = "UNCATEGORIZED";

type SliceProps = {
  filter: ReportFilterState;
  controls: ReportControls;
  onChange: (next: ReportFilterState) => void;
};

/** The report-specific dropdowns that narrow WHAT is counted: activity type and work category. */
export function ReportSliceSelects({ filters, filter, controls, onChange }: SliceProps & { filters: ReportFilters }) {
  const { t } = useTranslation();
  return (
    <>
      {controls.activityType && (
        <ReportFilterSelect
          label={t("reports.filters.activityType")}
          placeholder={t("reports.filters.anyValue")}
          data={filters.activityTypes}
          value={filter.activityType ?? null}
          onChange={(value) => onChange(withFilterKey(filter, "activityType", value))}
        />
      )}
      {controls.workCategory && (
        <ReportFilterSelect
          label={t("reports.filters.workCategory")}
          placeholder={t("reports.filters.anyValue")}
          data={[
            ...filters.workCategories.map((category) => ({ value: category, label: category })),
            { value: UNCATEGORIZED, label: t("reports.filters.uncategorized") },
          ]}
          value={filter.workCategory ?? null}
          onChange={(value) => onChange(withFilterKey(filter, "workCategory", value))}
        />
      )}
    </>
  );
}

/** A labelled segmented control: the caption is its accessible name (`aria-labelledby` on the control). */
function ToggleField({ id, label, children }: { id: string; label: string; children: (labelledBy: string) => ReactNode }) {
  return (
    <Box>
      <Text size="sm" fw={500} mb={4} id={id}>
        {label}
      </Text>
      {children(id)}
    </Box>
  );
}

/**
 * The segmented toggles on the bar's second row: domain view (delivered in / earned in), bucket
 * (week/month), WIP `by` and WIP item kind. Renders nothing when the report offers none.
 */
export default function ReportSliceControls({ filter, controls, onChange }: SliceProps) {
  const { t } = useTranslation();
  if (controls.domainView === undefined && !controls.bucket && !controls.wipBy && !controls.itemKind) return null;
  const columnAvailable = wipColumnAvailable(filter);
  return (
    <Group gap="lg" align="flex-end" wrap="wrap">
      {controls.domainView !== undefined && (
        <ToggleField id="report-domain-view" label={t("reports.filters.domainView")}>
          {(labelledBy) => (
            <SegmentedControl
              aria-labelledby={labelledBy}
              data={[
                { value: "TASK", label: t("reports.filters.deliveredIn") },
                { value: "EPIC", label: t("reports.filters.earnedIn") },
              ]}
              value={filter.domainView ?? controls.domainView}
              onChange={(value) => onChange(withFilterKey(filter, "domainView", value as DomainView))}
            />
          )}
        </ToggleField>
      )}
      {controls.bucket && (
        <ToggleField id="report-bucket" label={t("reports.filters.bucket")}>
          {(labelledBy) => (
            <SegmentedControl
              aria-labelledby={labelledBy}
              data={BUCKETS.map((value) => ({ value, label: t(`reports.filters.bucketOption.${value}`) }))}
              value={filter.bucket ?? "WEEK"}
              onChange={(value) => onChange(withFilterKey(filter, "bucket", value as Bucket))}
            />
          )}
        </ToggleField>
      )}
      {controls.wipBy && (
        <ToggleField id="report-wip-by" label={t("reports.filters.wipBy")}>
          {(labelledBy) => (
            <>
              <SegmentedControl
                aria-labelledby={labelledBy}
                data={WIP_BYS.map((value) => ({
                  value,
                  label: t(`reports.filters.wipByOption.${value}`),
                  disabled: value === "COLUMN" && !columnAvailable,
                }))}
                // A column choice the filter can no longer honour (a shared link, a team since cleared) reads as the default.
                value={filter.by === "COLUMN" && !columnAvailable ? "STAGE" : (filter.by ?? "STAGE")}
                onChange={(value) => onChange(withFilterKey(filter, "by", value as WipBy))}
              />
              {!columnAvailable && (
                <Text size="xs" c="dimmed" mt={4} maw={320}>
                  {t("reports.filters.wipByColumnHint")}
                </Text>
              )}
            </>
          )}
        </ToggleField>
      )}
      {controls.itemKind && (
        <ToggleField id="report-item-kind" label={t("reports.filters.itemKind")}>
          {(labelledBy) => (
            <SegmentedControl
              aria-labelledby={labelledBy}
              data={WIP_ITEM_KINDS.map((value) => ({ value, label: t(`reports.filters.itemKindOption.${value}`) }))}
              value={filter.itemKind ?? "TASK"}
              onChange={(value) => onChange(withFilterKey(filter, "itemKind", value as WipItemKind))}
            />
          )}
        </ToggleField>
      )}
    </Group>
  );
}
