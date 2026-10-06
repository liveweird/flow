import { memo } from "react";
import { useTranslation } from "react-i18next";
import { Table, Text } from "@mantine/core";
import { epicWindowDates, rowLabel } from "../utils/deepDiveCell";
import { type LayerTotals, type MatrixRow } from "../utils/deepDiveAggregate";
import { type DeepDiveMatrix, type MatrixEpic } from "../utils/deepDiveMatrix";
import { formatFigure } from "../utils/reportFormat";
import classes from "../theme.module.css";
import ScrollRegion from "./ScrollRegion";

const FIGURES: ReadonlyArray<{
  key: keyof LayerTotals;
  range: "pvRange" | "execRange" | "evRange" | "costRange";
  total: "pvTotal" | "execTotal" | "evTotal" | "costTotal";
}> = [
  { key: "pvMd", range: "pvRange", total: "pvTotal" },
  { key: "execTaskDays", range: "execRange", total: "execTotal" },
  { key: "evMd", range: "evRange", total: "evTotal" },
  { key: "costMd", range: "costRange", total: "costTotal" },
];

/**
 * The matrix's text alternative: one real table of every epic and task with each layer's figure in the shown
 * range and over the whole life (the server's totals — work outside the range still counts), plus the plan each
 * task's PV spreads (basis, its source) or why it has none, and each epic's planned window and budget. All layers
 * always, whatever the toggles show.
 */
const DeepDiveSummaryTable = memo(function DeepDiveSummaryTable({ matrix }: { matrix: DeepDiveMatrix }) {
  const { t } = useTranslation();
  const label = t("reports.deepDive.matrix.summary.title");

  const figures = (totals: LayerTotals, inRange: LayerTotals) =>
    FIGURES.flatMap(({ key, range, total }) => [
      <Table.Td key={range} ta="right">
        {formatFigure(inRange[key])}
      </Table.Td>,
      <Table.Td key={total} ta="right">
        {formatFigure(totals[key])}
      </Table.Td>,
    ]);

  const planCells = (row: MatrixRow) => {
    const plan = row.plan;
    return [
      <Table.Td key="basis" ta="right">
        {plan?.planBasisMd == null ? "—" : formatFigure(plan.planBasisMd)}
      </Table.Td>,
      <Table.Td key="source">{plan === null ? "" : t(`reports.deepDive.matrix.summary.planSource.${plan.planSource}`)}</Table.Td>,
      <Table.Td key="reason">
        {plan?.noPlanReason == null ? "" : t(`reports.deepDive.matrix.summary.noPlanReason.${plan.noPlanReason}`)}
      </Table.Td>,
    ];
  };

  const epicCells = (epic: MatrixEpic | null) => {
    const window = epic === null ? null : epicWindowDates(epic, matrix.range.from);
    return [
      <Table.Td key="window">{window === null ? "" : t("reports.deepDive.matrix.tip.span", window)}</Table.Td>,
      <Table.Td key="budget" ta="right">
        {epic?.budgetMd == null ? "" : formatFigure(epic.budgetMd)}
      </Table.Td>,
    ];
  };

  const line = (row: MatrixRow, indent: boolean, epic: MatrixEpic | null = null) => (
    <Table.Tr key={row.id}>
      <Table.Th scope="row" ta="left" fw={indent ? 400 : 600} pl={indent ? "xl" : undefined} className={classes.heatStickyCol}>
        {rowLabel(row, t)}
      </Table.Th>
      {figures(row.totals, row.inRange)}
      {planCells(row)}
      {epicCells(epic)}
    </Table.Tr>
  );

  return (
    <ScrollRegion label={label} minWidth={1400} maxHeight="60vh">
      <Table stickyHeader className={classes.heatTable} aria-label={label}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th scope="col" className={classes.heatStickyCol} miw={200}>
              {t("reports.deepDive.matrix.summary.item")}
            </Table.Th>
            {FIGURES.flatMap(({ range, total }) => [
              <Table.Th key={range} scope="col" ta="right">
                {t(`reports.deepDive.matrix.summary.${range}`)}
              </Table.Th>,
              <Table.Th key={total} scope="col" ta="right">
                {t(`reports.deepDive.matrix.summary.${total}`)}
              </Table.Th>,
            ])}
            <Table.Th scope="col" ta="right">
              {t("reports.deepDive.matrix.summary.basis")}
            </Table.Th>
            <Table.Th scope="col">{t("reports.deepDive.matrix.summary.source")}</Table.Th>
            <Table.Th scope="col">{t("reports.deepDive.matrix.summary.reason")}</Table.Th>
            <Table.Th scope="col">{t("reports.deepDive.matrix.summary.window")}</Table.Th>
            <Table.Th scope="col" ta="right">
              {t("reports.deepDive.matrix.summary.budget")}
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {matrix.epics.flatMap((epic) => [
            line(epic.row, false, epic),
            ...epic.tasks.map((task) => line(task, true)),
            ...(epic.ownCost === null ? [] : [line(epic.ownCost, true)]),
          ])}
        </Table.Tbody>
        <Table.Tfoot>
          <Table.Tr>
            <Table.Th scope="row" ta="left" className={classes.heatStickyCol}>
              {t("reports.deepDive.matrix.summary.totalRow")}
            </Table.Th>
            {figures(matrix.totals, matrix.inRange)}
            <Table.Td colSpan={5}>
              {matrix.ownCostTotalMd > 0 && (
                <Text size="xs" c="dimmed">
                  {t("reports.deepDive.matrix.summary.ownCostRow")}: {formatFigure(matrix.ownCostTotalMd)}
                </Text>
              )}
            </Table.Td>
          </Table.Tr>
        </Table.Tfoot>
      </Table>
    </ScrollRegion>
  );
});

export default DeepDiveSummaryTable;
