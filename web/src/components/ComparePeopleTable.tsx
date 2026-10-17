import { useTranslation } from "react-i18next";
import { Badge, Stack, Table, Text } from "@mantine/core";
import { IconArrowLeft, IconArrowRight } from "@tabler/icons-react";
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

/**
 * The difference column: B minus A. A row that names a better side gets the cue — a teal badge with an arrow pointing to that
 * side's column (left = A, right = B) and the gap, named in words for assistive technology ("A better by 1.4 days") — so colour is never
 * the only carrier, and it is never red (neither side is "wrong"). A tie, a neutral row and a side under the minimum sample print the
 * gap in plain dimmed text, with no cue at all.
 */
function DifferenceCell({ row }: { row: CompareRow }) {
  const { t } = useTranslation();
  const label = betterLabel(row, t);
  const text = differenceText(row, t);
  if (label === undefined || row.better === undefined) {
    return (
      <Text size="sm" c="dimmed" component="span">
        {text}
      </Text>
    );
  }
  const Arrow = row.better === "A" ? IconArrowLeft : IconArrowRight;
  return (
    <Badge color="teal" variant="light" size="lg" role="img" aria-label={label} leftSection={<Arrow size={14} aria-hidden />}>
      {text}
    </Badge>
  );
}

/** One person's figure of a row: the figure (a button to its items when there are some), then its sample — or, withheld, why. */
function FigureCell({
  row,
  cell,
  column,
  rowLabel,
  accountId,
  minSampleSize,
  drill,
}: {
  row: CompareRow;
  cell: CompareCell;
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
      <Text size="xs" c="dimmed" component="span">
        {cell.hidden ? t("reports.comparePeople.underMin", { n: cell.n, min: minSampleSize }) : t("reports.comparePeople.n", { n: cell.n })}
      </Text>
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
              return (
                <Table.Tr key={row.key}>
                  <Table.Th scope="row" fw={400}>
                    {rowLabel}
                  </Table.Th>
                  {([row.a, row.b] as const).map((cell, index) => (
                    <Table.Td key={index === 0 ? "a" : "b"} ta="right">
                      <FigureCell
                        row={row}
                        cell={cell}
                        column={headers[index]}
                        rowLabel={rowLabel}
                        accountId={report.people[index].accountId}
                        minSampleSize={report.meta.minSampleSize}
                        drill={drill}
                      />
                    </Table.Td>
                  ))}
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
