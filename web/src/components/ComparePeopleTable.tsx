import { useTranslation } from "react-i18next";
import { Group, Stack, Table, Text, VisuallyHidden } from "@mantine/core";
import { IconCheck } from "@tabler/icons-react";
import type { CompareCell, CompareRow, ComparePeopleReport } from "../api/reports";
import { betterLabel, cellText, differenceText, drillCount } from "../utils/comparePeopleReport";
import type { CellDrillTarget } from "../utils/reportDrill";
import DrillFigure from "./DrillFigure";
import ScrollRegion from "./ScrollRegion";

/** What the table needs to open a cell's items: the page's drill (`useReportDrill.open`) and whether the rows are kept data of a previous filter. */
export interface CompareDrill {
  onOpen: (target: CellDrillTarget, trigger: HTMLElement) => void;
  disabled: boolean;
}

/** The rows grouped by their `group`, in the order the server listed them (a group is contiguous there; the capacity rows sit in their groups). */
function groupsOf(rows: readonly CompareRow[]): Array<{ group: CompareRow["group"]; rows: CompareRow[] }> {
  const groups: Array<{ group: CompareRow["group"]; rows: CompareRow[] }> = [];
  for (const row of rows) {
    const last = groups.find((candidate) => candidate.group === row.group);
    if (last) last.rows.push(row);
    else groups.push({ group: row.group, rows: [row] });
  }
  return groups;
}

/** The difference column: the plain gap between the two figures — the absolute value, no sign, no cue, no colour (the better side is marked on its own figure). */
function DifferenceCell({ row }: { row: CompareRow }) {
  const { t } = useTranslation();
  return (
    <Text size="sm" component="span">
      {differenceText(row, t)}
    </Text>
  );
}

/**
 * One person's figure of a row: the figure (a button to its items when there are some), then its sample — or, withheld, why. When this
 * side is the row's better one, `betterText` ("better by 1.4 days") rides along as visually hidden text after the sample and a check
 * icon sits beside the figure, so the teal tint on the cell is never the only carrier.
 */
function FigureCell({
  row,
  cell,
  column,
  rowLabel,
  accountId,
  minSampleSize,
  drill,
  betterText,
}: {
  row: CompareRow;
  cell: CompareCell;
  /** The words behind the tint when this is the better side of the row; `undefined` otherwise. */
  betterText?: string;
  /** The column header the figure sits under ("A · Ann Lee"): part of the button's accessible name. */
  column: string;
  rowLabel: string;
  accountId: string;
  minSampleSize: number;
  drill: CompareDrill;
}) {
  const { t } = useTranslation();
  const figure = cellText(row, cell);
  const view = row.drill;
  return (
    <Stack gap={0} align="flex-end">
      <Group gap={4} wrap="nowrap">
        {betterText !== undefined && <IconCheck size={14} aria-hidden />}
        <Text size="sm" fw={500} component="span">
          <DrillFigure
            label={t("reports.drill.figureAria", { figure, column, name: rowLabel })}
            count={drillCount(row, cell)}
            disabled={drill.disabled}
            onOpen={view === undefined ? undefined : (trigger) => drill.onOpen({ kind: "cell", view, segmentAccountId: accountId }, trigger)}
          >
            {figure}
          </DrillFigure>
        </Text>
      </Group>
      {/* On the teal tint the sample takes the cell's own AA-tested ink: the dimmed grey is only 4.5:1 on the plain surface. */}
      <Text size="xs" c={betterText === undefined ? "dimmed" : "inherit"} component="span">
        {cell.hidden ? t("reports.comparePeople.underMin", { n: cell.n, min: minSampleSize }) : t("reports.comparePeople.n", { n: cell.n })}
      </Text>
      {betterText !== undefined && <VisuallyHidden>{` (${betterText})`}</VisuallyHidden>}
    </Stack>
  );
}

/**
 * The comparison table: Metric · A · B · Difference, one section per group (`DELIVERY`…`QUALITY`) with the rows the server listed — the
 * four capacity rows are in it exactly when the response carries them (an editor of both people's teams), never inferred from the role.
 * A cell prints the figure in the row's unit with its sample under it, and is a button to the items behind it where there are some.
 */
export default function ComparePeopleTable({ report, drill }: { report: ComparePeopleReport; drill: CompareDrill }) {
  const { t } = useTranslation();
  const [first, second] = report.people;
  const headers = [
    t("reports.comparePeople.side", { side: "A", name: first.label }),
    t("reports.comparePeople.side", { side: "B", name: second.label }),
  ];
  return (
    <ScrollRegion label={t("reports.comparePeople.tableAria")} minWidth={640}>
      <Table aria-label={t("reports.comparePeople.tableAria")} verticalSpacing={6}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th scope="col">{t("reports.comparePeople.column.metric")}</Table.Th>
            <Table.Th scope="col" ta="right">
              {headers[0]}
            </Table.Th>
            <Table.Th scope="col" ta="right">
              {headers[1]}
            </Table.Th>
            <Table.Th scope="col" ta="right">
              {t("reports.comparePeople.column.difference")}
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        {groupsOf(report.rows).map(({ group, rows }) => (
          <Table.Tbody key={group}>
            <Table.Tr>
              <Table.Th scope="rowgroup" colSpan={4} bg="var(--mantine-color-default-hover)">
                {t(`reports.comparePeople.group.${group}`)}
              </Table.Th>
            </Table.Tr>
            {rows.map((row) => {
              const rowLabel = t(`reports.comparePeople.row.${row.key}`);
              // A row names one better side only with the words to say it (a tie, a neutral row, a withheld side name none).
              const betterText = betterLabel(row, t);
              return (
                <Table.Tr key={row.key}>
                  <Table.Th scope="row" fw={400}>
                    {rowLabel}
                  </Table.Th>
                  {([row.a, row.b] as const).map((cell, index) => {
                    const better = betterText !== undefined && row.better === (index === 0 ? "A" : "B");
                    return (
                      <Table.Td
                        key={index === 0 ? "a" : "b"}
                        ta="right"
                        data-better={better ? "true" : undefined}
                        bg={better ? "var(--mantine-color-teal-light)" : undefined}
                        c={better ? "var(--mantine-color-teal-light-color)" : undefined}
                      >
                        <FigureCell
                          betterText={better ? betterText : undefined}
                          row={row}
                          cell={cell}
                          column={headers[index]}
                          rowLabel={rowLabel}
                          accountId={report.people[index].accountId}
                          minSampleSize={report.meta.minSampleSize}
                          drill={drill}
                        />
                      </Table.Td>
                    );
                  })}
                  <Table.Td ta="right">
                    <DifferenceCell row={row} />
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        ))}
      </Table>
    </ScrollRegion>
  );
}
