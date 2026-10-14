import { useTranslation } from "react-i18next";
import { MultiSelect, type ComboboxData } from "@mantine/core";
import { MAX_ACCOUNTS } from "../utils/reportFilter";

const INPUT_MIN_WIDTH = 48;

/**
 * The bar's multiple-choice sibling of `ReportFilterSelect` (velocity's members): searchable, clearable with a named
 * clear button, at most `MAX_ACCOUNTS` picks (Mantine's `maxValues` — a click past the cap adds nothing, so the field
 * says so in its description once the cap is reached and stays quiet before), and the picked options leave the list so
 * the dropdown only offers what can still be added. The search input may shrink to `INPUT_MIN_WIDTH` (Mantine's own floor
 * is 100px), so the first pills and the input share one line at the control's width and the bar's row stays aligned.
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
      description={value.length >= MAX_ACCOUNTS ? t("reports.filters.memberCap", { max: MAX_ACCOUNTS }) : undefined}
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
      styles={{ inputField: { minWidth: INPUT_MIN_WIDTH } }}
    />
  );
}
