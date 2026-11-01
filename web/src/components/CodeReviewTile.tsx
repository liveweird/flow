import { useTranslation } from "react-i18next";
import { Group, Stack, Text } from "@mantine/core";
import type { CodeActivityReport } from "../api/reports";
import { formatOneDecimal, formatPercent } from "../utils/reportFormat";
import KpiFigure from "./KpiFigure";
import ReportChartCard from "./ReportChartCard";

/**
 * How review work is spread over the team's merged merge requests (whose details were read): the reviewers per merge request, the median, and the
 * share of the reviews the busiest reviewer holds. A bottleneck signal — the caption says no verdict is drawn on a person. Absent from the
 * response while no merged merge request has its details read; each figure is withheld under the minimum sample (its dash says so).
 */
export default function CodeReviewTile({
  review,
  minSampleSize,
  isRefreshing,
}: {
  review: NonNullable<CodeActivityReport["review"]>;
  minSampleSize: number;
  isRefreshing: boolean;
}) {
  const { t } = useTranslation();
  return (
    <ReportChartCard
      title={t("reports.codeActivity.review.title")}
      caption={t("reports.codeActivity.review.caption")}
      isPending={false}
      isRefreshing={isRefreshing}
      empty={false}
    >
      <Stack gap="sm">
        <Group gap="xl" wrap="wrap">
          <Stack gap={2}>
            <Text size="xs" c="dimmed">
              {t("reports.codeActivity.review.reviewers")}
            </Text>
            <KpiFigure>{review.reviewersPerMergedMrP50 === undefined ? "—" : formatOneDecimal(review.reviewersPerMergedMrP50)}</KpiFigure>
          </Stack>
          <Stack gap={2}>
            <Text size="xs" c="dimmed">
              {t("reports.codeActivity.review.topShare")}
            </Text>
            <KpiFigure>{review.topReviewerShare === undefined ? "—" : formatPercent(review.topReviewerShare)}</KpiFigure>
          </Stack>
        </Group>
        <Text size="xs" c="dimmed">
          {t("reports.codeActivity.review.basis", { n: review.n })}
          {(review.reviewersPerMergedMrP50 === undefined || review.topReviewerShare === undefined) &&
            ` ${t("reports.codeActivity.review.withheld", { min: minSampleSize })}`}
        </Text>
      </Stack>
    </ReportChartCard>
  );
}
