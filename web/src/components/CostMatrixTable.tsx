import { Fragment, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Badge, Box, Group, Stack, Table, Text, useComputedColorScheme } from "@mantine/core";
import { Link as RouterLink, useLocation, useSearchParams } from "react-router-dom";
import type { CostMatrixReport, CostMatrixRow, ReportFilters } from "../api/reports";
import { bucketHeading, drillSearch, isSprintRelative, maxCellMd, rowDrillTarget } from "../utils/costMatrixReport";
import { HEAT_STEPS, heatStep, heatStyle } from "../utils/heatScale";
import { formatMd, formatPercent } from "../utils/reportFormat";
import classes from "../theme.module.css";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/** A foreign share is a fraction, or `null` when nothing was logged — a dash, never 0%. */
const shareText = (share: number | null) => (share === null ? MISSING : formatPercent(share));

/** A capacity figure, or a dash where the server has none (the no-author row is not a person). */
const capacityText = (md: number | null | undefined) => (md === null || md === undefined ? MISSING : formatMd(md));

/** The first column's width, a column's width and the least the region ever spans — the region grows with the columns it holds. */
const FIRST_COLUMN_PX = 190;
const COLUMN_PX = 96;
const MIN_REGION_PX = 640;

type Cell = CostMatrixRow["cells"][number];

/**
 * The cost matrix as a heat table: rows are the level's authors (author teams, then the team's authors, then the
 * one author), columns are the domains, a cell is man-days shaded by its size against the largest cell (ONE
 * sequential scale, `utils/heatScale.ts` — the figure is the carrier, never the fill), then the row total, the
 * person's capacity when the server sent it (right after the total, to read against it) and the foreign work, and
 * the totals row closes the table. With a split the domain cells move into one column group per week or sprint
 * (a two-row header: the groups, then their columns) and the period's total, capacity and foreign work come last
 * as a group of their own. The table IS the data, so it is a real table: a caption, column headers and row
 * headers with `scope`. The first column and the header rows stay put while it scrolls — sideways inside the
 * region, never the page. A row NAME is the way in — a link to the same report one level down; a soft-deleted team
 * is marked and not linked (its own drill is a `400`), and the unassigned row is not linked under a sprint-relative
 * period (a team-less drill resolves no sprint and answers empty).
 */
export default function CostMatrixTable({ report, filters }: { report: CostMatrixReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const level = report.meta.level;
  const sprintRelative = isSprintRelative(report.meta);
  const max = maxCellMd(report);
  const split = report.split;
  // The column is the server's call (the response carries the block for the team's editors only), never the role's.
  const withCapacity = !!report.capacity;
  const caption = `${t(`reports.costMatrix.caption.${level}`)}${split ? `, ${t(`reports.costMatrix.captionSplit.${split.kind}`)}` : ""}`;
  const buckets = split?.buckets ?? [];
  // Per group: the domains and the total (and the capacity); the period closes with total, capacity, foreign MD and share.
  const bucketWidth = report.columns.length + 1 + (withCapacity ? 1 : 0);
  const periodWidth = 3 + (withCapacity ? 1 : 0);
  const columnCount = split ? buckets.length * bucketWidth + periodWidth : report.columns.length + periodWidth;
  const minWidth = Math.max(MIN_REGION_PX, FIRST_COLUMN_PX + COLUMN_PX * columnCount);

  const rowHeader = (row: CostMatrixRow): ReactNode => {
    const name = level === "UNIT" ? (row.label ?? t("reports.groups.unassigned")) : (row.label ?? row.accountId ?? t("reports.costMatrix.noAuthor"));
    const target = rowDrillTarget(row, level, sprintRelative);
    if (target !== null) {
      return (
        <Anchor
          component={RouterLink}
          to={{ pathname, search: drillSearch(params, filters, target) }}
          size="sm"
          aria-label={t("reports.groups.drillAria", { name })}
        >
          {name}
        </Anchor>
      );
    }
    return (
      <Text size="sm" component="span" c={row.label === null ? "dimmed" : undefined}>
        {name}
        {row.active === false && (
          <>
            {" "}
            <Badge color="gray">{t("reports.costMatrix.deletedTeam")}</Badge>
          </>
        )}
      </Text>
    );
  };

  const domainName = (column: CostMatrixReport["columns"][number]) => column.name ?? column.domain ?? t("reports.costMatrix.noDomain");

  const heatCell = (cell: Cell, key: string, groupStart: boolean) => {
    const step = heatStep(cell.md, max);
    const style = heatStyle(step, scheme);
    return (
      <Table.Td
        key={key}
        ta="right"
        className={groupStart ? classes.heatGroupStart : undefined}
        data-heat={step}
        c={step === 0 ? "dimmed" : undefined}
        style={style && { backgroundColor: style.background, color: style.color }}
      >
        {formatMd(cell.md)}
      </Table.Td>
    );
  };

  /** A "Total" (+ "Capacity") pair of figures; `groupStart` marks the first cell of a column group. */
  const totalCells = (key: string, totalMd: number, capacityMd: number | null | undefined, groupStart: boolean) => (
    <Fragment key={`${key}-total`}>
      <Table.Td ta="right" fw={600} className={groupStart ? classes.heatGroupStart : undefined}>
        {formatMd(totalMd)}
      </Table.Td>
      {withCapacity && (
        <Table.Td ta="right">{capacityText(capacityMd)}</Table.Td>
      )}
    </Fragment>
  );

  /** "Total" (+ "Capacity"); `groupStart` marks a group that opens with it (the period group, whose domains are not repeated). */
  const totalHeaders = (groupStart: boolean) => (
    <>
      <Table.Th scope="col" ta="right" className={groupStart ? classes.heatGroupStart : undefined}>
        {t("reports.costMatrix.column.total")}
      </Table.Th>
      {withCapacity && (
        <Table.Th scope="col" ta="right">
          {t("reports.costMatrix.column.capacity")}
        </Table.Th>
      )}
    </>
  );

  const periodHeaders = () => (
    <>
      {totalHeaders(split !== undefined)}
      <Table.Th scope="col" ta="right">
        {t("reports.costMatrix.column.foreignMd")}
      </Table.Th>
      <Table.Th scope="col" ta="right">
        {t("reports.costMatrix.column.foreignShare")}
      </Table.Th>
    </>
  );

  const firstHeader = (
    <Table.Th scope="col" className={classes.heatStickyCol} miw={FIRST_COLUMN_PX} rowSpan={split ? 2 : undefined}>
      {t(level === "UNIT" ? "reports.costMatrix.column.team" : "reports.costMatrix.column.author")}
    </Table.Th>
  );

  const domainHeaders = (key: string, groupStart: boolean) =>
    report.columns.map((column, i) => (
      <Table.Th key={`${key}-${column.domain ?? "no-domain"}`} scope="col" ta="right" className={groupStart && i === 0 ? classes.heatGroupStart : undefined}>
        {domainName(column)}
      </Table.Th>
    ));

  const header = split ? (
    <>
      <Table.Tr>
        {firstHeader}
        {split.buckets.map((bucket) => (
          <Table.Th key={bucket.key} scope="colgroup" colSpan={bucketWidth} className={classes.heatGroupStart}>
            {bucketHeading(bucket, split.kind, t)}
          </Table.Th>
        ))}
        <Table.Th scope="colgroup" colSpan={periodWidth} className={classes.heatGroupStart}>
          {t("reports.costMatrix.periodGroup")}
        </Table.Th>
      </Table.Tr>
      <Table.Tr>
        {split.buckets.map((bucket) => (
          <Fragment key={bucket.key}>
            {domainHeaders(bucket.key, true)}
            {totalHeaders(false)}
          </Fragment>
        ))}
        {periodHeaders()}
      </Table.Tr>
    </>
  ) : (
    <Table.Tr>
      {firstHeader}
      {domainHeaders("period", false)}
      {periodHeaders()}
    </Table.Tr>
  );

  const bodyRow = (row: CostMatrixRow) => (
    <Table.Tr key={`${row.teamId ?? "-"}:${row.accountId ?? "-"}`}>
      <Table.Th scope="row" ta="left" fw={400} className={classes.heatStickyCol}>
        {rowHeader(row)}
      </Table.Th>
      {split
        ? (row.byBucket ?? []).map((figures, i) => [
            ...figures.cells.map((cell, j) => heatCell(cell, `${split.buckets[i].key}-${cell.domain ?? "no-domain"}`, j === 0)),
            totalCells(split.buckets[i].key, figures.totalMd, figures.capacityMd, false),
          ])
        : row.cells.map((cell) => heatCell(cell, cell.domain ?? "no-domain", false))}
      {totalCells("period", row.totalMd, row.capacityMd, split !== undefined)}
      <Table.Td ta="right">{formatMd(row.foreignMd)}</Table.Td>
      <Table.Td ta="right">{shareText(row.foreignShare)}</Table.Td>
    </Table.Tr>
  );

  const footerCells = split
    ? (report.bucketTotals ?? []).map((totals, i) => [
        ...totals.columns.map((column, j) => (
          <Table.Td key={`${split.buckets[i].key}-${column.domain ?? "no-domain"}`} ta="right" className={j === 0 ? classes.heatGroupStart : undefined}>
            {formatMd(column.totalMd)}
          </Table.Td>
        )),
        totalCells(split.buckets[i].key, totals.totalMd, totals.capacityMd, false),
      ])
    : report.columns.map((column) => (
        <Table.Td key={column.domain ?? "no-domain"} ta="right">
          {formatMd(column.totalMd)}
        </Table.Td>
      ));

  return (
    <Stack gap="xs">
      <ScrollRegion label={caption} minWidth={minWidth} maxHeight="70vh">
        <Table stickyHeader captionSide="top" className={classes.heatTable}>
          <Table.Caption className={classes.heatCaption}>{caption}</Table.Caption>
          <Table.Thead>{header}</Table.Thead>
          <Table.Tbody>{report.rows.map(bodyRow)}</Table.Tbody>
          <Table.Tfoot>
            <Table.Tr>
              <Table.Th scope="row" ta="left" className={classes.heatStickyCol}>
                {t("reports.costMatrix.totalRow")}
              </Table.Th>
              {footerCells}
              {totalCells("period", report.totalMd, report.capacity?.totalMd, split !== undefined)}
              <Table.Td ta="right">{formatMd(report.foreignMd)}</Table.Td>
              <Table.Td ta="right">{shareText(report.foreignShare)}</Table.Td>
            </Table.Tr>
          </Table.Tfoot>
        </Table>
      </ScrollRegion>
      {max > 0 && (
        <Group gap="xs" wrap="wrap" role="group" aria-label={t("reports.costMatrix.legend.label")}>
          <Text size="xs" c="dimmed">
            {t("reports.costMatrix.legend.text", { max: formatMd(max) })}
          </Text>
          <Group gap={2} aria-hidden>
            {Array.from({ length: HEAT_STEPS }, (_, i) => (
              <Box
                key={i}
                w={22}
                h={12}
                style={{
                  backgroundColor: heatStyle(i + 1, scheme)?.background,
                  border: "1px solid var(--mantine-color-default-border)",
                  borderRadius: 2,
                }}
              />
            ))}
          </Group>
        </Group>
      )}
    </Stack>
  );
}
