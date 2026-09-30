import { useTranslation } from "react-i18next";
import { Select, type ComboboxData } from "@mantine/core";

/**
 * The bar's clearable, searchable single-choice dropdown: a label, an "any" placeholder and a
 * clear button whose accessible name says which control it clears.
 */
export default function ReportFilterSelect({
  label,
  placeholder,
  data,
  value,
  onChange,
}: {
  label: string;
  placeholder: string;
  data: ComboboxData;
  value: string | null;
  onChange: (value: string | null) => void;
}) {
  const { t } = useTranslation();
  return (
    <Select
      label={label}
      placeholder={placeholder}
      data={data}
      value={value}
      onChange={onChange}
      clearable
      clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: label }) }}
      searchable
      w={200}
    />
  );
}
