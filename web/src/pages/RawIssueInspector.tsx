import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useParams, useSearchParams } from "react-router-dom";
import { Alert, Badge, Box, Button, Code, Group, Stack, Table, Text, TextInput, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconFileSearch, IconSearch } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { getRawIssue, type RawIssueInspection } from "../api/dataSources";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import { dataSourcePath } from "../utils/dataSourceLinks";
import { formatEpochMillis } from "../utils/dataSourceState";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { saveErrorMessage } from "../utils/saveError";

/** Anomaly codes are a fixed vocabulary (`norm/Tiling.kt`) — badges, never free text. */
function AnomaliesList({ anomalies }: { anomalies: RawIssueInspection["anomalies"] }) {
  const { t } = useTranslation();
  if (!anomalies || anomalies.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("dataSources.inspect.noAnomalies")}
      </Text>
    );
  }
  return (
    <Group gap="xs">
      {anomalies.map((code) => (
        <Badge key={code} color="orange" variant="light">
          {code}
        </Badge>
      ))}
    </Group>
  );
}

function RawJsonList({ entries }: { entries: { [key: string]: unknown }[] | undefined }) {
  const { t } = useTranslation();
  if (!entries || entries.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("dataSources.inspect.none")}
      </Text>
    );
  }
  return (
    <Stack gap="xs">
      {/* Raw JSON entries carry no stable id of their own — the array position is the key. */}
      {entries.map((entry, index) => (
        <Stack gap={2} key={index}>
          <Text size="xs" c="dimmed">
            {t("dataSources.inspect.entryNumber", { index: index + 1 })}
          </Text>
          <Code block>{JSON.stringify(entry, null, 2)}</Code>
        </Stack>
      ))}
    </Stack>
  );
}

function WorkItemSummary({ inspection }: { inspection: RawIssueInspection }) {
  const { t } = useTranslation();
  const workItem = inspection.workItem;
  if (!workItem) {
    return (
      <Text size="sm" c="dimmed">
        {t("dataSources.inspect.notProcessed")}
      </Text>
    );
  }
  return (
    <Table aria-label={t("dataSources.inspect.section.workItem")}>
      <Table.Tbody>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.issueKey")}</Table.Th>
          <Table.Td>{workItem.issueKey}</Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.project")}</Table.Th>
          <Table.Td>{workItem.projectKey}</Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.issueType")}</Table.Th>
          <Table.Td>{workItem.issueType}</Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.status")}</Table.Th>
          <Table.Td>
            {workItem.statusName} ({t(`dataSources.profile.category.${workItem.statusCategory}`)})
          </Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.assignee")}</Table.Th>
          <Table.Td>{workItem.assigneeAccountId ?? "—"}</Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.processedAt")}</Table.Th>
          <Table.Td>{formatEpochMillis(workItem.processedAt, t)}</Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>{t("dataSources.inspect.column.processingVersion")}</Table.Th>
          <Table.Td>{workItem.processingVersion}</Table.Td>
        </Table.Tr>
      </Table.Tbody>
    </Table>
  );
}

function IntervalsSection({ inspection }: { inspection: RawIssueInspection }) {
  const { t } = useTranslation();
  const statusIntervals = inspection.statusIntervals ?? [];
  const fieldIntervals = inspection.fieldIntervals ?? [];
  return (
    <Stack gap="md">
      <Stack gap={4}>
        <Text fw={500} size="sm">
          {t("dataSources.inspect.statusIntervals")}
        </Text>
        {statusIntervals.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t("dataSources.inspect.none")}
          </Text>
        ) : (
          <Table aria-label={t("dataSources.inspect.statusIntervals")}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("dataSources.inspect.column.seq")}</Table.Th>
                <Table.Th>{t("dataSources.profile.column.status")}</Table.Th>
                <Table.Th>{t("dataSources.profile.column.category")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.from")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.to")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.source")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {statusIntervals.map((interval) => (
                <Table.Tr key={interval.seq}>
                  <Table.Td>{interval.seq}</Table.Td>
                  <Table.Td>{interval.statusName}</Table.Td>
                  <Table.Td>{t(`dataSources.profile.category.${interval.category}`)}</Table.Td>
                  <Table.Td>{formatEpochMillis(interval.fromAtMs, t)}</Table.Td>
                  <Table.Td>{formatEpochMillis(interval.toAtMs, t)}</Table.Td>
                  <Table.Td>{interval.source}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
      <Stack gap={4}>
        <Text fw={500} size="sm">
          {t("dataSources.inspect.fieldIntervals")}
        </Text>
        {fieldIntervals.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t("dataSources.inspect.none")}
          </Text>
        ) : (
          <Table aria-label={t("dataSources.inspect.fieldIntervals")}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("dataSources.inspect.column.field")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.seq")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.value")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.from")}</Table.Th>
                <Table.Th>{t("dataSources.inspect.column.to")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {fieldIntervals.map((interval, index) => (
                <Table.Tr key={`${interval.field}-${interval.seq}-${index}`}>
                  <Table.Td>{interval.field}</Table.Td>
                  <Table.Td>{interval.seq}</Table.Td>
                  <Table.Td>{interval.valueText ?? interval.valueId ?? "—"}</Table.Td>
                  <Table.Td>{formatEpochMillis(interval.fromAtMs, t)}</Table.Td>
                  <Table.Td>{formatEpochMillis(interval.toAtMs, t)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
    </Stack>
  );
}

function InspectionResult({ inspection }: { inspection: RawIssueInspection }) {
  const { t } = useTranslation();
  return (
    <Stack gap="lg">
      <Group gap="xs">
        <Title order={3} size="h4">{inspection.issueKey}</Title>
        {inspection.needsProcessing && (
          <Badge color="gray" variant="light">
            {t("dataSources.inspect.needsProcessing")}
          </Badge>
        )}
        {inspection.deletedAt != null && (
          <Badge color="red" variant="light">
            {t("dataSources.inspect.deleted")}
          </Badge>
        )}
        {inspection.movedOutAt != null && (
          <Badge color="red" variant="light">
            {t("dataSources.inspect.movedOut")}
          </Badge>
        )}
      </Group>

      <Stack gap="xs">
        <Title order={4} size="h5">{t("dataSources.inspect.section.workItem")}</Title>
        <WorkItemSummary inspection={inspection} />
      </Stack>

      <Stack gap="xs">
        <Title order={4} size="h5">{t("dataSources.inspect.section.intervals")}</Title>
        <IntervalsSection inspection={inspection} />
      </Stack>

      <Stack gap="xs">
        <Title order={4} size="h5">{t("dataSources.inspect.section.anomalies")}</Title>
        <AnomaliesList anomalies={inspection.anomalies} />
      </Stack>

      <Stack gap="xs">
        <Title order={4} size="h5">{t("dataSources.inspect.section.changelogs", { count: inspection.changelogs?.length ?? 0 })}</Title>
        <RawJsonList entries={inspection.changelogs} />
      </Stack>

      <Stack gap="xs">
        <Title order={4} size="h5">{t("dataSources.inspect.section.worklogs", { count: inspection.worklogs?.length ?? 0 })}</Title>
        <RawJsonList entries={inspection.worklogs} />
      </Stack>

      <Stack gap="xs">
        <Title order={4} size="h5">{t("dataSources.inspect.section.payload")}</Title>
        <Code block>{JSON.stringify(inspection.payload, null, 2)}</Code>
      </Stack>
    </Stack>
  );
}

/**
 * The raw-issue lookup (`/data-sources/:id/inspect`, ADMIN only — plan §9/§10/§11): a key/id
 * input drives `GET .../raw-issues/{issueKey}`, whose URL is the one source of truth for the
 * looked-up key (`?key=`) — a shareable deep link, and the reason this is a query param rather
 * than local-only state. 400 (a malformed key) and 404 (no such issue) render inline; they never
 * replace the whole page the way `EditPageLoadState` does for the details/profile pages, since a
 * bad lookup should not stop the admin from trying another key.
 */
export default function RawIssueInspector() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const idIsValid = Number.isFinite(id) && id > 0;
  const admin = useAdmin();
  const [searchParams, setSearchParams] = useSearchParams();
  const lookupKey = searchParams.get("key") ?? "";
  const [inputValue, setInputValue] = useState(lookupKey);

  const inspection = useQuery({
    queryKey: ["dataSources", "rawIssue", id, lookupKey],
    queryFn: () => getRawIssue(id, lookupKey),
    enabled: idIsValid && admin && lookupKey !== "",
    retry: false,
  });

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = inputValue.trim();
    if (!trimmed) return;
    setSearchParams({ key: trimmed });
  }

  return (
    <Stack gap="md">
      <PageHeader
        title={t("dataSources.inspect.title")}
        backTo={{ to: dataSourcePath(id), label: t("dataSources.details.backToDetails") }}
      />
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack gap="lg">
          <form onSubmit={onSubmit}>
            <Group align="flex-end" gap="sm">
              <TextInput
                label={t("dataSources.inspect.keyLabel")}
                description={t("dataSources.inspect.keyHint")}
                placeholder="ENG-123"
                value={inputValue}
                onChange={(e) => setInputValue(e.currentTarget.value)}
                w={280}
              />
              <Button type="submit" leftSection={<IconSearch size={16} />} loading={inspection.isFetching}>
                {t("dataSources.inspect.lookUp")}
              </Button>
            </Group>
          </form>

          {inspection.isError && (
            <Alert color="red" variant="light">
              {saveErrorMessage(inspection.error, t, {
                invalid: "dataSources.inspect.invalidKey",
                notFound: "dataSources.inspect.notFound",
                failedStatus: "common.error.loadFailedStatus",
                failed: "common.error.network",
              })}
            </Alert>
          )}

          {inspection.data ? (
            <InspectionResult inspection={inspection.data} />
          ) : (
            !inspection.isError &&
            lookupKey === "" && <EmptyState icon={IconFileSearch} label={t("dataSources.inspect.empty")} />
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
