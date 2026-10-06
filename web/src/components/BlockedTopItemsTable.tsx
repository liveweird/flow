import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { BlockedTopItem, ReportFilters } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { formatDays, formatPercent, teamLabel } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
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
  const columns: ColumnDef<BlockedTopItem>[] = [
    {
      key: "item",
      header: t("reports.blockedTime.column.item"),
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
            header: t("reports.blockedTime.column.kind"),
            render: (item: BlockedTopItem) => t(`reports.kind.${item.itemKind}`),
          },
        ]
      : []),
    { key: "team", header: t("reports.blockedTime.column.team"), render: teamName },
    {
      key: "done",
      header: t("reports.blockedTime.column.done"),
      render: (item) => formatDate(item.doneAt, MISSING, filters.timeZone),
    },
    {
      key: "blocked",
      header: t("reports.blockedTime.column.blocked"),
      render: (item) => formatDays(item.blockedWorkingDays),
      align: "right",
    },
    {
      key: "cycle",
      header: t("reports.blockedTime.column.cycle"),
      render: (item) => (item.cycleWorkingDays == null ? MISSING : formatDays(item.cycleWorkingDays)),
      align: "right",
    },
    {
      key: "share",
      header: t("reports.blockedTime.column.share"),
      render: (item) => (item.share == null ? MISSING : formatPercent(item.share)),
      align: "right",
    },
  ];
  return (
    <ScrollRegion label={t("reports.blockedTime.topTableLabel")} minWidth={640}>
      <ColumnTable
        aria-label={t("reports.blockedTime.topTableLabel")}
        columns={columns}
        rows={items}
        rowKey={(item) => `${item.itemKind}:${item.issueKey}`}
      />
    </ScrollRegion>
  );
}
