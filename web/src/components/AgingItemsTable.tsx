import { useTranslation } from "react-i18next";
import { Anchor, Badge, Stack, Text } from "@mantine/core";
import { Link as RouterLink, useLocation, useSearchParams } from "react-router-dom";
import type { AgingItem, AgingWipReport, ReportFilters } from "../api/reports";
import { bandPercentileLabel, bandTone, thresholdsFor } from "../utils/agingReport";
import { formatDate } from "../utils/formatDate";
import { formatDays, teamLabel } from "../utils/reportFormat";
import { applyReportFilter, parseReportFilter } from "../utils/reportFilter";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/**
 * The open work, oldest first — the order the server sends, never re-sorted here. The team (at UNIT
 * level) and the assignee (at TEAM level) are the way in: real links to this report narrowed to
 * them. The band badge is the SERVER's band, its colour only the rank of that threshold (red the
 * highest, orange the next), and its text always says which threshold — colour is never the only
 * carrier. Blocked is a red outline badge with a word. Wide, so it scrolls sideways on a phone.
 */
export default function AgingItemsTable({
  report,
  filters,
}: {
  report: AgingWipReport;
  filters: ReportFilters;
}) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const level = report.meta.level;
  const showKind = report.items.some((item) => item.itemKind === "EPIC");

  const narrowedTo = (change: { teamId?: number; accountId?: string }): string =>
    applyReportFilter(params, { ...parseReportFilter(params), ...change }).toString();

  const teamCell = (item: AgingItem) => {
    if (item.teamId == null) return <Text size="sm" c="dimmed">{item.itemKind === "EPIC" ? t("reports.groups.noOwner") : t("reports.groups.unassigned")}</Text>;
    const name = teamLabel(item.teamId, filters.teams);
    if (level !== "UNIT") return <Text size="sm">{name}</Text>;
    return (
      <Anchor
        component={RouterLink}
        to={{ pathname, search: narrowedTo({ teamId: item.teamId }) }}
        size="sm"
        aria-label={t("reports.groups.drillAria", { name })}
      >
        {name}
      </Anchor>
    );
  };

  const assigneeCell = (item: AgingItem) => {
    const name = item.assignee ?? item.assigneeAccountId;
    if (name == null) return <Text size="sm" c="dimmed">{MISSING}</Text>;
    if (level !== "TEAM" || item.assigneeAccountId == null) return <Text size="sm">{name}</Text>;
    return (
      <Anchor
        component={RouterLink}
        to={{ pathname, search: narrowedTo({ accountId: item.assigneeAccountId }) }}
        size="sm"
        aria-label={t("reports.groups.drillAria", { name })}
      >
        {name}
      </Anchor>
    );
  };

  const bandCell = (item: AgingItem) => {
    if (item.band == null) return <Text size="sm" c="dimmed">{MISSING}</Text>;
    const label =
      item.band === "WITHIN"
        ? t("reports.agingWip.band.within")
        : t("reports.agingWip.band.above", { p: bandPercentileLabel(item.band) });
    return (
      <Badge color={bandTone(item.band, thresholdsFor(item, report))} data-band={item.band}>
        {label}
      </Badge>
    );
  };

  const columns: ColumnDef<AgingItem>[] = [
    {
      key: "item",
      header: t("reports.agingWip.column.item"),
      render: (item) => (
        <>
          <Text size="sm" fw={600}>
            {item.issueKey}
          </Text>
          {item.summary && (
            <Text size="xs" c="dimmed" lineClamp={2}>
              {item.summary}
            </Text>
          )}
        </>
      ),
    },
    ...(showKind
      ? [
          {
            key: "kind",
            header: t("reports.agingWip.column.kind"),
            render: (item: AgingItem) => t(`reports.kind.${item.itemKind}`),
          },
        ]
      : []),
    { key: "team", header: t("reports.agingWip.column.team"), render: teamCell },
    { key: "assignee", header: t("reports.agingWip.column.assignee"), render: assigneeCell },
    {
      key: "started",
      header: t("reports.agingWip.column.started"),
      render: (item) => formatDate(item.startedAt, MISSING, filters.timeZone),
    },
    {
      key: "age",
      header: t("reports.agingWip.column.age"),
      render: (item) => formatDays(item.ageWorkingDays),
      align: "right",
    },
    {
      key: "blocked",
      header: t("reports.agingWip.column.blocked"),
      render: (item) =>
        item.blocked ? (
          <Badge color="red" variant="outline">
            {t("reports.agingWip.blocked")}
          </Badge>
        ) : (
          <Text size="sm" c="dimmed">
            {MISSING}
          </Text>
        ),
    },
    { key: "band", header: t("reports.agingWip.column.band"), render: bandCell },
  ];

  return (
    <Stack gap="xs">
      <ScrollRegion label={t("reports.agingWip.tableLabel")} minWidth={760}>
        <ColumnTable
          aria-label={t("reports.agingWip.tableLabel")}
          columns={columns}
          rows={report.items}
          rowKey={(item) => `${item.itemKind}:${item.issueKey}`}
        />
      </ScrollRegion>
      {report.itemsTruncated && (
        <Text size="xs" c="dimmed">
          {t("reports.agingWip.truncated", { count: report.items.length })}
        </Text>
      )}
    </Stack>
  );
}
