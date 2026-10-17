import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import { personLabel } from "../utils/commitmentsReport";
import type { DistributionPerson } from "../utils/distributionPeople";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import type { GroupColumn, GroupIdentity } from "./ReportGroupsTable";

/**
 * A report's USER-level `groups` as "Totals per person": one row per picked person with the page's own group columns. A user
 * level has nothing further to drill, so the name is plain text — numbered like the histograms' legend and columns ("2. Bob")
 * when [people] knows the person (a person is told by number, not colour), the bare name otherwise (no chart to match it to).
 */
export default function ReportPersonTotals<G extends GroupIdentity>({
  groups,
  people,
  columns,
}: {
  groups: ReadonlyArray<G>;
  people: readonly DistributionPerson[];
  columns: ReadonlyArray<GroupColumn<G>>;
}) {
  const { t } = useTranslation();
  if (groups.length === 0) return null;
  const positions = new Map(people.map((person) => [person.accountId, person.position]));
  const title = t("reports.groups.personTotals");
  const withName: ColumnDef<G>[] = [
    {
      key: "name",
      header: t("reports.groups.member"),
      render: (group) => {
        const name = group.label ?? group.accountId ?? t("reports.groups.unassigned");
        const position = group.accountId ? positions.get(group.accountId) : undefined;
        return <Text size="sm">{position === undefined ? name : personLabel(position, name)}</Text>;
      },
    },
    ...columns.map((column) => ({ ...column, align: "right" as const })),
  ];
  return (
    <Stack gap="xs">
      <Title order={3} size="h4">
        {title}
      </Title>
      <ColumnTable
        aria-label={title}
        columns={withName}
        rows={groups}
        rowKey={(group) => `${group.accountId ?? "-"}:${group.label ?? "-"}`}
      />
    </Stack>
  );
}
