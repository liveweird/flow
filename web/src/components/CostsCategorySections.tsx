import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { CostsReport } from "../api/reports";
import { categoryColors } from "../utils/costsCategories";
import CostsCategoryTrendSection from "./CostsCategoryTrendSection";
import CostsTargetsSection from "./CostsTargetsSection";

/**
 * Costs' work-category widgets (A45), under the donuts: the team's targets against its actual mix, then each category's cumulative
 * share by week. Both read the very worklogs the work-category donut splits, and wear its colours. The targets are the TEAM's, so they
 * exist for the team's whole mix only (one dimmed line says why when one author is picked or a domain, activity type or work category narrows the worklogs); the trend also answers for one author.
 */
export default function CostsCategorySections({
  report,
  teamId,
  teamName,
  refreshing,
}: {
  report: CostsReport;
  teamId: number | undefined;
  teamName: string;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const { categoryTargets, categoryShareTrend, meta } = report;
  const colors = categoryColors(report);
  return (
    <>
      {categoryTargets !== null && (
        <CostsTargetsSection targets={categoryTargets} teamId={teamId} teamName={teamName} colors={colors} refreshing={refreshing} />
      )}
      {categoryTargets === null && meta.level !== "UNIT" && (
        <Text size="xs" c="dimmed" data-testid="costs-targets-unavailable">
          {t(meta.level === "USER" ? "reports.costs.categories.targets.userNote" : "reports.costs.categories.targets.sliceNote")}
        </Text>
      )}
      <CostsCategoryTrendSection trend={categoryShareTrend} colors={colors} unitLevel={meta.level === "UNIT"} refreshing={refreshing} />
    </>
  );
}
