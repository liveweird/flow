import { useTranslation } from "react-i18next";
import { Badge, Stack, Table, Text } from "@mantine/core";
import type { CostEpicRow, CostMatrixReport } from "../api/reports";
import { epicColumnName, epicDrillKey, epicRowName } from "../utils/costEpics";
import { epicCellTarget } from "../utils/costMatrixReport";
import type { EpicDrillTarget } from "../utils/reportDrill";
import { formatMd } from "../utils/reportFormat";
import classes from "../theme.module.css";
import DrillFigure from "./DrillFigure";
import JiraKeyLink from "./JiraKeyLink";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/** The first column's width, a column's width and the least the region ever spans — the region grows with the columns it holds. */
const FIRST_COLUMN_PX = 190;
const COLUMN_PX = 96;
const MIN_REGION_PX = 640;

/**
 * The cost matrix's man-days by epic: a row per epic (its Jira key and summary, its domain dimmed under them), then "Other epics (N)" — the
 * epics outside the server's top 50 — and "No epic" last; a column per author team (UNIT) or author (TEAM), then the row's Total, and a footer of
 * the column totals and the grand total. At USER level the one person's column would repeat the Total, so only Total is drawn. Every
 * figure is the server's own rounded exact sum (never re-added here); a zero is a dimmed dash. A figure opens the tasks behind it: a cell in
 * its column's author (team), a Total in the page's own scope — except "Other epics": its cells name no epic to list, and its Total opens
 * only where the server's fold is the same set for the row as for the page (not at USER level, where the page's person narrows the ranking).
 * Plain text while the report shown is kept data of a previous filter (`drillable`). A real table: a hidden caption naming it, `scope`
 * headers, a sticky first column inside a `ScrollRegion`; no heat shading — the figure is the carrier.
 */
export default function CostEpicTable({
  report,
  title,
  drillable,
  onOpen,
}: {
  report: CostMatrixReport;
  /** The table's accessible name: the card's level-aware heading. */
  title: string;
  drillable: boolean;
  onOpen: (target: EpicDrillTarget, trigger: HTMLElement) => void;
}) {
  const { t } = useTranslation();
  const { level } = report.meta;
  const { rows } = report.byEpic;
  const columns = level === "USER" ? [] : report.byEpic.columns;
  const minWidth = Math.max(MIN_REGION_PX, FIRST_COLUMN_PX + COLUMN_PX * (columns.length + 1));
  const domainName = (domain: string | null) => report.columns.find((column) => column.domain === domain)?.name ?? domain;

  const figure = (md: number, target: EpicDrillTarget | null, label: string) =>
    md === 0 ? (
      <Text span c="dimmed">
        {MISSING}
      </Text>
    ) : (
      <DrillFigure label={label} count={1} disabled={!drillable || target === null} onOpen={target === null ? undefined : (trigger) => onOpen(target, trigger)}>
        {formatMd(md)}
      </DrillFigure>
    );

  const rowHeader = (row: CostEpicRow) => {
    if (row.kind !== "EPIC") {
      return (
        <Text size="sm" c={row.kind === "NO_EPIC" ? "dimmed" : undefined}>
          {epicRowName(t, row)}
        </Text>
      );
    }
    return (
      <Stack gap={0}>
        <Text size="sm">
          {row.issueKey !== null && <JiraKeyLink issueKey={row.issueKey} underline="always" />} {row.summary}
        </Text>
        {row.domain !== null && (
          <Text size="xs" c="dimmed">
            {t("reports.costMatrix.epics.domainLabel", { domain: domainName(row.domain) })}
          </Text>
        )}
      </Stack>
    );
  };

  const bodyRow = (row: CostEpicRow) => {
    const key = epicDrillKey(row);
    const name = epicRowName(t, row);
    // The fold's cells name no epic of their own, and at USER level its Total would rank over the person's slice, not the row's.
    const foldOpens = row.kind !== "OTHER";
    const totalTarget: EpicDrillTarget | null = row.kind === "OTHER" && level === "USER" ? null : { kind: "epic", keys: [key] };
    return (
      <Table.Tr key={`${row.kind}:${key}`}>
        <Table.Th scope="row" ta="left" fw={400} className={classes.heatStickyCol}>
          {rowHeader(row)}
        </Table.Th>
        {columns.map((column, i) => (
          <Table.Td key={`${column.teamId ?? "-"}:${column.accountId ?? "-"}`} ta="right">
            {figure(
              row.cells[i],
              foldOpens ? epicCellTarget(report, column, [key]) : null,
              t("reports.costMatrix.epics.openAria", { md: formatMd(row.cells[i]), column: epicColumnName(t, column, level), name }),
            )}
          </Table.Td>
        ))}
        <Table.Td ta="right" fw={600}>
          {figure(row.totalMd, totalTarget, t("reports.costMatrix.epics.openTotalAria", { md: formatMd(row.totalMd), name }))}
        </Table.Td>
      </Table.Tr>
    );
  };

  return (
    <ScrollRegion label={title} minWidth={minWidth} maxHeight="70vh">
      <Table stickyHeader captionSide="top" className={classes.heatTable}>
        <Table.Caption className={classes.heatCaption}>{title}</Table.Caption>
        <Table.Thead>
          <Table.Tr>
            <Table.Th scope="col" className={classes.heatStickyCol} miw={FIRST_COLUMN_PX}>
              {t("reports.costMatrix.epics.column.epic")}
            </Table.Th>
            {columns.map((column) => (
              <Table.Th key={`${column.teamId ?? "-"}:${column.accountId ?? "-"}`} scope="col" ta="right">
                {epicColumnName(t, column, level)}
                {column.active === false && (
                  <>
                    {" "}
                    <Badge color="gray">{t("reports.costMatrix.deletedTeam")}</Badge>
                  </>
                )}
              </Table.Th>
            ))}
            <Table.Th scope="col" ta="right">
              {t("reports.costMatrix.epics.column.total")}
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>{rows.map(bodyRow)}</Table.Tbody>
        <Table.Tfoot>
          <Table.Tr>
            <Table.Th scope="row" ta="left" className={classes.heatStickyCol}>
              {t("reports.costMatrix.totalRow")}
            </Table.Th>
            {columns.map((column) => (
              <Table.Td key={`${column.teamId ?? "-"}:${column.accountId ?? "-"}`} ta="right">
                {formatMd(column.totalMd)}
              </Table.Td>
            ))}
            <Table.Td ta="right" fw={600}>
              {formatMd(report.totalMd)}
            </Table.Td>
          </Table.Tr>
        </Table.Tfoot>
      </Table>
    </ScrollRegion>
  );
}
