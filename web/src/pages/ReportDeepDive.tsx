import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { Alert, Group, List, Stack, Switch, Tabs, Text, Title } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { fetchDeepDive, getReportFilters } from "../api/reports";
import DeepDiveBurnup from "../components/DeepDiveBurnup";
import DeepDiveLimits from "../components/DeepDiveLimits";
import DeepDiveMatrix from "../components/DeepDiveMatrix";
import DeepDiveSelectionPanel from "../components/DeepDiveSelectionPanel";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import type { DeepDiveLayers } from "../utils/deepDiveCell";
import {
  applyDeepDiveSelection,
  applyDeepDiveView,
  deepDiveMode,
  deepDiveQuery,
  parseDeepDiveSelection,
  parseDeepDiveView,
} from "../utils/deepDiveFilter";
import { noteOf } from "../utils/deepDiveMatrix";
import { knownLabels } from "../utils/deepDivePanel";

const ALL_LAYERS: DeepDiveLayers = { pv: true, exec: true, cost: true };
const LAYER_KEYS = ["pv", "exec", "cost"] as const;
const MODES = ["SPRINTS", "EPICS", "TASKS"] as const;

const isLayers = (value: unknown): value is DeepDiveLayers =>
  typeof value === "object" && value !== null && LAYER_KEYS.every((key) => isBoolean((value as Record<string, unknown>)[key]));

/** The server's `400` for a selection says what is wrong with it (too many tasks, an unknown epic, …) in plain prose; shown as written. */
function SelectionError({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (error instanceof ApiError && error.status === 400 && error.detail !== undefined) {
    return (
      <Alert color="red" variant="light" title={t("reports.deepDive.badSelection")}>
        {error.detail}
      </Alert>
    );
  }
  return <ErrorAlert error={error} />;
}

function Explainer() {
  const { t } = useTranslation();
  return (
    <Stack gap="xs" component="section" aria-labelledby="deep-dive-explainer">
      <Title order={3} size="h4" id="deep-dive-explainer">
        {t("reports.deepDive.explainer.title")}
      </Title>
      <Text size="sm" c="dimmed">
        {t("reports.deepDive.explainer.intro")}
      </Text>
      <List size="sm" spacing="xs">
        {MODES.map((mode) => (
          <List.Item key={mode}>
            <Text span fw={600}>
              {t(`reports.deepDive.panel.mode.${mode}`)}
            </Text>
            {": "}
            {t(`reports.deepDive.explainer.${mode}`)}
          </List.Item>
        ))}
      </List>
    </Stack>
  );
}

/** Report 17 — Deep dive: plan, execution and cost on one epic/task × time matrix, for a selection the URL carries. */
export default function ReportDeepDive() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const selection = useMemo(() => parseDeepDiveSelection(params), [params]);
  const selectionKey = deepDiveQuery(selection);
  const view = parseDeepDiveView(params);
  const complete = deepDiveMode(selection) !== null;

  // The panel keeps its own draft, so it is reset only when the URL changes from OUTSIDE it (Back/forward, a pasted
  // or followed link): `shown` is the key its own Show button just wrote, consumed by the change that follows.
  const [seenKey, setSeenKey] = useState(selectionKey);
  const [shown, setShown] = useState<string | null>(null);
  const [panelEpoch, setPanelEpoch] = useState(0);
  if (seenKey !== selectionKey) {
    setSeenKey(selectionKey);
    setShown(null);
    if (shown !== selectionKey) setPanelEpoch(panelEpoch + 1);
  }
  const [layers, setLayers] = useStoredState<DeepDiveLayers>("reports.deepDive.layers", ALL_LAYERS, isLayers);
  const [burnupBudget, setBurnupBudget] = useStoredState<boolean>("reports.deepDive.burnupBudget", false, isBoolean);

  const filtersQuery = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000 });
  // The selection is the only input: nothing is requested until it is complete, and a new one is a new key (no kept data —
  // the matrix below is keyed by it too, so a stale grid is never redrawn under the new selection).
  const reportQuery = useQuery({
    queryKey: ["reports", "deep-dive", selectionKey],
    queryFn: () => fetchDeepDive(selection),
    enabled: complete,
  });
  const report = reportQuery.data;
  const note = report === undefined ? null : noteOf(report);
  const labels = useMemo(() => knownLabels(report), [report]);

  let body;
  if (!complete) body = <Explainer />;
  else if (reportQuery.isError) body = <SelectionError error={reportQuery.error} />;
  else if (report === undefined) body = <LoadingBlock />;
  else {
    body = (
      <Stack gap="md">
        {note?.kind === "RANGE_CLAMPED" && (
          <Alert color="orange" variant="light" role="note" icon={<IconInfoCircle size={16} />}>
            {t("reports.deepDive.note.rangeClamped")}
          </Alert>
        )}
        {note?.kind === "OTHER" && (
          <Alert color="gray" variant="light" role="note" icon={<IconInfoCircle size={16} />} title={t("reports.note.title")}>
            {note.text}
          </Alert>
        )}
        {note?.kind === "NOT_DERIVED" ? (
          <Alert color="gray" variant="light" role="note" icon={<IconInfoCircle size={16} />}>
            {t("reports.deepDive.note.notDerived")}
          </Alert>
        ) : (
          <>
            {/* Both views read the one answer. The matrix panel stays mounted (so its open months and rows survive a hop to the
                burn-up); the burn-up renders only while it is open, so its chart is never measured in a hidden box. */}
            <Tabs
              value={view}
              keepMounted
              onChange={(next) => setParams(applyDeepDiveView(params, next === "burnup" ? "burnup" : "matrix"), { replace: true })}
            >
              <Tabs.List aria-label={t("reports.deepDive.viewAria")}>
                <Tabs.Tab value="matrix">{t("reports.deepDive.tab.matrix")}</Tabs.Tab>
                <Tabs.Tab value="burnup">{t("reports.deepDive.tab.burnup")}</Tabs.Tab>
              </Tabs.List>
              <Tabs.Panel value="matrix" pt="md">
                <Stack gap="sm" component="section" aria-labelledby="deep-dive-matrix">
                  <Title order={3} size="h4" id="deep-dive-matrix">
                    {t("reports.deepDive.matrixTitle")}
                  </Title>
                  <Text size="xs" c="dimmed">
                    {t("reports.deepDive.range", { from: report.range.from, to: report.range.to })}
                  </Text>
                  <Group gap="lg" role="group" aria-label={t("reports.deepDive.layersAria")}>
                    {LAYER_KEYS.map((key) => (
                      <Switch
                        key={key}
                        label={t(`reports.deepDive.matrix.layer.${key}`)}
                        checked={layers[key]}
                        onChange={(event) => setLayers({ ...layers, [key]: event.currentTarget.checked })}
                      />
                    ))}
                  </Group>
                  <DeepDiveMatrix key={selectionKey} report={report} layers={layers} />
                </Stack>
              </Tabs.Panel>
              <Tabs.Panel value="burnup" pt="md">
                {view === "burnup" && <DeepDiveBurnup key={selectionKey} report={report} withBudget={burnupBudget} onWithBudgetChange={setBurnupBudget} />}
              </Tabs.Panel>
            </Tabs>
            <DeepDiveLimits report={report} />
          </>
        )}
      </Stack>
    );
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.deepDive.title")} description={t("reports.deepDive.description")} />
      <ReportFiltersStatus query={filtersQuery} />
      {filtersQuery.data && (
        <DeepDiveSelectionPanel
          key={panelEpoch}
          selection={selection}
          filters={filtersQuery.data}
          labels={labels}
          onShow={(next) => {
            const key = deepDiveQuery(next);
            // The same selection again is not a new history entry.
            if (key === selectionKey) return;
            setShown(key);
            setParams(applyDeepDiveSelection(params, next));
          }}
        />
      )}
      {body}
    </Stack>
  );
}
