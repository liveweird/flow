import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Badge, Stack, Text, Title } from "@mantine/core";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import type { EpicProgressReport, EpicProgressRow, ReportFilters } from "../api/reports";
import { scopedSearch, type EpicScope, type EvmFigures } from "../utils/epicProgressReport";
import { parseReportFilter } from "../utils/reportFilter";
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
        <Title order={4}>{title}</Title>
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
 * The drill of the current level, as of the report's day. UNIT: the domains (the epic basis — they
 * add up to the headline) and, apart, the teams (the sprint basis — a DIFFERENT view whose rows do
 * NOT add up to it, said in the caption). DOMAIN: its epics. EPIC and TEAM have nothing further.
 * A row's NAME is the way in — a real link to the same report re-scoped, the period carried along;
 * a soft-deleted team keeps its figures but is marked and not linked (its own drill would be a `400`).
 */
export default function EpicProgressRows({ report, filters }: { report: EpicProgressReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const period = report.basis === "PERIOD";
  // A one-sprint period survives a drill only into a team that lists that sprint (the bar's `changeTeam` rule).
  const sprintId = parseReportFilter(params).sprintId;
  const teamListsSprint = (teamId: number) =>
    sprintId !== undefined && (filters.teams.find((team) => team.id === teamId)?.sprints.some((sprint) => sprint.sprintId === sprintId) ?? false);
  const link = (scope: EpicScope, label: string, state?: EpicDrillState): ReactNode => (
    <Anchor
      component={RouterLink}
      to={{
        pathname: epicProgressPath,
        search: scopedSearch(params, scope, { dropSprint: scope.teamId !== undefined && !teamListsSprint(scope.teamId) }),
      }}
      state={state}
      size="sm"
      aria-label={t("reports.groups.drillAria", { name: label })}
    >
      {label}
    </Anchor>
  );

  if (report.level === "UNIT") {
    const domains = report.rows.filter((row) => row.kind === "DOMAIN");
    const teams = report.rows.filter((row) => row.kind === "TEAM");
    return (
      <Stack gap="lg">
        <FiguresTable
          title={t("reports.epicProgress.domainsTitle")}
          caption={t("reports.epicProgress.domainsCaption")}
          firstColumn={t("reports.epicProgress.column.domain")}
          rows={domains}
          period={period}
          nameCell={(row) => (row.key === null ? row.name : link({ domain: row.key }, row.name))}
        />
        <FiguresTable
          title={t("reports.epicProgress.teamsTitle")}
          caption={t("reports.epicProgress.teamsCaption")}
          firstColumn={t("reports.epicProgress.column.team")}
          rows={teams}
          period={period}
          nameCell={(row) =>
            row.active === false || row.id === null ? (
              <Text size="sm" component="span">
                {row.name} <Badge color="gray">{t("reports.epicProgress.deletedTeam")}</Badge>
              </Text>
            ) : (
              link({ teamId: row.id }, row.name)
            )
          }
        />
      </Stack>
    );
  }
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
