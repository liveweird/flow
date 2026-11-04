import { type ReactNode } from "react";
import { type TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Stack, Table, Title } from "@mantine/core";
import { type GitLabDataProfile } from "../api/dataSources";
import { formatEpochMillis, percent } from "../utils/dataSourceState";

/** A section heading + its body; every section is a plain table (plan §8/§10/§11 — no charts). */
export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Stack gap="xs">
      <Title order={3} size="h4">{title}</Title>
      {children}
    </Stack>
  );
}

/** The scalar-metrics tables — label/value rows. */
export function MetricsTable({ label, rows }: { label: string; rows: [string, ReactNode][] }) {
  return (
    <Table aria-label={label}>
      <Table.Tbody>
        {rows.map(([rowLabel, value]) => (
          <Table.Tr key={rowLabel}>
            <Table.Th>{rowLabel}</Table.Th>
            <Table.Td>{value}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

/** The merge request states in the order the table lists them; any other key follows alphabetically. */
const STATE_ORDER = ["merged", "opened", "closed", "locked"];
/** The review-event kinds in the order the table lists them (the server zero-fills every kind). */
const EVENT_KINDS = ["COMMENT", "DIFF_COMMENT", "APPROVAL", "REVIEW_REQUESTED", "REVIEWED", "CHANGES_REQUESTED"] as const;

function commitTime(epochMillis: number | null | undefined, t: TFunction): string {
  return epochMillis == null ? "—" : formatEpochMillis(epochMillis, t);
}

function stateRank(state: string): number {
  const index = STATE_ORDER.indexOf(state);
  return index < 0 ? STATE_ORDER.length : index;
}

/**
 * The GitLab half of the data profile: five tables of counts over the normalized code layer.
 * Counts only — no name, address or key is ever shown (the server sends none).
 */
export default function GitLabProfileSections({ gitlab }: { gitlab: GitLabDataProfile }) {
  const { t } = useTranslation();
  const { projects, commits, mergeRequests, events, identities } = gitlab;
  const states = Object.keys(mergeRequests.byState).sort(
    (a, b) => stateRank(a) - stateRank(b) || a.localeCompare(b),
  );
  const stateLabel = (state: string) =>
    t(`dataSources.profile.gitlab.state.${state}`, { defaultValue: state });

  return (
    <>
      <Section title={t("dataSources.profile.gitlab.section.projects")}>
        <MetricsTable
          label={t("dataSources.profile.gitlab.section.projects")}
          rows={[
            [t("dataSources.profile.gitlab.projects.live"), projects.live],
            [t("dataSources.profile.gitlab.projects.movedOut"), projects.movedOut],
            [t("dataSources.profile.gitlab.projects.deleted"), projects.deleted],
            [t("dataSources.profile.gitlab.projects.archived"), projects.archived],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.gitlab.section.commits")}>
        <MetricsTable
          label={t("dataSources.profile.gitlab.section.commits")}
          rows={[
            [t("dataSources.profile.gitlab.commits.count"), commits.count],
            [t("dataSources.profile.gitlab.commits.merges"), commits.merges],
            [t("dataSources.profile.gitlab.commits.withCounts"), commits.commitsWithCounts],
            [t("dataSources.profile.gitlab.commits.earliest"), commitTime(commits.earliestCommittedAt, t)],
            [t("dataSources.profile.gitlab.commits.latest"), commitTime(commits.latestCommittedAt, t)],
            [t("dataSources.profile.gitlab.commits.filesPending"), commits.filesPending],
            [t("dataSources.profile.gitlab.commits.filesUnavailable"), commits.filesUnavailable],
            [t("dataSources.profile.gitlab.commits.filesCapped"), commits.filesCapped],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.gitlab.section.mergeRequests")}>
        <MetricsTable
          label={t("dataSources.profile.gitlab.section.mergeRequests")}
          rows={[
            ...states.map((state): [string, ReactNode] => [stateLabel(state), mergeRequests.byState[state]]),
            [
              t("dataSources.profile.gitlab.mergeRequests.withDiffStats"),
              `${mergeRequests.withDiffStats} (${percent(mergeRequests.withDiffStatsPercent)})`,
            ],
            [t("dataSources.profile.gitlab.mergeRequests.detailsPending"), mergeRequests.detailsPending],
            [t("dataSources.profile.gitlab.mergeRequests.detailsUnreadable"), mergeRequests.detailsUnreadable],
            [t("dataSources.profile.gitlab.mergeRequests.withIssueKeyCandidates"), mergeRequests.withIssueKeyCandidates],
            [t("dataSources.profile.gitlab.mergeRequests.issueKeyCandidates"), mergeRequests.issueKeyCandidates],
          ]}
        />
      </Section>

      <Section title={t("dataSources.profile.gitlab.section.events")}>
        <MetricsTable
          label={t("dataSources.profile.gitlab.section.events")}
          rows={EVENT_KINDS.map((kind): [string, ReactNode] => [
            t(`dataSources.profile.gitlab.event.${kind}`),
            events.byKind[kind] ?? 0,
          ])}
        />
      </Section>

      <Section title={t("dataSources.profile.gitlab.section.identities")}>
        <MetricsTable
          label={t("dataSources.profile.gitlab.section.identities")}
          rows={[
            [t("dataSources.profile.gitlab.identities.users"), identities.users],
            [t("dataSources.profile.gitlab.identities.bots"), identities.bots],
            [t("dataSources.profile.gitlab.identities.usersDeleted"), identities.usersDeleted],
            [t("dataSources.profile.gitlab.identities.ghosts"), identities.ghosts],
            [t("dataSources.profile.gitlab.identities.emailOnly"), identities.emailOnly],
            [
              t("dataSources.profile.gitlab.identities.usersWithEmail"),
              `${identities.usersWithEmail} (${percent(identities.usersWithEmailPercent)})`,
            ],
            [t("dataSources.profile.gitlab.identities.commitsUnmatched"), identities.commitsUnmatched],
          ]}
        />
      </Section>
    </>
  );
}
