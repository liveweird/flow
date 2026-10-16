import { lazy, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Box, Chip, Group, Paper, SegmentedControl, Stack, Switch, Text } from "@mantine/core";
import { IconInfoCircle, IconUsers } from "@tabler/icons-react";
import { getWipReport } from "../api/reports";
import { ApiError } from "../api/http";
import DailyTableDisclosure from "../components/DailyTableDisclosure";
import EmptyState from "../components/EmptyState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportNote from "../components/ReportNote";
import ReportTabs from "../components/ReportTabs";
import { WipDailyTable, WipPeopleDailyTable, WipPeopleSummaryTable, WipSummaryTable } from "../components/WipTables";
import { useReportPage } from "../hooks/useReportPage";
import { dropReportSpecific, normalizeWipFilter, wipColumnAvailable, type ReportFilterState } from "../utils/reportFilter";
import { FLOW_TABS, reportSpecificKeep, wipPath } from "../utils/reportLinks";
import { DEFAULT_HIDDEN_BANDS, paintBands, paintPeople, wipBands, wipChartRows, wipPeople, wipPeopleChartRows } from "../utils/wipReport";

// The chart (and with it recharts) rides its own lazy chunk.
const WipChart = lazy(() => import("../components/WipChart"));

// WIP reads the task's own domain (no domain view), takes a domain but never one WITH a team (the
// daily aggregate has no team × domain split), and answers 400 to an activity type or work category. It is never read for
// the whole unit either: exactly one of a specific team or a specific domain (`teamRequired` + `teamOrDomain`). Under a team the member
// control picks up to eight people (`multiMember`): the server computes their WIP at query time, inside that team.
const CONTROLS: ReportControls = {
  teamRequired: true,
  teamOrDomain: true,
  domain: true,
  domainExcludesTeam: true,
  multiMember: true,
  wipBy: true,
  itemKind: true,
};

/** The USER-level chart: the union of the ticked bands (stacked by band) or one stack segment per picked person. */
type WipView = "bands" | "people";

/** Off the request and the URL: what the page has no control for (a `400` for an activity type or work category), then team-over-domain and the column rule. */
const normalizeWipPage = (filter: ReportFilterState) => normalizeWipFilter(dropReportSpecific(filter, reportSpecificKeep(wipPath)));

/** Report 9 — WIP: items per stage, status or board column at the end of every day. */
export default function ReportWip() {
  const { t } = useTranslation();
  // The request is always explicit — what to key by and which items. `normalizeWipPage` (URL
  // included) already dropped what the server would answer 400: params the page has no control for, a domain
  // beside a team, a column keying without one team; `CONTROLS` gives a link naming neither a team (the default one).
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "wip",
    (requested) => getWipReport({ ...requested, by: requested.by ?? "STAGE", itemKind: requested.itemKind ?? "TASK" }),
    normalizeWipPage,
    CONTROLS,
  );
  const report = query.data;
  // The ticked bands: local to the page, remembered per keying — and, for columns, per team (a status
  // list is not a column list, and neither is one board's the next board's).
  const [hiddenState, setHiddenState] = useState<{ scope: string; keys: readonly string[] } | null>(null);
  const [workingDaysOnly, setWorkingDaysOnly] = useState(false);
  const [view, setView] = useState<WipView>("bands");

  const bands = report ? wipBands(report.by, report.keys, t) : [];
  const scope = report ? (report.by === "COLUMN" ? `COLUMN|${filter.teamId}` : report.by) : "";
  const hidden = report ? (hiddenState?.scope === scope ? hiddenState.keys : DEFAULT_HIDDEN_BANDS[report.by]) : [];
  const shown = bands.filter((band) => !hidden.includes(band.key));
  const chartSeries = report && workingDaysOnly ? report.series.filter((point) => point.isWorkingDay) : (report?.series ?? []);
  // The picked people (USER level only). With two or more the chart can also be read per person; one person IS the union.
  const people = report ? wipPeople(report.series) : [];
  const byPerson = view === "people" && people.length > 1;
  const shownKeys = shown.map((band) => band.key);

  // A team whose board is not mapped: the 400 a column request for one team meets. Only a request
  // that WAS for columns can mean it — any other 400 (an unknown sprint, …) is a plain failure.
  const noBoard =
    query.error instanceof ApiError && query.error.status === 400 && filter.by === "COLUMN" && wipColumnAvailable(filter);

  // Neither a team nor a domain exists to read (the hook gives a team whenever there is one): nothing to pick; a domain but no
  // team yet leaves the bar up with a prompt, since the query waits for a pick.
  const nothingToRead = filters !== undefined && filters.teams.length === 0 && filters.domains.length === 0;
  const needsPick = filters !== undefined && !nothingToRead && filter.teamId === undefined && filter.domain === undefined;

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.wip.title")} description={t("reports.wip.description")} />
      <ReportTabs tabs={FLOW_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {nothingToRead && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.wip.noTeams")} />
        </Paper>
      )}
      {filters && !nothingToRead && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          {report?.note && <ReportNote note={report.note} derivedAt={report.meta.derivedAt} />}
          {needsPick ? (
            <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
              {t("reports.wip.pickScope")}
            </Alert>
          ) : noBoard ? (
            <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
              {t("reports.wip.columnNeedsBoard")}
            </Alert>
          ) : (
            <ReportChartCard
              title={t("reports.wip.chartTitle")}
              caption={report && `${t(`reports.wip.caption.${report.by}`)}${report.itemKind === "BOTH" ? ` ${t("reports.wip.bothNote")}` : ""}`}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report?.series.length === 0}
            >
              {report && (
                <Stack gap="md">
                  <Group gap="lg" align="flex-start" wrap="wrap">
                    <Box role="group" aria-labelledby="wip-bands">
                      <Text size="sm" fw={500} mb={4} id="wip-bands">
                        {t("reports.wip.show")}
                      </Text>
                      <Chip.Group
                        multiple
                        value={shown.map((band) => band.key)}
                        onChange={(keys) =>
                          setHiddenState({
                            scope,
                            keys: bands.filter((band) => !keys.includes(band.key)).map((band) => band.key),
                          })
                        }
                      >
                        <Group gap="xs">
                          {bands.map((band) => (
                            <Chip key={band.name} value={band.key} size="sm">
                              {band.label}
                            </Chip>
                          ))}
                        </Group>
                      </Chip.Group>
                    </Box>
                    <Switch
                      mt={22}
                      label={t("reports.wip.workingDaysOnly")}
                      checked={workingDaysOnly}
                      onChange={(event) => setWorkingDaysOnly(event.currentTarget.checked)}
                    />
                  </Group>
                  {people.length > 1 && (
                    <SegmentedControl
                      size="xs"
                      aria-label={t("reports.wip.view.label")}
                      value={view}
                      onChange={(value) => setView(value as WipView)}
                      data={[
                        { value: "bands", label: t("reports.wip.view.bands") },
                        { value: "people", label: t("reports.wip.view.people") },
                      ]}
                    />
                  )}
                  {report.meta.level === "USER" && (
                    <Text size="xs" c="dimmed">
                      {t(byPerson ? "reports.wip.userNotePeople" : "reports.wip.userNote")}
                    </Text>
                  )}
                  {shown.length === 0 ? (
                    <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
                      {t("reports.wip.noneShown")}
                    </Alert>
                  ) : (
                    <Suspense fallback={<LoadingBlock />}>
                      {byPerson ? (
                        <WipChart
                          rows={wipPeopleChartRows(chartSeries, people, shownKeys)}
                          bands={paintPeople(people)}
                          label={t("reports.wip.peopleChartLabel")}
                        />
                      ) : (
                        <WipChart rows={wipChartRows(chartSeries, shown)} bands={paintBands(report.by, shown)} />
                      )}
                    </Suspense>
                  )}
                  {byPerson ? (
                    <WipPeopleSummaryTable series={report.series} people={people} keys={shownKeys} />
                  ) : (
                    <WipSummaryTable series={report.series} bands={bands} />
                  )}
                  <Text size="xs" c="dimmed">
                    {t("reports.wip.summaryNote")}
                  </Text>
                  <DailyTableDisclosure>
                    {byPerson ? (
                      <WipPeopleDailyTable series={report.series} people={people} keys={shownKeys} />
                    ) : (
                      <WipDailyTable series={report.series} bands={bands} />
                    )}
                  </DailyTableDisclosure>
                </Stack>
              )}
            </ReportChartCard>
          )}
        </>
      )}
    </Stack>
  );
}
