import { useTranslation } from "react-i18next";
import { Table, Text } from "@mantine/core";
import type { BlockedTopItem, ReportFilters } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { formatDays, formatPercent, teamLabel } from "../utils/reportFormat";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/** The most-blocked finished items, in the order the server sends them; the kind column only when epics are in. */
export default function BlockedTopItemsTable({
  items,
  filters,
}: {
  items: ReadonlyArray<BlockedTopItem>;
  filters: ReportFilters;
}) {
  const { t } = useTranslation();
  const showKind = items.some((item) => item.itemKind === "EPIC");
  const teamName = (item: BlockedTopItem) =>
    item.teamId == null
      ? t(item.itemKind === "EPIC" ? "reports.groups.noOwner" : "reports.groups.unassigned")
      : teamLabel(item.teamId, filters.teams);
  return (
    <ScrollRegion label={t("reports.blockedTime.topTableLabel")} minWidth={640}>
      <Table aria-label={t("reports.blockedTime.topTableLabel")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.blockedTime.column.item")}</Table.Th>
            {showKind && <Table.Th>{t("reports.blockedTime.column.kind")}</Table.Th>}
            <Table.Th>{t("reports.blockedTime.column.team")}</Table.Th>
            <Table.Th>{t("reports.blockedTime.column.done")}</Table.Th>
            <Table.Th ta="right">{t("reports.blockedTime.column.blocked")}</Table.Th>
            <Table.Th ta="right">{t("reports.blockedTime.column.cycle")}</Table.Th>
            <Table.Th ta="right">{t("reports.blockedTime.column.share")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {items.map((item) => (
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
              <Table.Td>{teamName(item)}</Table.Td>
              <Table.Td>{formatDate(item.doneAt, MISSING, filters.timeZone)}</Table.Td>
              <Table.Td ta="right">{formatDays(item.blockedWorkingDays)}</Table.Td>
              <Table.Td ta="right">{item.cycleWorkingDays == null ? MISSING : formatDays(item.cycleWorkingDays)}</Table.Td>
              <Table.Td ta="right">{item.share == null ? MISSING : formatPercent(item.share)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollRegion>
  );
}
