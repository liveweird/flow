// Axe accessibility scans over pages whose content only exists over real data, in the light and the
// dark scheme: every report with derived figures behind it (charts, histograms, heat table) and the
// data-source pages (details, profile, raw issue inspector, metrics configuration). Its own spec file
// (split from accessibility.spec.ts) so the sync + DERIVE wait runs beside the other passes and a
// failure re-syncs only here. Owns: one synced Jira-stub data source (`e2e-axe-ds-*`) with a team
// (`e2e-axe-data-team-*`) its FLO board is mapped to, created and deleted via the API.
import type { APIRequestContext, Page } from "@playwright/test";
import {
  apiAsAdmin,
  awaitDerivedSprint,
  axeViolations,
  configureMetricsViaApi,
  expect,
  expectScheme,
  login,
  pauseDataSourceViaApi,
  REPORT_PAGES,
  reportSettled,
  type Scheme,
  stubDayOffset,
  syncStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

async function scan(page: Page): Promise<void> {
  // One line per violation with the offending nodes; no waivers (see accessibility.spec.ts).
  expect(await axeViolations(page)).toEqual([]);
}

test.describe("pages over synced data", () => {
  let api: APIRequestContext | undefined;
  let dataSourceId = 0;
  let dataSourceName = "";
  let teamId = 0;

  test.beforeAll(async () => {
    // A full stub sync (~1,200 issues) and the DERIVE after it; both waits are bounded (340s + 240s).
    test.setTimeout(960_000);
    ({ api } = await apiAsAdmin());
    try {
      ({ id: dataSourceId, name: dataSourceName } = await syncStubDataSourceViaApi(api, "e2e-axe-ds"));
      ({ teamId } = await configureMetricsViaApi(api, dataSourceId, uniqueText("e2e-axe-data-team")));
      // The golden sprint (FLO Sprint 4, id 3003) appearing in the team's velocity proves a DERIVE ran under the mapping.
      await awaitDerivedSprint(api, teamId, 3003);
      // Paused once derived: every config bump elsewhere in the suite re-derives an ENABLED connection
      // (a further full-stub derive on the worker), while a paused one keeps what it derived and stays
      // readable by `connectionId`.
      await pauseDataSourceViaApi(api, dataSourceId);
    } catch (failure) {
      await removeFixtures();
      throw failure;
    }
  });
  test.afterAll(async () => {
    await removeFixtures();
  });

  // Children first: the connection, then the team its board pointed at. A FRESH admin session: the
  // token beforeAll minted (900 s) may have expired across the bounded waits.
  async function removeFixtures(): Promise<void> {
    const { api: cleanup } = await apiAsAdmin();
    const paths = [
      dataSourceId === 0 ? null : `/api/v1/data-sources/${dataSourceId}`,
      teamId === 0 ? null : `/api/v1/teams/${teamId}`,
    ];
    for (const path of paths) {
      if (path === null) continue;
      const removed = await cleanup.delete(path);
      expect([204, 404], `${path} -> ${removed.status()}`).toContain(removed.status());
    }
    await cleanup.dispose();
    await api?.dispose();
  }

  // Fact reports read the seeded team (its By-member level), the rest the whole connection; the
  // epic-progress plan panel of the golden epic is scanned by reports.spec.ts.
  const teamScoped = new Set(["velocity", "throughput", "sprint-consistency", "cycle-time", "task-estimation-accuracy", "wip", "backlog", "aging-wip", "blocked-time"]);
  const DATA_PAGES: { name: string; path: () => string; settled: (page: Page) => Promise<void> }[] = [
    ...REPORT_PAGES.map(({ path, heading }) => ({
      name: path,
      path: () => {
        const team = teamScoped.has(path.split("/").pop() ?? "") ? `&teamId=${teamId}` : "";
        return `${path}?connectionId=${dataSourceId}&from=${stubDayOffset(-365)}&to=${stubDayOffset(7)}${team}`;
      },
      settled: reportSettled(heading),
    })),
    {
      // The matrix itself: the golden sprint of the FLO domain over this connection, scanned once the grid is drawn
      // (the page without a selection is only the explainer, scanned by accessibility.spec.ts).
      name: "/reports/deep-dive with a sprint selection",
      path: () => `/reports/deep-dive?domain=FLO&sprintId=3003&connectionId=${dataSourceId}`,
      settled: async (page) => {
        await reportSettled("Deep dive")(page);
        await expect(page.getByRole("grid", { name: "Plan, execution and cost by epic and time" })).toBeVisible();
      },
    },
    {
      name: "the data source details page",
      path: () => `/data-sources/${dataSourceId}`,
      settled: async (page) => {
        await expect(page.getByRole("heading", { name: dataSourceName, exact: true })).toBeVisible();
        await expect(page.getByRole("row", { name: /Raw issues/ })).toContainText("1200");
      },
    },
    {
      name: "the data profile page",
      path: () => `/data-sources/${dataSourceId}/profile`,
      settled: async (page) => {
        await expect(page.getByRole("heading", { name: "Data profile" })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Workflows" })).toBeVisible();
      },
    },
    {
      name: "the raw issue inspector",
      path: () => `/data-sources/${dataSourceId}/inspect?key=FLO-1`,
      settled: async (page) => {
        await expect(page.getByRole("heading", { name: "FLO-1" })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Raw payload" })).toBeVisible();
      },
    },
    {
      name: "the metrics configuration page",
      path: () => `/data-sources/${dataSourceId}/metrics-config`,
      settled: async (page) => {
        await expect(page.getByRole("heading", { name: "Metrics configuration" })).toBeVisible();
        await expect(page.getByRole("status", { name: "Loading…" })).toHaveCount(0);
      },
    },
  ];

  function registerDataPageScans(scheme: Scheme): void {
    const suffix = scheme === "dark" ? " in the dark scheme" : "";
    for (const { name, path, settled } of DATA_PAGES) {
      test(`${name} has no WCAG A/AA violations over synced data${suffix}`, async ({ page }) => {
        await login(page);
        await page.goto(path());
        await settled(page);
        await expectScheme(page, scheme);
        await scan(page);
      });
    }
  }
  registerDataPageScans("light");
  test.describe("dark scheme", () => {
    test.use({ colorScheme: "dark" });
    registerDataPageScans("dark");
  });
});
