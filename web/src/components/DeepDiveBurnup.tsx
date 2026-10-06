import { lazy, Suspense, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Switch, Table, Text, Title } from "@mantine/core";
import type { DeepDiveReport } from "../api/reports";
import { buildDeepDiveBurnup, type DeepDiveBurnup as BurnupModel } from "../utils/deepDiveBurnup";
import { formatFigure } from "../utils/reportFormat";
import DailyTableDisclosure from "./DailyTableDisclosure";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";

// The chart (and with it recharts) rides its own lazy chunk.
const DeepDiveBurnupChart = lazy(() => import("./DeepDiveBurnupChart"));

const figureOrDash = (value: number | null) => (value === null ? "—" : formatFigure(value));

/** The chart's numbers, newest day first, every column whatever the budget switch shows. */
function BurnupTable({ burnup }: { burnup: BurnupModel }) {
  const { t } = useTranslation();
  const label = t("reports.deepDive.burnup.tableLabel");
  return (
    <ScrollRegion label={label} minWidth={400} maxHeight={360}>
      <Table verticalSpacing={4} stickyHeader aria-label={label}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.daily.day")}</Table.Th>
            <Table.Th ta="right">{t("reports.deepDive.burnup.column.pv")}</Table.Th>
            <Table.Th ta="right">{t("reports.deepDive.burnup.column.ev")}</Table.Th>
            <Table.Th ta="right">{t("reports.deepDive.burnup.column.ac")}</Table.Th>
            {burnup.hasBudget && <Table.Th ta="right">{t("reports.deepDive.burnup.column.budget")}</Table.Th>}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {[...burnup.points].reverse().map((point) => (
            <Table.Tr key={point.date}>
              <Table.Td>{point.date}</Table.Td>
              <Table.Td ta="right">{formatFigure(point.pv)}</Table.Td>
              <Table.Td ta="right">{figureOrDash(point.ev)}</Table.Td>
              <Table.Td ta="right">{figureOrDash(point.ac)}</Table.Td>
              {burnup.hasBudget && <Table.Td ta="right">{figureOrDash(point.budget)}</Table.Td>}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollRegion>
  );
}

/**
 * The Deep dive's Burn-up view: how plan (PV), earned value (EV) and cost (AC) add up day by day over the range the matrix
 * shows, from the SAME response (`utils/deepDiveBurnup.ts` does the sums). The epics' budget plan is an optional dashed line
 * (off by default, remembered per viewer; the switch is offered only when some epic has a planned window and a budget). Its numbers sit in the
 * table behind the disclosure.
 */
export default function DeepDiveBurnup({
  report,
  withBudget,
  onWithBudgetChange,
}: {
  report: DeepDiveReport;
  /** The budget switch is the page's state (remembered per viewer), so it survives a hop to the matrix and back. */
  withBudget: boolean;
  onWithBudgetChange: (next: boolean) => void;
}) {
  const { t } = useTranslation();
  const burnup = useMemo(() => buildDeepDiveBurnup(report), [report]);
  const budgetShown = burnup.hasBudget && withBudget;
  const rows = useMemo(
    () =>
      burnup.points.map(({ date, pv, ev, ac, budget }) => ({
        date,
        pv,
        ev,
        ac,
        ...(budgetShown ? { budget } : {}),
      })),
    [burnup, budgetShown],
  );

  return (
    <Stack gap="sm" component="section" aria-labelledby="deep-dive-burnup">
      <Title order={3} size="h4" id="deep-dive-burnup">
        {t("reports.deepDive.burnup.title")}
      </Title>
      <Text size="sm" c="dimmed">
        {t("reports.deepDive.burnup.description")}
      </Text>
      <Text size="xs" c="dimmed">
        {t("reports.deepDive.range", { from: report.range.from, to: report.range.to })}
      </Text>
      {burnup.empty ? (
        <Text size="sm" c="dimmed">
          {t("reports.deepDive.burnup.empty")}
        </Text>
      ) : (
        <>
          {burnup.hasBudget ? (
            <Switch
              label={t("reports.deepDive.burnup.budgetSwitch")}
              description={t("reports.deepDive.burnup.budgetHelp")}
              checked={withBudget}
              onChange={(event) => onWithBudgetChange(event.currentTarget.checked)}
            />
          ) : (
            <Text size="xs" c="dimmed">
              {t("reports.deepDive.burnup.noBudget")}
            </Text>
          )}
          <Suspense fallback={<LoadingBlock />}>
            <DeepDiveBurnupChart rows={rows} withBudget={budgetShown} asOfDate={burnup.asOfDate} />
          </Suspense>
          <Stack gap={2}>
            <Text size="xs" c="dimmed">
              {t("reports.deepDive.burnup.rangeNote")}
            </Text>
            {burnup.points.at(-1)?.ev === null && (
              <Text size="xs" c="dimmed">
                {t("reports.deepDive.burnup.actualsNote")}
              </Text>
            )}
            {burnup.includesEpicOwnCost && (
              <Text size="xs" c="dimmed">
                {t("reports.deepDive.burnup.ownCostNote")}
              </Text>
            )}
          </Stack>
          <DailyTableDisclosure>
            <BurnupTable burnup={burnup} />
          </DailyTableDisclosure>
        </>
      )}
    </Stack>
  );
}
