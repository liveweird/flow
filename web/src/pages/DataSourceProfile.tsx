import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { Box, Stack, Table, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconReportAnalytics } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import {
  getDataSourceProfile,
  type BoardProfile,
  type CustomFieldProfile,
  type DataProfile,
  type ProjectProfile,
  type WorkflowProfile,
} from "../api/dataSources";
import EditPageLoadState from "../components/EditPageLoadState";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import { dataSourcePath } from "../utils/dataSourceLinks";
import { formatEpochMillis } from "../utils/dataSourceState";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";

/** One decimal place, matching the server's own rounding — never a rendered fraction. */
function percent(value: number): string {
  return `${value.toFixed(1)}%`;
}

/** A section heading + its body; every section is a plain table (plan §8/§10/§11 — no charts). */
function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Stack gap="xs">
      <Title order={3} size="h4">{title}</Title>
      {children}
    </Stack>
  );
}

function ProjectsSection({ projects }: { projects: ProjectProfile[] }) {
  const { t } = useTranslation();
  const rows = projects.flatMap((project) =>
    Object.entries(project.issueCounts).map(([issueType, count]) => ({ project: project.projectKey, issueType, count })),
  );
  if (rows.length === 0) return <NoData />;
  return (
    <Table aria-label={t("dataSources.profile.section.projects")}>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{t("dataSources.profile.column.project")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.issueType")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.count")}</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row) => (
          <Table.Tr key={`${row.project}-${row.issueType}`}>
            <Table.Td>{row.project}</Table.Td>
            <Table.Td>{row.issueType}</Table.Td>
            <Table.Td>{row.count}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function WorkflowsSection({ workflows }: { workflows: WorkflowProfile[] }) {
  const { t } = useTranslation();
  if (workflows.length === 0) return <NoData />;
  return (
    <Stack gap="md">
      {workflows.map((workflow) => (
        <Stack gap={4} key={`${workflow.projectKey}-${workflow.issueType}`}>
          <Text fw={500} size="sm">
            {workflow.projectKey} · {workflow.issueType}
          </Text>
          <Table aria-label={`${workflow.projectKey} · ${workflow.issueType}`}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("dataSources.profile.column.status")}</Table.Th>
                <Table.Th>{t("dataSources.profile.column.category")}</Table.Th>
                <Table.Th>{t("dataSources.profile.column.transitions")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {workflow.observedStatuses.map((s) => (
                <Table.Tr key={s.statusId}>
                  <Table.Td>{s.name}</Table.Td>
                  <Table.Td>{t(`dataSources.profile.category.${s.category}`)}</Table.Td>
                  <Table.Td>{s.transitionCount}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Text size="xs" c="dimmed">
            {t("dataSources.profile.referenceWorkflow", { statuses: workflow.referenceStatusNames.join(", ") })}
          </Text>
        </Stack>
      ))}
    </Stack>
  );
}

function BoardsSection({ boards }: { boards: BoardProfile[] }) {
  const { t } = useTranslation();
  if (boards.length === 0) return <NoData />;
  return (
    <Table aria-label={t("dataSources.profile.section.boards")}>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{t("dataSources.profile.column.board")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.boardType")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.project")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.columns")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.unmappedStatuses")}</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {boards.map((board) => (
          <Table.Tr key={board.boardId}>
            <Table.Td>{board.name}</Table.Td>
            <Table.Td>{board.boardType}</Table.Td>
            <Table.Td>{board.projectKey ?? "—"}</Table.Td>
            <Table.Td>{board.columns.map((c) => `${c.name} (${c.statusNames.join(", ")})`).join("; ") || "—"}</Table.Td>
            <Table.Td>{board.unmappedStatusNames.join(", ") || "—"}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function CustomFieldsSection({ customFields }: { customFields: CustomFieldProfile[] }) {
  const { t } = useTranslation();
  if (customFields.length === 0) return <NoData />;
  return (
    <Table aria-label={t("dataSources.profile.section.customFields")}>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{t("dataSources.profile.column.field")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.type")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.role")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.nonNullCount")}</Table.Th>
          <Table.Th>{t("dataSources.profile.column.fillPercent")}</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {customFields.map((field) => (
          <Table.Tr key={field.id}>
            <Table.Td>{field.name}</Table.Td>
            <Table.Td>{field.type}</Table.Td>
            <Table.Td>{t(`dataSources.profile.fieldRole.${field.role}`)}</Table.Td>
            <Table.Td>{field.nonNullCount}</Table.Td>
            <Table.Td>{percent(field.fillPercent)}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function NoData() {
  const { t } = useTranslation();
  return (
    <Text size="sm" c="dimmed">
      {t("dataSources.profile.noData")}
    </Text>
  );
}

/** The scalar-metrics tables (range/estimates/worklogs/reopens/sprints/people) — label/value rows. */
function MetricsTable({ label, rows }: { label: string; rows: [string, ReactNode][] }) {
  return (
    <Table aria-label={label}>
      <Table.Tbody>
        {rows.map(([label, value]) => (
          <Table.Tr key={label}>
            <Table.Th>{label}</Table.Th>
            <Table.Td>{value}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function ProfileBody({ profile }: { profile: DataProfile }) {
  const { t } = useTranslation();
  const stateCountsText =
    Object.entries(profile.sprints.stateCounts).length > 0
      ? Object.entries(profile.sprints.stateCounts)
          .map(([state, count]) => `${state}: ${count}`)
          .join(", ")
      : "—";
  const anomalyEntries = Object.entries(profile.anomalyCounts ?? {});

  return (
    <Stack gap="lg">
      <Text size="sm" c="dimmed">
        {t("dataSources.profile.computedAt", { when: formatEpochMillis(profile.computedAt, t) })}
      </Text>

      {profile.range && (
        <Section title={t("dataSources.profile.section.range")}>
          <MetricsTable label={t("dataSources.profile.section.range")}
            rows={[
              [t("dataSources.profile.range.earliestCreated"), formatEpochMillis(profile.range.earliestCreatedAt, t)],
              [t("dataSources.profile.range.latestUpdated"), formatEpochMillis(profile.range.latestUpdatedAt, t)],
            ]}
          />
        </Section>
      )}

      <Section title={t("dataSources.profile.section.projects")}>
        <ProjectsSection projects={profile.projects ?? []} />
      </Section>

      <Section title={t("dataSources.profile.section.workflows")}>
        <WorkflowsSection workflows={profile.workflows ?? []} />
      </Section>

      <Section title={t("dataSources.profile.section.boards")}>
        <BoardsSection boards={profile.boards ?? []} />
      </Section>

      <Section title={t("dataSources.profile.section.customFields")}>
        <CustomFieldsSection customFields={profile.customFields ?? []} />
      </Section>

      <Section title={t("dataSources.profile.section.estimates")}>
        <MetricsTable label={t("dataSources.profile.section.estimates")}
          rows={[
            [t("dataSources.profile.estimates.totalIssues"), profile.estimates.totalIssues],
            [
              t("dataSources.profile.estimates.storyPoints"),
              `${profile.estimates.storyPointsCount} (${percent(profile.estimates.storyPointsPercent)})`,
            ],
            [
              t("dataSources.profile.estimates.originalEstimate"),
              `${profile.estimates.originalEstimateCount} (${percent(profile.estimates.originalEstimatePercent)})`,
            ],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.section.worklogs")}>
        <MetricsTable label={t("dataSources.profile.section.worklogs")}
          rows={[
            [t("dataSources.profile.worklogs.count"), profile.worklogs.count],
            [t("dataSources.profile.worklogs.totalHours"), profile.worklogs.totalHours],
            [
              t("dataSources.profile.worklogs.itemsWithWorklog"),
              `${profile.worklogs.itemsWithWorklog} (${percent(profile.worklogs.itemsWithWorklogPercent)})`,
            ],
            [t("dataSources.profile.worklogs.authorCount"), profile.worklogs.authorCount],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.section.reopens")}>
        <MetricsTable label={t("dataSources.profile.section.reopens")}
          rows={[
            [t("dataSources.profile.reopens.count"), profile.reopens.count],
            [t("dataSources.profile.reopens.totalIssues"), profile.reopens.totalIssues],
            [t("dataSources.profile.reopens.percent"), percent(profile.reopens.percent)],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.section.sprints")}>
        <MetricsTable label={t("dataSources.profile.section.sprints")}
          rows={[
            [t("dataSources.profile.sprints.count"), profile.sprints.count],
            [
              t("dataSources.profile.sprints.itemsWithSprint"),
              `${profile.sprints.itemsWithSprint} (${percent(profile.sprints.itemsWithSprintPercent)})`,
            ],
            [
              t("dataSources.profile.sprints.carryOver"),
              `${profile.sprints.carryOverCount} (${percent(profile.sprints.carryOverPercent)})`,
            ],
            [t("dataSources.profile.sprints.stateCounts"), stateCountsText],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.section.people")}>
        <MetricsTable label={t("dataSources.profile.section.people")}
          rows={[
            [t("dataSources.profile.people.activeAssignees"), profile.people.activeAssignees],
            [t("dataSources.profile.people.unassignedPercent"), percent(profile.people.unassignedPercent)],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.section.anomalies")}>
        {anomalyEntries.length === 0 ? (
          <NoData />
        ) : (
          <MetricsTable label={t("dataSources.profile.section.anomalies")} rows={anomalyEntries.map(([code, count]) => [code, count])} />
        )}
      </Section>
    </Stack>
  );
}

/**
 * The read-only data profile (`/data-sources/:id/profile`, ADMIN only — plan §8/§9/§10/§11):
 * plain Mantine tables per section, no charts. `computedAt` is null before the connection's
 * first PROCESS pass, so that state is a normal empty page, not a load failure.
 */
export default function DataSourceProfile() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const idIsValid = Number.isFinite(id) && id > 0;
  const admin = useAdmin();

  const profile = useQuery({
    queryKey: ["dataSources", "profile", id],
    queryFn: () => getDataSourceProfile(id),
    enabled: idIsValid && admin,
  });

  if (profile.isLoading || profile.isError || !profile.data) {
    const notFound = profile.error instanceof ApiError && profile.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={profile.isLoading}
        message={notFound ? t("dataSources.notFound") : loadErrorMessage(profile.error, t)}
        backTo={dataSourcePath(id)}
        backLabel={t("dataSources.details.backToDetails")}
        title={t("dataSources.profile.title")}
      />
    );
  }

  return (
    <Stack gap="md">
      <PageHeader
        title={t("dataSources.profile.title")}
        backTo={{ to: dataSourcePath(id), label: t("dataSources.details.backToDetails") }}
      />
      <Box maw={CONTENT_MAX_WIDTH}>
        {profile.data.computedAt == null ? (
          <EmptyState icon={IconReportAnalytics} label={t("dataSources.profile.empty")} />
        ) : (
          <ProfileBody profile={profile.data} />
        )}
      </Box>
    </Stack>
  );
}
