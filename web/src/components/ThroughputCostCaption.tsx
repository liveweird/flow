import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";

/**
 * What the Actual cost beside Delivered is, under a Throughput card's title: for the period card the time logged inside the period on the
 * items that were not yet done when it began (delivered in it or still open), for the sprint card the time between the sprint's dates on its final scope. With people
 * picked ("where the split shows") one more sentence says Actual cost is by who logged the time while Delivered is by assignee.
 */
export default function ThroughputCostCaption({ scope, byPerson }: { scope: "period" | "sprint"; byPerson: boolean }) {
  const { t } = useTranslation();
  return (
    <Text size="xs" c="dimmed">
      {t(scope === "period" ? "reports.throughput.actualCostCaption" : "reports.throughput.actualCostSprintCaption")}
      {byPerson ? ` ${t("reports.throughput.actualCostUserNote")}` : ""}
    </Text>
  );
}
