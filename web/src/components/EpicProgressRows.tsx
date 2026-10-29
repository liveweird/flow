import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Stack, Text, Title } from "@mantine/core";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import type { EpicProgressReport, EpicProgressRow } from "../api/reports";
import { scopedSearch, type EpicScope, type EvmFigures } from "../utils/epicProgressReport";
import { formatIndex, formatMd, formatSignedMd } from "../utils/reportFormat";
import { epicProgressPath } from "../utils/reportLinks";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";
import JiraKeyLink from "./JiraKeyLink";

/** What travels with a drill from a domain to one of its epics, so the epic page can offer the way back. */
export interface EpicDrillState {
  domain?: { key: string; name: string };
}

const MISSING = "—";

const index = (value: number | null) => (value == null ? MISSING : formatIndex(value));

/** A row's figures on the basis the report shows: its `period` block, or the cumulative figures it carries itself. */
const shown = (row: EpicProgressRow, period: boolean): EvmFigures => (period ? row.period : row);

function FiguresTable({
  title,
  caption,
  firstColumn,
  rows,
  period,
  nameCell,
}: {
  title: string;
  caption?: string;
  firstColumn: string;
  rows: readonly EpicProgressRow[];
  period: boolean;
  nameCell: (row: EpicProgressRow) => ReactNode;
}) {
  const { t } = useTranslation();
  const figure = (column: "pv" | "ev" | "ac" | "sv" | "spi" | "cv" | "cpi", render: (row: EpicProgressRow) => ReactNode): ColumnDef<EpicProgressRow> => ({
    key: column,
    header: t(`reports.epicProgress.column.${column}`),
    render,
    align: "right",
  });
  const columns: ColumnDef<EpicProgressRow>[] = [
    { key: "name", header: firstColumn, render: nameCell },
    figure("pv", (row) => formatMd(shown(row, period).pv)),
    figure("ev", (row) => formatMd(shown(row, period).ev)),
    figure("ac", (row) => formatMd(shown(row, period).ac)),
    figure("sv", (row) => formatSignedMd(shown(row, period).sv)),
    figure("spi", (row) => index(shown(row, period).spi)),
    figure("cv", (row) => formatSignedMd(shown(row, period).cv)),
    figure("cpi", (row) => index(shown(row, period).cpi)),
  ];
  return (
    <Stack gap="xs">
      <Stack gap={0}>
        <Title order={4} size="h5">{title}</Title>
        {caption && (
          <Text size="xs" c="dimmed">
            {caption}
          </Text>
        )}
      </Stack>
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("reports.epicProgress.noRows")}
        </Text>
      ) : (
        <ScrollRegion label={title} minWidth={640}>
          <ColumnTable
            aria-label={title}
            columns={columns}
            rows={rows}
            rowKey={(row) => `${row.kind}:${row.key ?? row.id}`}
          />
        </ScrollRegion>
      )}
    </Stack>
  );
}

/**
 * The drill of the current level, as of the report's day. DOMAIN: its epics. EPIC and TEAM have nothing further (the whole unit is
 * never read, so there is no domains-and-teams level). A row's NAME is the way in — a real link to the same report re-scoped to
 * the epic, the period carried along.
 */
export default function EpicProgressRows({ report }: { report: EpicProgressReport }) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const period = report.basis === "PERIOD";
  const link = (scope: EpicScope, label: string, state?: EpicDrillState): ReactNode => (
    <Anchor
      component={RouterLink}
      to={{ pathname: epicProgressPath, search: scopedSearch(params, scope) }}
      state={state}
      size="sm"
      aria-label={t("reports.groups.drillAria", { name: label })}
    >
      {label}
    </Anchor>
  );

  if (report.level === "DOMAIN") {
    const state: EpicDrillState | undefined = report.scope?.key
      ? { domain: { key: report.scope.key, name: report.scope.name } }
      : undefined;
    return (
      <FiguresTable
        title={t("reports.epicProgress.epicsTitle")}
        caption={t("reports.epicProgress.epicsCaption")}
        firstColumn={t("reports.epicProgress.column.epic")}
        rows={report.rows}
        period={period}
        nameCell={(row) =>
          row.key === null ? (
            row.name
          ) : (
            <Text size="sm" component="span">
              {link({ epicId: row.key }, row.key, state)}
              <JiraKeyLink issueKey={row.key} iconOnly />
              {row.name !== row.key && (
                <Text size="sm" c="dimmed" component="span">
                  {" "}
                  {row.name}
                </Text>
              )}
            </Text>
          )
        }
      />
    );
  }
  return null;
}
