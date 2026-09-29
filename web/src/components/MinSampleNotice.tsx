import { useTranslation } from "react-i18next";
import { Alert } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";

/**
 * The "counts only" note (domain-model.md: groups below the minimum sample size show counts, not
 * percentiles): names how many items there are and how many are needed. Gray, not a warning — a
 * small sample is a fact about the selection, not a fault. `subject` picks what is withheld.
 */
export default function MinSampleNotice({
  n,
  minSampleSize,
  subject = "distribution",
}: {
  n: number;
  minSampleSize: number;
  subject?: "distribution" | "share" | "thresholds";
}) {
  const { t } = useTranslation();
  return (
    <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
      {/* An empty thresholds window still has to say no item is banded, so it has its own line. */}
      {n === 0 ? t(subject === "thresholds" ? "reports.minSample.thresholdsNone" : "reports.minSample.none") : t(`reports.minSample.${subject}`, { count: n, min: minSampleSize })}
    </Alert>
  );
}
