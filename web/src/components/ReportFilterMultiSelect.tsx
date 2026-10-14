import { useTranslation } from "react-i18next";
import { MultiSelect, type ComboboxData } from "@mantine/core";
import { MAX_ACCOUNTS } from "../utils/reportFilter";

/**
 * The bar's multiple-choice sibling of `ReportFilterSelect` (velocity's members): searchable, clearable with a named
 * clear button, at most `MAX_ACCOUNTS` picks (Mantine's `maxValues` — the options stop being offered at the cap), and
 * the picked options leave the list so the dropdown only offers what can still be added.
 */
export default function ReportFilterMultiSelect({
  label,
  placeholder,
  data,
  value,
  onChange,
}: {
  label: string;
  placeholder: string;
  data: ComboboxData;
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const { t } = useTranslation();
  return (
    <MultiSelect
      label={label}
      placeholder={value.length === 0 ? placeholder : undefined}
      data={data}
      value={value}
      onChange={onChange}
      clearable
      clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: label }) }}
      searchable
      hidePickedOptions
      maxValues={MAX_ACCOUNTS}
      w={320}
    />
  );
}
