import { useTranslation } from "react-i18next";
import { Select, type ComboboxData } from "@mantine/core";

/**
 * The bar's clearable, searchable single-choice dropdown: a label, an "any" placeholder and a
 * clear button whose accessible name says which control it clears. `required` is the one-way variant: no
 * clear button, no "any" placeholder and the picked option cannot be clicked off (Mantine's `allowDeselect`).
 */
export default function ReportFilterSelect({
  label,
  placeholder,
  data,
  value,
  onChange,
  required = false,
}: {
  label: string;
  placeholder?: string;
  data: ComboboxData;
  value: string | null;
  onChange: (value: string | null) => void;
  required?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Select
      label={label}
      placeholder={required ? undefined : placeholder}
      data={data}
      value={value}
      onChange={onChange}
      clearable={!required}
      allowDeselect={!required}
      clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: label }) }}
      searchable
      w={200}
    />
  );
}
