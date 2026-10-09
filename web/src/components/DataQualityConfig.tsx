import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { DataQualityReport } from "../api/reports";
import { connectionName } from "../utils/dataQualityReport";
import { formatDate } from "../utils/formatDate";
import { formatMd } from "../utils/reportFormat";
import DataQualityCard, { CappedTable, ConnectionCell, type DataQualityScope } from "./DataQualityCard";

/**
 * Configuration findings — properties of a connection's mapping, not of a team. Every one is an
 * administrator's to fix; the connection cell links to that connection's metrics configuration for an
 * administrator only, and reads as plain text for everyone else (the page itself is readable by all).
 */
export default function DataQualityConfig({
  report,
  scope,
  timeZone,
}: {
  report: DataQualityReport;
  scope: DataQualityScope;
  timeZone: string;
}) {
  const { t } = useTranslation();
  const { domainsWithoutOwner, unmappedStatuses, unmappedBoards, itemsAboveEpic, deriveWarnings } = report;
  const connectionColumn = {
    key: "connection",
    header: t("reports.dataQuality.column.connection"),
  };
  return (
    <>
      <DataQualityCard
        id="domainsNoOwner"
        state={domainsWithoutOwner.total > 0 ? "found" : "none"}
        count={domainsWithoutOwner.total}
        scope={scope}
        scopeNote="noTeam"
      >
        <CappedTable
          label={t("reports.dataQuality.cards.domainsNoOwner.title")}
          columns={[
            { ...connectionColumn, render: (row) => <ConnectionCell connectionId={row.connectionId} scope={scope} /> },
            {
              key: "domain",
              header: t("reports.dataQuality.column.domainName"),
              render: (row) => (
                <>
                  <Text size="sm" fw={500}>
                    {row.name}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {row.domainKey}
                  </Text>
                </>
              ),
            },
            { key: "projects", header: t("reports.dataQuality.column.projects"), render: (row) => row.projectKeys.join(", ") },
            { key: "epics", header: t("reports.dataQuality.column.epics"), align: "right", render: (row) => row.epics },
          ]}
          rows={domainsWithoutOwner.items}
          total={domainsWithoutOwner.total}
          rowKey={(row) => `${row.connectionId}:${row.domainKey}`}
        />
      </DataQualityCard>
      <DataQualityCard
        id="unmappedStatuses"
        state={unmappedStatuses.total > 0 ? "found" : "none"}
        count={unmappedStatuses.total}
        scope={scope}
        scopeNote="notTeam"
      >
        <CappedTable
          label={t("reports.dataQuality.cards.unmappedStatuses.title")}
          columns={[
            { ...connectionColumn, render: (row) => <ConnectionCell connectionId={row.connectionId} scope={scope} /> },
            { key: "status", header: t("reports.dataQuality.column.status"), render: (row) => row.name },
            { key: "category", header: t("reports.dataQuality.column.category"), render: (row) => row.category ?? "—" },
            { key: "items", header: t("reports.dataQuality.column.items"), align: "right", render: (row) => row.items },
            { key: "openItems", header: t("reports.dataQuality.column.openItems"), align: "right", render: (row) => row.openItems },
          ]}
          rows={unmappedStatuses.items}
          total={unmappedStatuses.total}
          rowKey={(row) => `${row.connectionId}:${row.statusId}`}
        />
      </DataQualityCard>
      <DataQualityCard
        id="unmappedBoards"
        // The residual is its own finding: it keeps the card orange, but the badge counts boards only.
        state={unmappedBoards.total > 0 || unmappedBoards.unattributedDoneTasks > 0 ? "found" : "none"}
        count={unmappedBoards.total}
        scope={scope}
        scopeNote="boardList"
      >
        <CappedTable
          label={t("reports.dataQuality.cards.unmappedBoards.title")}
          columns={[
            { ...connectionColumn, render: (row) => <ConnectionCell connectionId={row.connectionId} scope={scope} /> },
            { key: "board", header: t("reports.dataQuality.column.board"), render: (row) => row.name },
            { key: "project", header: t("reports.dataQuality.column.project"), render: (row) => row.projectKey ?? "—" },
            { key: "sprints", header: t("reports.dataQuality.column.sprints"), align: "right", render: (row) => row.sprints },
            { key: "doneTasks", header: t("reports.dataQuality.column.doneTasks"), align: "right", render: (row) => row.doneTasks },
          ]}
          rows={unmappedBoards.items}
          total={unmappedBoards.total}
          rowKey={(row) => `${row.connectionId}:${row.boardId}`}
        />
        {unmappedBoards.unattributedDoneTasks > 0 && (
          <Text size="sm">{t("reports.dataQuality.unattributedNote", { count: unmappedBoards.unattributedDoneTasks })}</Text>
        )}
      </DataQualityCard>
      <DataQualityCard
        id="itemsAboveEpic"
        state={itemsAboveEpic.total > 0 ? "found" : "none"}
        count={itemsAboveEpic.total}
        scope={scope}
        scopeNote="notTeam"
      >
        <CappedTable
          label={t("reports.dataQuality.cards.itemsAboveEpic.title")}
          columns={[
            // Plain text, not a link: nothing in the metrics configuration fixes an issue the model leaves out on purpose.
            { ...connectionColumn, render: (row) => connectionName(scope.filters, row.connectionId) },
            {
              key: "issue",
              header: t("reports.dataQuality.column.issue"),
              render: (row) => (
                <>
                  <Text size="sm" fw={500}>
                    {row.issueKey}
                  </Text>
                  {row.summary && (
                    <Text size="xs" c="dimmed">
                      {row.summary}
                    </Text>
                  )}
                </>
              ),
            },
            { key: "type", header: t("reports.dataQuality.column.issueType"), render: (row) => row.issueType },
            { key: "level", header: t("reports.dataQuality.column.hierarchyLevel"), align: "right", render: (row) => row.hierarchyLevel },
            { key: "project", header: t("reports.dataQuality.column.project"), render: (row) => row.projectKey },
            { key: "logged", header: t("reports.dataQuality.column.loggedMd"), align: "right", render: (row) => formatMd(row.worklogMd) },
          ]}
          rows={itemsAboveEpic.items}
          total={itemsAboveEpic.total}
          rowKey={(row) => `${row.connectionId}:${row.issueKey}`}
        />
      </DataQualityCard>
      <DataQualityCard
        id="deriveWarnings"
        state={deriveWarnings.length > 0 ? "found" : "none"}
        count={deriveWarnings.length}
        scope={scope}
        scopeNote="notTeam"
      >
        <CappedTable
          label={t("reports.dataQuality.cards.deriveWarnings.title")}
          columns={[
            { ...connectionColumn, render: (row) => <ConnectionCell connectionId={row.connectionId} scope={scope} name={row.connectionName} /> },
            { key: "lastRun", header: t("reports.dataQuality.column.lastRun"), render: (row) => formatDate(row.startedAt, "—", timeZone) },
            {
              key: "warning",
              header: t("reports.dataQuality.column.warning"),
              render: (row) => row.warnings.map((warning) => t(`reports.dataQuality.warning.${warning}`)).join(", "),
            },
          ]}
          rows={deriveWarnings}
          rowKey={(row) => `${row.connectionId}:${row.runId}`}
        />
      </DataQualityCard>
    </>
  );
}
