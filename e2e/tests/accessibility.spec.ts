// Axe accessibility smoke: WCAG 2.0/2.1 A+AA scans over the login screen, the authenticated list
// and form pages (every report route among them), the detail pages of one API-seeded fixture
// team, the overlays (a registry editor modal) — where focus traps, aria-modal and labels
// actually live — and every report plus the data-source pages over a synced-and-derived Jira-stub
// connection; the page sets run in the light scheme and again in the dark one. Owns: the fixture
// team (unique `e2e-axe-*` name) and one synced Jira-stub data source (`e2e-axe-ds-*`) with a team
// (`e2e-axe-data-team-*`) its FLO board is mapped to, all created and deleted via the API.
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import {
  apiAsAdmin,
  awaitDerivedSprint,
  axeViolations,
  configureMetricsViaApi,
  expect,
  login,
  stubDayOffset,
  syncStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

// No waivers: the theme's text/dimmed/ink tokens are AA-tested in web/src/theme.test.ts, so
// the color-contrast rule runs for real here (Lettuce's posture, not Toadie's waiver). Fix a
// finding at the token level — never by patching single elements.
async function scan(page: Page, include?: string): Promise<void> {
  // Keep the assert readable on failure: one line per violation with the offending nodes.
  expect(await axeViolations(page, include)).toEqual([]);
}

test("login screen has no WCAG A/AA violations", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await scan(page);
});

type Scheme = "light" | "dark";

// Every report route (nav model: `web/src/utils/reportLinks.ts`), in whatever state the stack gives
// it — the same pages with derived data behind them are scanned in "pages over synced data" below.
const REPORT_PAGES: { path: string; heading: string }[] = [
  { path: "/reports/velocity", heading: "Velocity" },
  { path: "/reports/throughput", heading: "Throughput" },
  { path: "/reports/sprint-consistency", heading: "Sprint consistency" },
  { path: "/reports/cycle-time", heading: "Cycle time" },
  { path: "/reports/task-estimation-accuracy", heading: "Task estimation accuracy" },
  { path: "/reports/epic-estimation-accuracy", heading: "Epic estimation accuracy" },
  { path: "/reports/estimate-adjustments", heading: "Estimate adjustments" },
  { path: "/reports/reported-time-ratio", heading: "Reported time" },
  { path: "/reports/wip", heading: "WIP" },
  { path: "/reports/backlog", heading: "Estimated backlog" },
  { path: "/reports/aging-wip", heading: "Aging WIP" },
  { path: "/reports/blocked-time", heading: "Blocked time" },
  { path: "/reports/epic-progress", heading: "Epic progress" },
  { path: "/reports/data-quality", heading: "Data quality" },
  { path: "/reports/cost-matrix", heading: "Cost matrix" },
];

// One test per page keeps the report line-per-page.
const AUTHED_PAGES: { path: string; heading: string }[] = [
  { path: "/", heading: "Flow" },
  { path: "/teams", heading: "Teams" },
  { path: "/users", heading: "Users" },
  { path: "/users/new", heading: "New user" },
  { path: "/feature-flags", heading: "Feature flags" },
  { path: "/data-sources", heading: "Data sources" },
  { path: "/metrics-settings", heading: "Metrics settings" },
  ...REPORT_PAGES,
  { path: "/change-password", heading: "Change password" },
  { path: "/changelog", heading: "Changelog" },
];

/** The scheme the page must actually render in — Mantine stamps it on <html> (the `auto` default follows the emulated media). */
async function expectScheme(page: Page, scheme: Scheme): Promise<void> {
  await expect(page.locator("html")).toHaveAttribute("data-mantine-color-scheme", scheme);
}

// The light pass keeps its original titles; the dark pass (Playwright's `colorScheme: "dark"`, which
// the app's `auto` default follows) repeats the set under a suffix, so a finding names its scheme.
function registerAuthedPageScans(scheme: Scheme): void {
  const suffix = scheme === "dark" ? " in the dark scheme" : "";
  for (const { path, heading } of AUTHED_PAGES) {
    test(`${path} has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await login(page);
      await page.goto(path);
      // The page title is the first heading (a report repeats it as its card title).
      await expect(page.getByRole("heading", { name: heading }).first()).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page);
    });
  }
}
registerAuthedPageScans("light");
test.describe("dark scheme", () => {
  test.use({ colorScheme: "dark" });
  registerAuthedPageScans("dark");
  test("login screen has no WCAG A/AA violations in the dark scheme", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    await expectScheme(page, "dark");
    await scan(page);
  });
});

test.describe("detail pages and overlays", () => {
  let api: APIRequestContext;
  let adminId: number;
  let teamId: number;
  let teamName: string;

  test.beforeAll(async () => {
    ({ api, userId: adminId } = await apiAsAdmin());
    teamName = uniqueText("e2e-axe-team");
    const created = await api.post("/api/v1/teams", { data: { name: teamName } });
    expect(created.status(), await created.text()).toBe(201);
    teamId = (await created.json() as { id: number }).id;
  });
  test.afterAll(async () => {
    const response = await api.delete(`/api/v1/teams/${teamId}`);
    expect([204, 404], `/api/v1/teams/${teamId} -> ${response.status()}`).toContain(response.status());
    await api.dispose();
  });

  const DETAIL_PAGES: { name: string; path: () => string; settled: (page: Page) => Locator }[] = [
    { name: "the team page", path: () => `/teams/${teamId}`, settled: (p) => p.getByRole("heading", { name: teamName }) },
    { name: "the edit-user page", path: () => `/users/${adminId}/edit`, settled: (p) => p.getByRole("heading", { name: "Edit user" }) },
    { name: "the user-features page", path: () => `/users/${adminId}/features`, settled: (p) => p.getByRole("heading", { name: "Feature flags" }) },
  ];
  for (const { name, path, settled } of DETAIL_PAGES) {
    test(`${name} has no WCAG A/AA violations`, async ({ page }) => {
      await login(page);
      await page.goto(path());
      await expect(settled(page)).toBeVisible();
      await scan(page);
    });
  }

  test("the reset-password page has no WCAG A/AA violations", async ({ page }) => {
    await page.goto("/reset-password");
    await expect(page.getByRole("heading", { name: "Reset password" })).toBeVisible();
    await scan(page);
  });

  test("the not-found page has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto(`/${uniqueText("nowhere")}`);
    await expect(page.getByRole("heading").first()).toBeVisible();
    await scan(page);
  });

  // Overlays are scanned scoped to the dialog: the page behind them is inert, and the dialog is
  // where a missing label, a broken focus trap or a contrast slip would hide from the page sweep.
  // `toBeVisible` passes mid-transition; axe measures contrast against the fade-in's partial
  // opacity, so the scan waits for the dialog to settle at full opacity.
  async function settledDialog(page: Page): Promise<Locator> {
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveCSS("opacity", "1");
    return dialog;
  }

  test("a team editor modal has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto("/teams");
    await page.getByRole("button", { name: "New team" }).click();
    const modal = await settledDialog(page);
    await expect(modal.getByRole("heading", { name: "New team" })).toBeVisible();
    await scan(page, '[role="dialog"]');
  });
});

// The pages whose content only exists over real data: every report with derived figures behind it
// (charts, histograms, heat table, plan panel) and the data-source pages (details, profile, raw
// issue inspector, metrics configuration). One synced-and-derived stub connection serves both
// schemes; reports read its fixed window (a year before the stub's reference date to a week after
// it) so the stub's 2025 sprints and epics are inside it, narrowed to it through `connectionId`.
test.describe("pages over synced data", () => {
  let api: APIRequestContext;
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
    await api.dispose();
  }

  /** A report has settled once its page title is up, no spinner is left (filters, data, lazy charts) and nothing failed. */
  const reportSettled = (heading: string) => async (page: Page) => {
    await expect(page.getByRole("heading", { level: 2, name: heading, exact: true })).toBeVisible();
    await expect(page.getByRole("status", { name: "Loading…" })).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
  };

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
