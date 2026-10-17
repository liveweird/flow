import { useTranslation } from "react-i18next";
import { SegmentedControl } from "@mantine/core";
import { EPIC_BASES, type EpicBasis } from "../utils/reportFilter";

/**
 * "This period | Since start": what the figures and the curves are measured over (`basis` in the URL). The control shows the
 * basis the report is drawn on; touching it makes that choice explicit (absent from the URL, the server picks per level).
 */
export default function EpicProgressBasisControl({ value, onChange }: { value: EpicBasis; onChange: (basis: EpicBasis) => void }) {
  const { t } = useTranslation();
  return (
    <SegmentedControl
      aria-label={t("reports.epicProgress.basis.label")}
      w="fit-content"
      data={EPIC_BASES.map((basis) => ({ value: basis, label: t(`reports.epicProgress.basis.${basis}`) }))}
      value={value}
      onChange={(next) => onChange(next as EpicBasis)}
    />
  );
}
