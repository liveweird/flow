import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Badge, Box, Group, Stack, Table, Text, useComputedColorScheme } from "@mantine/core";
import { Link as RouterLink, useLocation, useSearchParams } from "react-router-dom";
import type { CostMatrixReport, CostMatrixRow, ReportFilters } from "../api/reports";
import { drillSearch, isSprintRelative, maxCellMd } from "../utils/costMatrixReport";
import { HEAT_STEPS, heatStep, heatStyle } from "../utils/heatScale";
import { formatMd, formatPercent } from "../utils/reportFormat";
import classes from "../theme.module.css";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/** A foreign share is a fraction, or `null` when nothing was logged — a dash, never 0%. */
const shareText = (share: number | null) => (share === null ? MISSING : formatPercent(share));

/**
 * The cost matrix as a heat table: rows are the level's authors (author teams, then the team's authors, then the
 * one author), columns are the domains, a cell is man-days shaded by its size against the largest cell (ONE
 * sequential scale, `utils/heatScale.ts` — the figure is the carrier, never the fill), then the row total and
 * the foreign work beside it, and the totals row closes the table. The table IS the data, so it is a real table:
 * a caption, column headers and row headers with `scope`. The first column and the header row stay put while it
 * scrolls. A row NAME is the way in — a link to the same report one level down; a soft-deleted team is marked and
 * not linked (its own drill is a `400`), and the unassigned row is not linked under a sprint-relative period (a
 * team-less drill resolves no sprint and answers empty).
 */
export default function CostMatrixTable({ report, filters }: { report: CostMatrixReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const level = report.meta.level;
  const sprintRelative = isSprintRelative(report.meta);
  const max = maxCellMd(report);
  const caption = t(`reports.costMatrix.caption.${level}`);

  const drillable = (row: CostMatrixRow): { teamId: number; accountId?: string } | null => {
    if (row.teamId === null) return null;
    // The team-less bucket has no sprints: under a sprint-relative period its drill resolves none and answers empty.
    if (row.teamId === 0 && sprintRelative) return null;
    if (level === "UNIT") return row.active === false ? null : { teamId: row.teamId };
    if (level === "TEAM" && row.accountId !== null) return { teamId: row.teamId, accountId: row.accountId };
    return null;
  };

  const rowHeader = (row: CostMatrixRow): ReactNode => {
    const name = level === "UNIT" ? (row.label ?? t("reports.groups.unassigned")) : (row.label ?? row.accountId ?? t("reports.costMatrix.noAuthor"));
    const target = drillable(row);
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

  return (
    <Stack gap="xs">
      <ScrollRegion label={caption} minWidth={640} maxHeight="70vh">
        <Table stickyHeader captionSide="top" className={classes.heatTable}>
          <Table.Caption className={classes.heatCaption}>{caption}</Table.Caption>
          <Table.Thead>
            <Table.Tr>
              <Table.Th scope="col" className={classes.heatStickyCol} miw={190}>
                {t(level === "UNIT" ? "reports.costMatrix.column.team" : "reports.costMatrix.column.author")}
              </Table.Th>
              {report.columns.map((column) => (
                <Table.Th key={column.domain ?? "no-domain"} scope="col" ta="right">
                  {column.name ?? column.domain ?? t("reports.costMatrix.noDomain")}
                </Table.Th>
              ))}
              <Table.Th scope="col" ta="right">
                {t("reports.costMatrix.column.total")}
              </Table.Th>
              <Table.Th scope="col" ta="right">
                {t("reports.costMatrix.column.foreignMd")}
              </Table.Th>
              <Table.Th scope="col" ta="right">
                {t("reports.costMatrix.column.foreignShare")}
              </Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {report.rows.map((row) => (
              <Table.Tr key={`${row.teamId ?? "-"}:${row.accountId ?? "-"}`}>
                <Table.Th scope="row" ta="left" fw={400} className={classes.heatStickyCol}>
                  {rowHeader(row)}
                </Table.Th>
                {row.cells.map((cell) => {
                  const step = heatStep(cell.md, max);
                  const style = heatStyle(step, scheme);
                  return (
                    <Table.Td
                      key={cell.domain ?? "no-domain"}
                      ta="right"
                      data-heat={step}
                      c={step === 0 ? "dimmed" : undefined}
                      style={style && { backgroundColor: style.background, color: style.color }}
                    >
                      {formatMd(cell.md)}
                    </Table.Td>
                  );
                })}
                <Table.Td ta="right" fw={600}>
                  {formatMd(row.totalMd)}
                </Table.Td>
                <Table.Td ta="right">{formatMd(row.foreignMd)}</Table.Td>
                <Table.Td ta="right">{shareText(row.foreignShare)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
          <Table.Tfoot>
            <Table.Tr>
              <Table.Th scope="row" ta="left" className={classes.heatStickyCol}>
                {t("reports.costMatrix.totalRow")}
              </Table.Th>
              {report.columns.map((column) => (
                <Table.Td key={column.domain ?? "no-domain"} ta="right">
                  {formatMd(column.totalMd)}
                </Table.Td>
              ))}
              <Table.Td ta="right">{formatMd(report.totalMd)}</Table.Td>
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
