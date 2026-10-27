import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { FILTERS } from "../test/reportFixtures";
import { deepDiveFilterLines, deepDiveLayerLine, reportFilterLines, tailPercentLine } from "./reportPrintSummary";

const t = i18n.t.bind(i18n);
const asText = (lines: ReadonlyArray<{ label: string; value: string }>) => lines.map((line) => `${line.label}: ${line.value}`);

describe("reportFilterLines", () => {
  test("a sprint, last-N-sprints and a custom open range are spelled out", () => {
    expect(asText(reportFilterLines({ sprintId: 12, teamId: 1 }, FILTERS, {}, t))).toEqual([
      "Period: Alpha 2 · 1970-01-01 → 1970-01-01",
      "Team: Alpha",
    ]);
    expect(asText(reportFilterLines({ lastSprints: 3, teamId: 0 }, FILTERS, {}, t))).toEqual(["Period: Last 3 sprints", "Team: Unassigned"]);
    expect(asText(reportFilterLines({ from: "2026-01-01", to: "2026-02-01" }, FILTERS, {}, t))).toEqual([
      "Period: 2026-01-01 → 2026-02-01",
      "Team: All teams",
    ]);
  });

  test("a page's other dimensions appear only where it offers them and the filter sets them", () => {
    const filter = { teamId: 1, activityType: "Bug", workCategory: "Maintenance", bucket: "MONTH" as const, by: "STATUS" as const, itemKind: "EPIC" as const };
    const none = asText(reportFilterLines(filter, FILTERS, { noPeriod: true }, t));
    expect(none).toEqual(["Team: Alpha"]);
    const all = asText(
      reportFilterLines(filter, FILTERS, { noPeriod: true, activityType: true, workCategory: true, bucket: true, wipBy: true, itemKind: ["TASK", "EPIC"], domainView: "TASK" }, t),
    );
    expect(all).toEqual([
      "Team: Alpha",
      "Activity type: Bug",
      "Work category: Maintenance",
      "Domain view: Delivered in",
      "Group by: Month",
      "Count by: Status",
      "Count: Epics",
    ]);
  });

  test("a toggle the page offers prints at its effective default when the filter leaves it unset", () => {
    expect(
      asText(reportFilterLines({}, FILTERS, { noPeriod: true, bucket: true, wipBy: true, itemKind: ["TASK", "EPIC"] }, t)),
    ).toEqual(["Team: All teams", "Group by: Week", "Count by: Stage", "Count: Tasks"]);
  });

  test("a domain standing in for the team (WIP) says so and prints no team", () => {
    const controls = { noPeriod: true, domain: true, teamRequired: true, teamOrDomain: true, domainExcludesTeam: true };
    expect(asText(reportFilterLines({ domain: "FLO" }, FILTERS, controls, t))).toEqual(["Domain: Flow (stands for the team)"]);
    expect(asText(reportFilterLines({ epicId: "FLO-33" }, FILTERS, { ...controls, epicScope: true }, t))).toEqual(["Epic: FLO-33 (stands for the team)"]);
    expect(asText(reportFilterLines({ domain: "FLO", teamId: 1 }, FILTERS, controls, t))).toEqual(["Team: Alpha", "Domain: Flow"]);
  });

  test("the connection is named only when there is more than one", () => {
    expect(asText(reportFilterLines({ connectionId: 1 }, FILTERS, { noPeriod: true, connection: true }, t))).toEqual(["Team: All teams"]);
    const two = { ...FILTERS, connections: [...FILTERS.connections, { id: 2, name: "Second", siteUrl: "" }] };
    expect(asText(reportFilterLines({ connectionId: 2 }, two, { noPeriod: true, connection: true }, t))).toEqual(["Team: All teams", "Connection: Second"]);
  });

  test("Compare people lists the two people, with no team", () => {
    expect(asText(reportFilterLines({ from: "2026-01-01", to: "2026-02-01", accountIds: ["acc-cy", "acc-ann"] }, FILTERS, { peopleOnly: true }, t))).toEqual([
      "Period: 2026-01-01 → 2026-02-01",
      "People: Cy Coder, Ann Author",
    ]);
  });
});

describe("Person trend", () => {
  test("lists the one person under 'Person', with no team", () => {
    expect(asText(reportFilterLines({ from: "2026-01-01", to: "2026-02-01", accountIds: ["acc-ann"] }, FILTERS, { peopleOnly: true, peopleCount: 1 }, t))).toEqual([
      "Period: 2026-01-01 → 2026-02-01",
      "Person: Ann Author",
    ]);
  });
});

describe("page-local lines", () => {
  test("the long-tail share is the filter's, else the default 5%", () => {
    expect(tailPercentLine({}, t)).toEqual({ label: "Long tail", value: "5%" });
    expect(tailPercentLine({ tailPercent: 0 }, t)).toEqual({ label: "Long tail", value: "0%" });
  });

  test("the Deep dive layers are the ones switched on", () => {
    expect(deepDiveLayerLine({ pv: true, aev: false, cost: true }, t)).toEqual({ label: "Layers", value: "Plan (PV), Cost (AC)" });
  });
});

describe("deepDiveFilterLines", () => {
  test("names the domain, the sprints and epics by their labels (the key where unknown) and the range", () => {
    expect(
      asText(
        deepDiveFilterLines(
          { domain: "FLO", sprintIds: [41, 42], epicIds: ["FLO-1"], from: "2026-01-01" },
          { "41": "Sprint 4", "FLO-1": "FLO-1 Onboarding" },
          FILTERS,
          t,
        ),
      ),
    ).toEqual(["Domain: Flow", "Sprints: Sprint 4, 42", "Epics: FLO-1 Onboarding", "Date range: 2026-01-01 → …"]);
    expect(deepDiveFilterLines({}, {}, undefined, t)).toEqual([]);
  });
});
