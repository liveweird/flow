import type { UseQueryResult } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Alert, Drawer, Select, Stack, Text, VisuallyHidden } from "@mantine/core";
import type { ReportDrillItems } from "../api/reportDrill";
import { personLabel } from "../utils/commitmentsReport";
import { drillItemColumns, type DrillAnchor, type DrillUnit } from "../utils/reportDrill";
import { formatMd } from "../utils/reportFormat";
import classes from "../theme.module.css";
import ColumnTable from "./ColumnTable";
import ErrorAlert from "./ErrorAlert";
import JiraKeyLink from "./JiraKeyLink";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";

/** A picked person of a stacked chart, as the drawer's person select lists them (numbered like the chart's legend). */
interface DrillPerson {
  accountId: string;
  label: string;
  position: number;
}

/**
 * The pane's width: the whole screen on a phone, otherwise three quarters of it between 60rem and 90rem. The widest table it holds
 * (Issue · Kind · Summary · Assignee · Sprint completed · figure) fits in 60rem with the summary wrapping, so it no longer scrolls
 * sideways; a `calc()` passes through Mantine's size handling untouched.
 */
const DRAWER_SIZE = "calc(min(100vw, 90rem, max(60rem, 75vw)))";

/** The select's value for "the whole bar" — never an account id. */
const EVERYONE = "*";

export interface ReportDrillDrawerProps {
  opened: boolean;
  onClose: () => void;
  /** What is listed, the pane's heading, e.g. "Delivered". */
  title: string;
  /** The bucket / filter context under the heading, dimmed, e.g. "2026-09-07". The dialog's name reads "{title} · {subtitle}". */
  subtitle?: string;
  /** A dimmed line under the title (Sprint consistency's "live figures" note while a slice is active). */
  caption?: string;
  query: UseQueryResult<ReportDrillItems>;
  unit: DrillUnit;
  anchor: DrillAnchor;
  /** The configured zone the date column is read in. */
  timeZone: string;
  /** The item count the chart (or its table) shows for this bar: a different answer means the data changed since it was drawn. */
  chartCount?: number;
  /**
   * Whether the list may mix tasks and epics (blocked time's `itemKind=BOTH`): the Kind column is then shown for EVERY bar of the
   * request, so the columns never shift between a bucket that happens to hold both kinds and one that holds a single kind.
   */
  showKind?: boolean;
  /** The picked people of a stacked bar; with two or more the drawer offers a select that narrows the list to one person. */
  people?: readonly DrillPerson[];
  segmentAccountId?: string;
  onSegmentChange?: (accountId: string | undefined) => void;
  /** Whether the person select offers "Everyone". Compare people's lists are always one person's (the API requires the segment), so it does not. */
  allowEveryone?: boolean;
}

/**
 * The items behind one bar, in a wide right-hand drawer the report page mounts (so the filters, the scroll position and the chart
 * are exactly as they were when it closes). The summary line is the SERVER's own recomputation of the bar over the listed
 * items; if it disagrees with the chart's figure an orange note says the data changed since the chart was drawn. Issue keys
 * link to Jira in a new tab (`JiraKeyLink`). The drawer is a flex column, so the table's scroller takes all the height left
 * under the summary and scrolls inside only when the list is longer than that. Escape and the X close it (as does browser Back, through
 * `useReportDrill`, which also returns focus, hence `returnFocus={false}`). The title is the drawer's own `h2`, the dialog's accessible
 * name: the heading text, a visually hidden " · " and the dimmed subtitle, so the name reads "Delivered · 2026-09-07".
 */
export default function ReportDrillDrawer({
  opened,
  onClose,
  title,
  subtitle,
  caption,
  query,
  unit,
  anchor,
  timeZone,
  chartCount,
  showKind = false,
  people = [],
  segmentAccountId,
  onSegmentChange,
  allowEveryone = true,
}: ReportDrillDrawerProps) {
  const { t } = useTranslation();
  const data = query.data;
  const changed = data !== undefined && chartCount !== undefined && chartCount !== data.bar.count;
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={DRAWER_SIZE}
      classNames={{ content: classes.drillDrawerContent, body: classes.drillDrawerBody, title: classes.drillDrawerTitle }}
      title={
        <>
          {title}
          {subtitle && (
            <>
              <VisuallyHidden>{" · "}</VisuallyHidden>
              <span className={classes.drillDrawerSubtitle}>{subtitle}</span>
            </>
          )}
        </>
      }
      returnFocus={false}
      trapFocus
      closeButtonProps={{ "aria-label": t("common.action.close") }}
    >
      <Stack gap="sm" className={classes.drillDrawerFill}>
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
              ...(allowEveryone ? [{ value: EVERYONE, label: t("reports.drill.everyone") }] : []),
              ...people.map((person) => ({ value: person.accountId, label: personLabel(person.position, person.label) })),
            ]}
            value={segmentAccountId ?? (allowEveryone ? EVERYONE : null)}
            onChange={(value) => onSegmentChange(value === null || value === EVERYONE ? undefined : value)}
          />
        )}
        {query.error ? (
          <ErrorAlert error={query.error} />
        ) : data === undefined ? (
          <LoadingBlock />
        ) : (
          <Stack gap="sm" className={classes.drillDrawerFill}>
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
              <ScrollRegion label={t("reports.drill.tableAria")} minWidth={520} fill>
                <ColumnTable
                  aria-label={t("reports.drill.tableAria")}
                  columns={drillItemColumns(t, unit, anchor, timeZone, showKind, (item) => <JiraKeyLink issueKey={item.issueKey} />)}
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
      </Stack>
    </Drawer>
  );
}
