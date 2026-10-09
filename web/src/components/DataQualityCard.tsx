import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Badge, Box, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import type { ReportFilters } from "../api/reports";
import { cardAnchor, connectionName, moreCount } from "../utils/dataQualityReport";
import { dataSourceMetricsConfigPath } from "../utils/dataSourceLinks";
import classes from "../theme.module.css";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/** Every card of the page — the key of its `reports.dataQuality.cards.*` text and of its anchor. */
export type CardId =
  | "coverage"
  | "hours"
  | "late"
  | "authors"
  | "noEstimate"
  | "noEpic"
  | "noWorkCategory"
  | "unassigned"
  | "epicsNoEstimate"
  | "epicsNoDates"
  | "epicsHorizon"
  | "epicDrift"
  | "outsideSprint"
  | "crossDomain"
  | "snapshotDrift"
  | "domainsNoOwner"
  | "unmappedStatuses"
  | "unmappedBoards"
  | "itemsAboveEpic"
  | "deriveWarnings";

/** What a card needs to know about the request: who is reading it, at which level, and what the filters can name. */
export interface DataQualityScope {
  filters: ReportFilters;
  level: "UNIT" | "TEAM" | "USER";
  /** A real team is selected (not the whole unit, not the unassigned bucket): some findings belong to no team. */
  teamScoped: boolean;
  admin: boolean;
}

/** found = something to fix (orange, a soft finding); none = checked and clean (teal); na = not measured here (gray); info = a figure, not a finding. */
export type CardState = "found" | "none" | "na" | "info";

/** Why a team view can show less than the unit: the finding belongs to the connection, or to no team at all. */
export type ScopeNote = "notTeam" | "noTeam" | "boardList";

export default function DataQualityCard({
  id,
  state,
  count = 0,
  scope,
  scopeNote,
  naText,
  children,
}: {
  id: CardId;
  state: CardState;
  /** The finding's size — the badge number when `found`. */
  count?: number;
  scope: DataQualityScope;
  scopeNote?: ScopeNote;
  /** Why the finding is not measured (state `na`), shown instead of the body. */
  naText?: string;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const titleId = `${cardAnchor(id)}-title`;
  return (
    <Paper
      role="group"
      className={classes.reportCard}
      withBorder
      p="md"
      id={cardAnchor(id)}
      tabIndex={-1}
      aria-labelledby={titleId}
      data-state={state}
    >
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <Stack gap={2}>
            <Title order={3} size="h4" id={titleId}>
              {t(`reports.dataQuality.cards.${id}.title`)}
            </Title>
            <Text size="sm" c="dimmed">
              {t(`reports.dataQuality.cards.${id}.explain`)}
            </Text>
          </Stack>
          {state === "found" && (
            <Badge color="orange" style={{ flexShrink: 0 }}>
              {t("reports.dataQuality.state.found", { count })}
            </Badge>
          )}
          {state === "none" && (
            <Badge color="teal" style={{ flexShrink: 0 }}>
              {t("reports.dataQuality.state.none")}
            </Badge>
          )}
          {state === "na" && (
            <Badge color="gray" style={{ flexShrink: 0 }}>
              {t("reports.dataQuality.state.na")}
            </Badge>
          )}
        </Group>
        {state === "na" ? (
          naText && (
            <Text size="sm" c="dimmed">
              {naText}
            </Text>
          )
        ) : (
          <>
            {scope.teamScoped && scopeNote && (
              <Text size="xs" c="dimmed">
                {t(`reports.dataQuality.scope.${scopeNote}`)}
              </Text>
            )}
            {children}
          </>
        )}
      </Stack>
    </Paper>
  );
}

/** A row of labelled figures — the counts a card states before its list. */
export function QualityStats({ items }: { items: ReadonlyArray<readonly [string, string]> }) {
  return (
    <Group gap="lg">
      {items.map(([label, value]) => (
        <Box key={label}>
          <Text size="xs" c="dimmed">
            {label}
          </Text>
          <Text fw={600}>{value}</Text>
        </Box>
      ))}
    </Group>
  );
}

export type QualityColumn<T> = ColumnDef<T>;

/**
 * The capped list of a finding as a table, with "and N more" when the server matched more than it
 * sent (`total > items.length`). Renders nothing for an empty list — the card's badge already says none
 * was found.
 */
export function CappedTable<T>({
  label,
  columns,
  rows,
  total,
  rowKey,
  note,
}: {
  /** The table's accessible name (the card's title). */
  label: string;
  columns: ReadonlyArray<QualityColumn<T>>;
  rows: ReadonlyArray<T>;
  /** How many matched — `rows` carries the first of them. Omit for a list the server sends whole. */
  total?: number;
  rowKey: (row: T, index: number) => string;
  note?: ReactNode;
}) {
  const { t } = useTranslation();
  if (rows.length === 0) return null;
  const more = moreCount({ total: total ?? rows.length, items: rows });
  return (
    <Stack gap="xs">
      <ScrollRegion label={t("reports.dataQuality.tableLabel", { name: label })} minWidth={520}>
        <ColumnTable
          verticalSpacing={4}
          aria-label={t("reports.dataQuality.tableLabel", { name: label })}
          columns={columns}
          rows={rows}
          rowKey={rowKey}
        />
      </ScrollRegion>
      {more > 0 && (
        <Text size="sm" c="dimmed">
          {t("reports.dataQuality.more", { count: more, shown: rows.length })}
        </Text>
      )}
      {note}
    </Stack>
  );
}

/**
 * A connection's name in a configuration finding. For an administrator it is the way into that
 * connection's metrics configuration (the finding is theirs to fix); everyone else reads the name only —
 * the page is readable by all (D12), the configuration is not.
 */
export function ConnectionCell({
  connectionId,
  scope,
  name: serverName,
}: {
  connectionId: number;
  scope: DataQualityScope;
  /** The name the server sent with the row, when it sends one; else it is looked up in the reference data. */
  name?: string;
}) {
  const { t } = useTranslation();
  const name = serverName ?? connectionName(scope.filters, connectionId);
  if (!scope.admin) return <Text size="sm">{name}</Text>;
  return (
    <Anchor
      component={RouterLink}
      to={dataSourceMetricsConfigPath(connectionId)}
      size="sm"
      aria-label={t("reports.dataQuality.admin.configAria", { name })}
    >
      {name}
    </Anchor>
  );
}
