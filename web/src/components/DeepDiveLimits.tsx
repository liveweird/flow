import { useTranslation } from "react-i18next";
import { List, Stack, Text, Title } from "@mantine/core";
import type { DeepDiveReport } from "../api/reports";
import { formatMd } from "../utils/reportFormat";

const COUNTERS = ["neverInSprint", "noEstimate", "noWorkingDay", "epicsWithoutWindow", "laterFallback"] as const;
const FIXED = ["asWas", "subTasks", "execution", "worklogDay"] as const;

/**
 * What the page states openly as its limits: the report's own counters (what the selection could not draw, each with
 * a short why — a zero is shown, so the reader sees it was checked) and the fixed rules that are not data-dependent,
 * then how fresh the data is (the last derive's day).
 */
export default function DeepDiveLimits({ report }: { report: DeepDiveReport }) {
  const { t } = useTranslation();
  const { quality, range } = report;
  return (
    <Stack gap="sm" component="section" aria-labelledby="deep-dive-limits">
      <Title order={3} size="h4" id="deep-dive-limits">
        {t("reports.deepDive.limits.title")}
      </Title>
      <List spacing="xs" size="sm" aria-label={t("reports.deepDive.limits.countersAria")}>
        {COUNTERS.map((counter) => (
          <List.Item key={counter}>
            <Text span fw={600}>
              {t(`reports.deepDive.limits.counter.${counter}.label`)}: {quality[counter]}
            </Text>{" "}
            <Text span c="dimmed">
              {t(`reports.deepDive.limits.counter.${counter}.hint`)}
            </Text>
          </List.Item>
        ))}
        <List.Item>
          <Text span fw={600}>
            {t("reports.deepDive.limits.counter.epicOwnCostMd.label")}: {formatMd(quality.epicOwnCostMd)}
          </Text>{" "}
          <Text span c="dimmed">
            {t("reports.deepDive.limits.counter.epicOwnCostMd.hint")}
          </Text>
        </List.Item>
      </List>
      <List spacing="xs" size="sm" aria-label={t("reports.deepDive.limits.fixedAria")}>
        {FIXED.map((rule) => (
          <List.Item key={rule}>{t(`reports.deepDive.limits.fixed.${rule}`)}</List.Item>
        ))}
      </List>
      {range.asOfDay !== null && (
        <Text size="xs" c="dimmed">
          {t("reports.deepDive.limits.asOf", { day: range.asOfDay })}
        </Text>
      )}
    </Stack>
  );
}
