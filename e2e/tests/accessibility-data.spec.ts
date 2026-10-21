// Axe accessibility scans over pages whose content only exists over real data, in the light and the
// dark scheme: every report with derived figures behind it (charts, histograms, heat table) and the
// data-source pages (details, profile, raw issue inspector, metrics configuration). Its own spec file
// (split from accessibility.spec.ts) so the data-backed scans run beside the other passes. Owns: nothing — it READS the shared fixture (a synced-and-derived, paused Jira-stub data source and
// the team its FLO board is mapped to, created by global setup and deleted by global teardown, never mutated).
import type { Page } from "@playwright/test";
import {
  apiAsAdmin,
  axeViolations,
  expect,
  expectScheme,
  login,
  REPORT_PAGES,
  reportSettled,
  sharedFixtureReady,
  type Scheme,
  stubDayOffset,
  test,
} from "./helpers";

async function scan(page: Page): Promise<void> {
  // One line per violation with the offending nodes; no waivers (see accessibility.spec.ts).
  expect(await axeViolations(page)).toEqual([]);
}

test.describe("pages over synced data", () => {
  let dataSourceId = 0;
  let dataSourceName = "";
  let teamId = 0;

  test.beforeAll(async () => {
    // The shared fixture's SYNC and the DERIVE after it, each wait bounded (340s + 240s) — a no-op once another spec prepared it.
    test.setTimeout(720_000);
    const { api } = await apiAsAdmin();
    try {
      ({ dataSourceId, dataSourceName, teamId } = await sharedFixtureReady(api));
    } finally {
      await api.dispose();
    }
  });

  // The reports read the seeded team (its By-member level where there is one): every flow, data-quality and cost page is read for a team,
  // the rest the whole connection; the epic-progress plan panel of the golden epic is scanned by reports.spec.ts.
  const teamScoped = new Set(["commitments", "throughput", "sprint-consistency", "cycle-time", "task-estimation-accuracy", "task-estimates", "epic-estimation-accuracy", "estimate-adjustments", "wip", "backlog", "aging-wip", "blocked-time", "reported-time-ratio", "epic-progress", "data-quality", "cost-matrix"]);
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
        await expect(page.getByRole("grid", { name: "Plan, earned value and cost by epic and time" })).toBeVisible();
      },
    },
    {
      // The Burn-up view of the same selection: its chart, legend and as-of marker, scanned once the series are drawn.
      name: "/reports/deep-dive with a sprint selection, burn-up view",
      path: () => `/reports/deep-dive?domain=FLO&sprintId=3003&connectionId=${dataSourceId}&view=burnup`,
      settled: async (page) => {
        await reportSettled("Deep dive")(page);
        await expect(page.getByRole("heading", { level: 3, name: "Burn-up", exact: true })).toBeVisible();
        await expect(page.getByRole("group", { name: "Chart: cumulative plan, earned value, approximate EV and cost in man-days by day" }).getByText("Cost (AC)", { exact: true })).toBeVisible();
      },
    },
    {
      name: "the data source details page",
      path: () => `/data-sources/${dataSourceId}`,
      settled: async (page) => {
        await expect(page.getByRole("heading", { name: dataSourceName, exact: true })).toBeVisible();
        await expect(page.getByRole("row", { name: /Raw issues/ })).toContainText("1201");
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
