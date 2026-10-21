import { useTranslation } from "react-i18next";
import type { ReportFilters } from "../api/reports";
import { domainStandsForTeam, withFilterKey, withPeriod, type ReportControls, type ReportFilterState } from "../utils/reportFilter";
import ReportFilterMultiSelect from "./ReportFilterMultiSelect";
import ReportFilterSelect from "./ReportFilterSelect";

/**
 * WHOSE work the report reads: team, then (under a picked team) member, the domain and — where the
 * report offers it and there is more than one — the Jira connection. The team is the anchor the
 * others hang from: changing it drops the member, and the sprint-survival rule below.
 */
export default function ReportScopeControls({
  filters,
  filter,
  controls,
  onChange,
}: {
  filters: ReportFilters;
  filter: ReportFilterState;
  controls: ReportControls;
  onChange: (next: ReportFilterState) => void;
}) {
  const { t } = useTranslation();
  const team = filters.teams.find((candidate) => candidate.id === filter.teamId);
  const memberOptions = (team?.members ?? []).map((member) => ({ value: member.accountId, label: member.displayName }));

  const changeTeam = (value: string | null) => {
    if (value === null && controls.teamRequired) return;
    const next: ReportFilterState = { ...filter };
    delete next.accountId;
    delete next.accountIds;
    if (value === null) {
      delete next.teamId;
    } else {
      next.teamId = Number(value);
      if (controls.domainExcludesTeam) delete next.domain;
    }
    // A sprint belongs to one team — it survives only a change to a team that lists it.
    const stillListed = filters.teams
      .find((candidate) => String(candidate.id) === value)
      ?.sprints.some((sprint) => sprint.sprintId === filter.sprintId);
    onChange(filter.sprintId !== undefined && !stillListed ? withPeriod(next, {}) : next);
  };

  const changeDomain = (value: string | null) => {
    if (!controls.domainExcludesTeam || value === null) return onChange(withFilterKey(filter, "domain", value));
    // No team × domain split: the domain replaces the team (and the member under it).
    const next = { ...filter, domain: value };
    delete next.teamId;
    delete next.accountId;
    delete next.accountIds;
    onChange(next);
  };

  // A `teamOrDomain` report read for a domain alone (or, with `epicScope`, one epic): the team control shows no team (and still no "All teams", nothing to clear).
  const byDomain = domainStandsForTeam(controls, filter);

  return (
    <>
      {/* A people-only report (Compare people) reads two people across every team: no team, and its people are picked beside the bar. */}
      {!controls.peopleOnly && (
        <ReportFilterSelect
          label={t("reports.filters.team")}
          placeholder={byDomain ? t(filter.domain === undefined ? "reports.filters.noTeamEpic" : "reports.filters.noTeam") : t("reports.filters.allTeams")}
          data={filters.teams.map((candidate) => ({ value: String(candidate.id), label: candidate.name }))}
          value={filter.teamId === undefined ? null : String(filter.teamId)}
          onChange={changeTeam}
          required={controls.teamRequired && !byDomain}
          clearable={!controls.teamRequired}
        />
      )}
      {team && !controls.noMember && !controls.peopleOnly && (
        controls.multiMember ? (
          <ReportFilterMultiSelect
            label={t("reports.filters.member")}
            placeholder={t("reports.filters.wholeTeam")}
            data={memberOptions}
            value={filter.accountIds ?? []}
            onChange={(ids) => onChange(withFilterKey(filter, "accountIds", ids.length === 0 ? null : ids))}
          />
        ) : (
          <ReportFilterSelect
            label={t("reports.filters.member")}
            placeholder={t("reports.filters.wholeTeam")}
            data={memberOptions}
            value={filter.accountId ?? null}
            onChange={(value) => onChange(withFilterKey(filter, "accountId", value))}
          />
        )
      )}
      {controls.domain && (
        <ReportFilterSelect
          label={t("reports.filters.domain")}
          placeholder={t("reports.filters.anyValue")}
          data={filters.domains.map((domain) => ({ value: domain.domainKey, label: domain.domainName }))}
          value={filter.domain ?? null}
          onChange={changeDomain}
        />
      )}
      {controls.connection && filters.connections.length > 1 && (
        <ReportFilterSelect
          label={t("reports.filters.connection")}
          placeholder={t("reports.filters.allConnections")}
          data={filters.connections.map((connection) => ({ value: String(connection.id), label: connection.name }))}
          value={filter.connectionId === undefined ? null : String(filter.connectionId)}
          onChange={(value) => onChange(withFilterKey(filter, "connectionId", value === null ? null : Number(value)))}
        />
      )}
    </>
  );
}
