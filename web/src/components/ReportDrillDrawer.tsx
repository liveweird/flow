import type { UseQueryResult } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Alert, Button, Drawer, Select, Stack, Text } from "@mantine/core";
import type { ReportDrillItems } from "../api/reportDrill";
import { personLabel } from "../utils/commitmentsReport";
import { drillItemColumns, type DrillAnchor, type DrillUnit } from "../utils/reportDrill";
import { formatMd } from "../utils/reportFormat";
import ColumnTable from "./ColumnTable";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";

/** A picked person of a stacked chart, as the drawer's person select lists them (numbered like the chart's legend). */
interface DrillPerson {
  accountId: string;
  label: string;
  position: number;
}

/** The select's value for "the whole bar" — never an account id. */
const EVERYONE = "*";

export interface ReportDrillDrawerProps {
  opened: boolean;
  onClose: () => void;
  /** What the open bar is, e.g. "2026-09-07 · Delivered". */
  title: string;
  /** A dimmed line under the title (Sprint consistency's "live figures" note while a slice is active). */
  caption?: string;
  query: UseQueryResult<ReportDrillItems>;
  unit: DrillUnit;
  anchor: DrillAnchor;
  /** The configured zone the date column is read in. */
  timeZone: string;
  /** The item count the chart (or its table) shows for this bar: a different answer means the data changed since it was drawn. */
  chartCount?: number;
  /** The picked people of a stacked bar; with two or more the drawer offers a select that narrows the list to one person. */
  people?: readonly DrillPerson[];
  segmentAccountId?: string;
  onSegmentChange?: (accountId: string | undefined) => void;
}

/**
 * The items behind one bar, in a right-hand drawer the report page mounts (so the filters, the scroll position and the chart
 * are exactly as they were when it closes). The summary line is the SERVER's own recomputation of the bar over the listed
 * items; if it disagrees with the chart's figure an orange note says the data changed since the chart was drawn. Issue keys
 * are plain text (the Aging WIP rule — no Jira links). Escape, the X and the footer button all close it; focus is returned by
 * `useReportDrill`, hence `returnFocus={false}`.
 */
export default function ReportDrillDrawer({
  opened,
  onClose,
  title,
  caption,
  query,
  unit,
  anchor,
  timeZone,
  chartCount,
  people = [],
  segmentAccountId,
  onSegmentChange,
}: ReportDrillDrawerProps) {
  const { t } = useTranslation();
  const data = query.data;
  const changed = data !== undefined && chartCount !== undefined && chartCount !== data.bar.count;
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="xl"
      title={title}
      returnFocus={false}
      trapFocus
      closeButtonProps={{ "aria-label": t("common.action.close") }}
    >
      <Stack gap="sm">
        {caption && (
          <Text size="xs" c="dimmed">
            {caption}
          </Text>
        )}
        {people.length > 1 && onSegmentChange && (
          <Select
            label={t("reports.drill.segmentLabel")}
            allowDeselect={false}
            data={[
              { value: EVERYONE, label: t("reports.drill.everyone") },
              ...people.map((person) => ({ value: person.accountId, label: personLabel(person.position, person.label) })),
            ]}
            value={segmentAccountId ?? EVERYONE}
            onChange={(value) => onSegmentChange(value === null || value === EVERYONE ? undefined : value)}
          />
        )}
        {query.error ? (
          <ErrorAlert error={query.error} />
        ) : data === undefined ? (
          <LoadingBlock />
        ) : (
          <Stack gap="sm">
            <Text size="sm" fw={500}>
              {data.bar.sumMd === null
                ? t("reports.items", { count: data.bar.count })
                : t("reports.drill.summary", { items: t("reports.items", { count: data.bar.count }), md: formatMd(data.bar.sumMd) })}
            </Text>
            {changed && (
              <Alert color="orange" variant="light" role="status">
                {t("reports.drill.changed")}
              </Alert>
            )}
            {data.items.length === 0 ? (
              <Text size="sm" c="dimmed">
                {t("reports.drill.noItems")}
              </Text>
            ) : (
              <ScrollRegion label={t("reports.drill.tableAria")} minWidth={520} maxHeight="60vh">
                <ColumnTable
                  aria-label={t("reports.drill.tableAria")}
                  columns={drillItemColumns(t, unit, anchor, timeZone)}
                  rows={data.items}
                  rowKey={(item, index) => `${item.issueKey}:${index}`}
                  stickyHeader
                />
              </ScrollRegion>
            )}
            {data.truncated && (
              <Text size="xs" c="dimmed">
                {t("reports.drill.truncated", { count: data.items.length, total: data.total })}
              </Text>
            )}
          </Stack>
        )}
        <Button variant="default" onClick={onClose} w="fit-content">
          {t("reports.drill.back")}
        </Button>
      </Stack>
    </Drawer>
  );
}
