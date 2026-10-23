import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { FEATURES, type Feature } from "../api/session";
import { FEATURE_FLAGS_KEY, type FeatureFlagFilters as Filters } from "../hooks/useFeatureFlagFilters";
import ClearableTextInput from "./ClearableTextInput";
import FilterPanel from "./FilterPanel";

/** The /feature-flags filter panel: feature, state (any / enabled / disabled), name, email. */
export default function FeatureFlagFilters({ filters }: { filters: Filters }) {
  const { t } = useTranslation();
  return (
    <FilterPanel activeFilterCount={filters.activeCount} storageKey={FEATURE_FLAGS_KEY}>
      <Select
        label={t("flowUsers.featureFlags.featureLabel")}
        value={filters.feature}
        onChange={(v) => {
          if (v != null) filters.setFeature(v as Feature);
        }}
        allowDeselect={false}
        data={FEATURES.map((f) => ({ value: f, label: t(`common.feature.${f}`) }))}
      />
      <Select
        label={t("flowUsers.featureFlags.stateLabel")}
        value={filters.state}
        onChange={filters.setState}
        clearable
        placeholder={t("flowUsers.featureFlags.stateAny")}
        data={[
          { value: "enabled", label: t("flowUsers.featureFlags.stateEnabled") },
          { value: "disabled", label: t("flowUsers.featureFlags.stateDisabled") },
        ]}
      />
      <ClearableTextInput
        label={t("common.field.name")}
        value={filters.name}
        onChange={filters.setName}
        clearLabel={t("common.filter.clearName")}
      />
      <ClearableTextInput
        label={t("common.field.email")}
        value={filters.email}
        onChange={filters.setEmail}
        clearLabel={t("flowUsers.clearEmailFilter")}
      />
    </FilterPanel>
  );
}
