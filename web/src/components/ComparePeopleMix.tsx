import { useTranslation } from "react-i18next";
import { Stack, Table, Text, Title } from "@mantine/core";
import type { CompareMixRow, ComparePeopleReport, ReportFilters } from "../api/reports";
import { mixKeyLabel } from "../utils/comparePeopleReport";
import type { CompareMixBy, MixDrillTarget } from "../utils/reportDrill";
import { formatMd, formatPercent } from "../utils/reportFormat";
import DrillFigure from "./DrillFigure";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/** Opens the items behind one person's share of one work-mix key (the page's drill). */
export interface MixDrill {
  onOpen: (target: MixDrillTarget, trigger: HTMLElement) => void;
  disabled: boolean;
}

const SLICES: ReadonlyArray<CompareMixBy> = ["activityType", "workCategory", "domain"];

/**
 * One slice of the work mix: the delivered tasks of the two people grouped by activity type, work category or domain. Neutral by design —
 * there is no better mix — so there is no difference column and no cue; a person's share is a button to the tasks behind it (a key with
 * no value, such as a task without an activity type, cannot be sliced to and stays plain text).
 */
function MixTable({
  by,
  rows,
  report,
  filters,
  drill,
}: {
  by: CompareMixBy;
  rows: readonly CompareMixRow[];
  report: ComparePeopleReport;
  filters: ReportFilters;
  drill: MixDrill;
}) {
  const { t } = useTranslation();
  const title = t(`reports.comparePeople.mix.${by}`);
  const headers = report.people.map((person, index) => t("reports.comparePeople.side", { side: index === 0 ? "A" : "B", name: person.label }));
  return (
    <Stack gap="xs">
      <Title order={4} size="h5">
        {title}
      </Title>
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("reports.comparePeople.mix.empty")}
        </Text>
      ) : (
        <ScrollRegion label={title} minWidth={420}>
          <Table aria-label={title} verticalSpacing={6}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">{title}</Table.Th>
                {headers.map((header) => (
                  <Table.Th key={header} scope="col" ta="right">
                    {header}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => {
                const name = mixKeyLabel(by, row.key, t, filters.domains);
                return (
                  <Table.Tr key={row.key ?? ""}>
                    <Table.Th scope="row" fw={400}>
                      {name}
                    </Table.Th>
                    {([row.a, row.b] as const).map((share, index) => {
                      const figure = share.share === null || share.share === undefined ? MISSING : formatPercent(share.share);
                      const key = row.key;
                      return (
                        <Table.Td key={index === 0 ? "a" : "b"} ta="right">
                          <Stack gap={0} align="flex-end">
                            <Text size="sm" fw={500} component="span">
                              <DrillFigure
                                label={t("reports.drill.figureAria", { figure, column: headers[index], name: `${title}: ${name}` })}
                                count={key === null ? 0 : share.items}
                                disabled={drill.disabled}
                                onOpen={
                                  key === null
                                    ? undefined
                                    : (trigger) => drill.onOpen({ kind: "mix", by, key, segmentAccountId: report.people[index].accountId }, trigger)
                                }
                              >
                                {figure}
                              </DrillFigure>
                            </Text>
                            <Text size="xs" c="dimmed" component="span">
                              {t("reports.comparePeople.mix.cell", { md: formatMd(share.md), count: share.items })}
                            </Text>
                          </Stack>
                        </Table.Td>
                      );
                    })}
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </ScrollRegion>
      )}
    </Stack>
  );
}

/** The three slices of the work mix, one small table each. */
export default function ComparePeopleMix({
  report,
  filters,
  drill,
}: {
  report: ComparePeopleReport;
  filters: ReportFilters;
  drill: MixDrill;
}) {
  return (
    <Stack gap="lg">
      {SLICES.map((by) => (
        <MixTable key={by} by={by} rows={report.workMix[by]} report={report} filters={filters} drill={drill} />
      ))}
    </Stack>
  );
}
