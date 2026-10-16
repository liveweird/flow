import { useTranslation } from "react-i18next";
import { Select, type ComboboxData } from "@mantine/core";

/**
 * The bar's clearable, searchable single-choice dropdown: a label, an "any" placeholder and a
 * clear button whose accessible name says which control it clears. `required` is the one-way variant: no
 * clear button, no "any" placeholder and the picked option cannot be clicked off (Mantine's `allowDeselect`).
 * `clearable` overrides only the clearing (`!required` by default): a select that may be empty but not cleared back to "any".
 */
export default function ReportFilterSelect({
  label,
  placeholder,
  data,
  value,
  onChange,
  required = false,
  clearable = !required,
}: {
  label: string;
  placeholder?: string;
  data: ComboboxData;
  value: string | null;
  onChange: (value: string | null) => void;
  required?: boolean;
  clearable?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Select
      label={label}
      placeholder={required ? undefined : placeholder}
      data={data}
      value={value}
      onChange={onChange}
      clearable={clearable}
      allowDeselect={clearable}
      clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: label }) }}
      searchable
      w={200}
    />
  );
}
