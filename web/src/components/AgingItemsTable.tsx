import { useTranslation } from "react-i18next";
import { Anchor, Badge, Stack, Table, Text } from "@mantine/core";
import { Link as RouterLink, useLocation, useSearchParams } from "react-router-dom";
import type { AgingItem, AgingWipReport, ReportFilters } from "../api/reports";
import { bandPercentileLabel, bandTone, thresholdsFor } from "../utils/agingReport";
import { formatDate } from "../utils/formatDate";
import { formatDays, teamLabel } from "../utils/reportFormat";
import { applyReportFilter, parseReportFilter } from "../utils/reportFilter";
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

  return (
    <Stack gap="xs">
      <ScrollRegion label={t("reports.agingWip.tableLabel")} minWidth={760}>
        <Table aria-label={t("reports.agingWip.tableLabel")}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t("reports.agingWip.column.item")}</Table.Th>
              {showKind && <Table.Th>{t("reports.agingWip.column.kind")}</Table.Th>}
              <Table.Th>{t("reports.agingWip.column.team")}</Table.Th>
              <Table.Th>{t("reports.agingWip.column.assignee")}</Table.Th>
              <Table.Th>{t("reports.agingWip.column.started")}</Table.Th>
              <Table.Th ta="right">{t("reports.agingWip.column.age")}</Table.Th>
              <Table.Th>{t("reports.agingWip.column.blocked")}</Table.Th>
              <Table.Th>{t("reports.agingWip.column.band")}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {report.items.map((item) => (
              <Table.Tr key={`${item.itemKind}:${item.issueKey}`}>
                <Table.Td>
                  <Text size="sm" fw={600}>
                    {item.issueKey}
                  </Text>
                  {item.summary && (
                    <Text size="xs" c="dimmed" lineClamp={2}>
                      {item.summary}
                    </Text>
                  )}
                </Table.Td>
                {showKind && <Table.Td>{t(`reports.kind.${item.itemKind}`)}</Table.Td>}
                <Table.Td>{teamCell(item)}</Table.Td>
                <Table.Td>{assigneeCell(item)}</Table.Td>
                <Table.Td>{formatDate(item.startedAt, MISSING, filters.timeZone)}</Table.Td>
                <Table.Td ta="right">{formatDays(item.ageWorkingDays)}</Table.Td>
                <Table.Td>
                  {item.blocked ? (
                    <Badge color="red" variant="outline">
                      {t("reports.agingWip.blocked")}
                    </Badge>
                  ) : (
                    <Text size="sm" c="dimmed">
                      {MISSING}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>{bandCell(item)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollRegion>
      {report.itemsTruncated && (
        <Text size="xs" c="dimmed">
          {t("reports.agingWip.truncated", { count: report.items.length })}
        </Text>
      )}
    </Stack>
  );
}
