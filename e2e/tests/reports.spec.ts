// The v0.3.0 REPORTS journey, read the way D12 says everyone reads them: a NON-ADMIN user opens
// the Delivery and Estimation reports over a synced-and-derived Jira-stub connection and finds the
// golden FLO sprint's figures (`sample-data/jira/expected.json` `golden.sprint`) exactly; batch 2
// adds the Flow metrics group (WIP, aging, blocked time, epic progress), Data quality, the Cost
// matrix, Home's unit overview and Velocity's last-N-sprints and member drill. The
// admin side is API-seeded (a synced stub connection, a team, the FLO board mapped to it — the
// same shape the server's `DerivedStubFixture` uses) so the journeys stay fast; the sync + DERIVE
// wait is a bounded poll of the report API, never a sleep. Owns: its own Jira-stub data source
// (`e2e-reports-ds-*`), team (`e2e-reports-team-*`) with one stub person on its roster, and a
// throwaway regular user, all created through the API before the block and deleted after it.
import { readFileSync } from "node:fs";
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import {
  addStubMemberViaApi,
  apiAsAdmin,
  awaitDerivedSprint,
  awaitDerivedTeamCost,
  axeViolations,
  configureMetricsViaApi,
  expect,
  login,
  syncStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

interface GoldenSprint {
  sprintId: number;
  name: string;
  committedMd: number;
  committedItems: number;
  addedMd: number;
  addedItems: number;
  removedMd: number;
  removedItems: number;
  finalMd: number;
  finalItems: number;
  deliveredMd: number;
  deliveredItems: number;
  carriedOverMd: number;
  carriedOverItems: number;
  droppedMd: number;
  droppedItems: number;
}

interface GoldenEpic {
  issueKey: string;
  budgetMd: number;
  startDate: string;
  dueDate: string;
}

const EXPECTED = JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
  referenceDate: string;
  golden: { sprint: GoldenSprint; epic: GoldenEpic };
};
const GOLDEN = EXPECTED.golden.sprint;
const GOLDEN_EPIC = EXPECTED.golden.epic;

/**
 * The period the period-bound batch-2 reports read: the stub's data ends at its reference date, so
 * a fixed window around it keeps them independent of today (the default "last 90 days" would slide
 * off the sample data), passed as `from`/`to` in the URL — the filter IS the URL.
 */
const dayOffset = (days: number) => new Date(Date.parse(EXPECTED.referenceDate) + days * 86_400_000).toISOString().slice(0, 10);
const WINDOW = `from=${dayOffset(-184)}&to=${dayOffset(7)}`;

/** Man-days the way the SPA writes them (at most two decimals, no trailing zeros). */
const md = (value: number) => String(Math.round(value * 100) / 100);
/** A sprint-consistency cell: "MD (items)". */
const mdItems = (mdValue: number, items: number) => `${md(mdValue)} (${items})`;

let api: APIRequestContext | undefined;
// Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
let dataSourceId: number | undefined;
let teamId = 0;
let teamName = "";
let userId: number | undefined;
let membershipId: number | undefined;
let reader = { email: "", password: "" };

/** Sign in as the regular user and open a report group from the sidebar. */
async function openGroup(page: Page, group: "Delivery" | "Estimation"): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.getByRole("link", { name: group, exact: true }).click();
}

/**
 * Narrow the open report to the golden sprint through the filter bar: the team, then the
 * period "One sprint", then that sprint by name. (The default period, the last 90 days, holds
 * none of the stub's sprints — they ended in 2025.)
 */
async function pickGoldenSprint(page: Page): Promise<void> {
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await team.click();
  await team.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await page.getByRole("combobox", { name: "Period", exact: true }).click();
  await page.getByRole("option", { name: "One sprint", exact: true }).click();
  await page.getByRole("combobox", { name: "Sprint", exact: true }).click();
  await page.getByRole("option", { name: GOLDEN.name, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
}

/**
 * A distribution's percentile strip OR its counts-only minimum-sample notice — the notice matched
 * by its own wording, so the cycle-time trend's "no period has enough items" note cannot stand in
 * for it. The union is what `.first()` wraps at the call sites.
 */
const percentilesOrNotice = (page: Page): Locator =>
  page
    .getByText("Median (p50)")
    .or(page.getByRole("note").filter({ hasText: /are needed to show|Nothing to measure/ }));

/** The golden sprint's table row (its name is the only such row on a page). */
const goldenRow = (page: Page): Locator => page.getByRole("row", { name: new RegExp(GOLDEN.name) });

/**
 * Sign in as the regular user and open a report by its deep link, narrowed to THIS spec's
 * connection (an unnarrowed unit-level report would also count the other specs' synced
 * connections) and to the stub's fixed window. `extra` adds the team or scope under test.
 */
async function openReport(page: Page, path: string, extra = ""): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.goto(`${path}?connectionId=${dataSourceId}&${WINDOW}${extra}`);
}

// The populated counterpart of the accessibility sweep, which scans these pages in whatever
// state the stack happens to be in: here each is scanned with real derived data behind it (the
// charts, the heat table, the epic plan), once its content settled — one line per page, and a
// clean page is an empty list.
async function populatedViolations(page: Page, targets: { path: string; extra?: string; settled: (p: Page) => Locator }[]) {
  const findings: Record<string, unknown[]> = {};
  for (const { path, extra = "", settled } of targets) {
    await page.goto(`${path}?connectionId=${dataSourceId}&${WINDOW}${extra}`);
    await expect(settled(page)).toBeVisible();
    findings[path] = await axeViolations(page);
  }
  return Object.fromEntries(Object.entries(findings).filter(([, violations]) => violations.length > 0));
}

/** The epic-progress journey: unit → the FLO domain → the golden epic, with its plan panel and daily table. */
async function drillIntoGoldenEpic(page: Page): Promise<void> {
  await openReport(page, "/reports/epic-progress");
  await expect(page.getByRole("heading", { level: 2, name: "Epic progress", exact: true })).toBeVisible();

  // The unit level: the headline tiles, and the domains table (the epic basis) beside the teams.
  await expect(page.getByRole("heading", { level: 3, name: "Whole unit", exact: true })).toBeVisible();
  for (const tile of ["PV (MD)", "EV (MD)", "AC (MD)", "SPI", "CPI"]) await expect(page.getByRole("group", { name: tile, exact: true })).toBeVisible();
  const domains = page.getByRole("table", { name: "Domains", exact: true });
  await expect(domains.getByRole("link", { name: "Show FLO", exact: true })).toBeVisible();
  await expect(page.getByRole("table", { name: "Teams (sprint view)", exact: true }).getByRole("link", { name: `Show ${teamName}`, exact: true })).toBeVisible();

  // Into the FLO domain: its epics, the golden one among them; the period and connection travel along.
  await domains.getByRole("link", { name: "Show FLO", exact: true }).click();
  await expect(page).toHaveURL(/domain=FLO/);
  await expect(page.getByRole("heading", { level: 3, name: "Domain: FLO", exact: true })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`connectionId=${dataSourceId}`));
  await page.getByRole("table", { name: "Epics", exact: true }).getByRole("link", { name: `Show ${GOLDEN_EPIC.issueKey}`, exact: true }).click();

  // The epic: the plan panel carries the generator's budget and planned dates; the breadcrumb leads back.
  await expect(page).toHaveURL(new RegExp(`epicId=${GOLDEN_EPIC.issueKey}`));
  await expect(page.getByRole("heading", { level: 3, name: new RegExp(`^Epic: ${GOLDEN_EPIC.issueKey}`) })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Where you are in the drill-down" }).getByRole("link", { name: "FLO", exact: true })).toBeVisible();
  // The plan was fully spent long before the window ends, so PV IS the budget.
  await expect(page.getByRole("group", { name: "PV (MD)", exact: true })).toContainText(md(GOLDEN_EPIC.budgetMd));
  const plan = page.getByRole("heading", { level: 3, name: "Plan", exact: true }).locator("xpath=ancestor::*[contains(@class,'Paper')][1]");
  await expect(plan).toContainText(`${md(GOLDEN_EPIC.budgetMd)} MD`);
  await expect(plan).toContainText(GOLDEN_EPIC.startDate);
  await expect(plan).toContainText(GOLDEN_EPIC.dueDate);

  // The chart's text alternative lists the days, newest first.
  await expect(page.getByRole("group", { name: "Chart: cumulative planned value, earned value and actual cost in man-days by day" })).toBeVisible();
  await page.getByRole("button", { name: "Show daily figures" }).click();
  const daily = page.getByRole("table", { name: "Cumulative PV, EV and AC by day, as a table" });
  await expect(daily.getByRole("row").nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  expect(await daily.getByRole("row").count(), "header + one row per day of the window").toBeGreaterThan(100);
}

/** The data-quality journey: overview tiles against their cards, the stub-guaranteed findings, no admin link. */
async function readDataQualityFindings(page: Page): Promise<void> {
  await openReport(page, "/reports/data-quality");
  await expect(page.getByRole("heading", { level: 2, name: "Data quality", exact: true })).toBeVisible();

  // The overview: what was checked, and the headline tiles.
  const populations = page.getByRole("group", { name: "What was checked" });
  await expect(populations.getByText("Done tasks", { exact: true })).toBeVisible();
  const tiles = page.getByRole("group", { name: "Headline counts, each linking to its card" });
  const tileValue = async (label: string): Promise<number> => {
    const tile = tiles.getByRole("link", { name: label, exact: true }).locator("xpath=..");
    return Number((await tile.locator("p").first().innerText()).trim());
  };

  // The stub guarantees these: work logged days late, authors on no team, and the OPS Kanban work no sprint holds.
  const cards = page.getByRole("group", { name: "Findings" });
  const findings = [
    { tile: "Over 1 day late", card: "Late logging" },
    { tile: "Authors without a team", card: "Authors without a team" },
    { tile: "Work done outside any sprint", card: "Work done outside any sprint" },
  ];
  for (const { tile, card } of findings) {
    const count = await tileValue(tile);
    expect(count, `${tile} is found in the stub data`).toBeGreaterThan(0);
    // The card states the same count the tile does.
    const cardBox = cards.getByRole("group", { name: card, exact: true });
    await expect(cardBox.getByText(`Found: ${count}`, { exact: true })).toBeVisible();
  }
  await expect(cards.getByRole("group", { name: "Work done outside any sprint", exact: true }).getByText(/\bOPS-\d+/).first()).toBeVisible();

  // A tile leads to its card without touching the URL; focus lands in it.
  const before = page.url();
  await tiles.getByRole("link", { name: "Authors without a team", exact: true }).click();
  await expect(cards.getByRole("group", { name: "Authors without a team", exact: true })).toBeFocused();
  expect(page.url()).toBe(before);

  // The configuration findings name their connection, but only an administrator gets the link to fix it.
  await expect(cards.getByRole("group", { name: "Boards without a team", exact: true })).toContainText("Found:");
  await expect(page.getByRole("link", { name: /^Open the metrics configuration of / })).toHaveCount(0);
}

/** The cost-matrix journey: the heat table, its totals, and the drill to the roster team's authors. */
async function readCostMatrix(page: Page): Promise<void> {
  await openReport(page, "/reports/cost-matrix");
  await expect(page.getByRole("heading", { level: 2, name: "Cost matrix", exact: true })).toBeVisible();

  // The heat table: author teams in rows, the stub's four domains in columns, totals both ways.
  const matrix = page.getByRole("table", { name: /^Man-days logged in the period: author teams in rows/ });
  for (const column of ["FLO", "GTM", "OPS", "PLT", "Total", "Foreign work (MD)", "Foreign share"]) {
    await expect(matrix.getByRole("columnheader", { name: column, exact: true })).toBeVisible();
  }
  const teamRow = matrix.getByRole("row", { name: new RegExp(`^Show ${teamName} `) });
  await expect(teamRow).toBeVisible();
  const totalsRow = matrix.getByRole("row", { name: /^Total / });
  const cellValues = async (row: Locator): Promise<number[]> =>
    (await row.getByRole("cell").allInnerTexts()).map((text) => Number(text.replace("%", "")));
  const rowTotal = async (row: Locator) => (await cellValues(row))[4];
  // The totals row repeats the tile; the rows add up to it (each figure is rounded once, so within 0.005 apiece).
  const grandTotal = await rowTotal(totalsRow);
  await expect(page.getByRole("group", { name: "Man-days logged", exact: true })).toContainText(String(grandTotal));
  const rowsSum = (await matrix.getByRole("row").filter({ hasNot: page.getByRole("columnheader") }).evaluateAll((rows) =>
    rows.map((row) => row.querySelectorAll("td")[4]?.textContent ?? "").map(Number),
  ));
  const bodyTotals = rowsSum.slice(0, -1);
  expect(bodyTotals.reduce((sum, value) => sum + value, 0)).toBeCloseTo(grandTotal, 1);

  // Into the team: its authors are the rows now (the roster person among them), the period carried along.
  await teamRow.getByRole("link", { name: `Show ${teamName}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "Man-days by author and domain", exact: true })).toBeVisible();
  const authors = page.getByRole("table", { name: /^Man-days logged in the period: the team's authors in rows/ });
  await expect(authors.getByRole("columnheader", { name: "Author", exact: true })).toBeVisible();
  await expect(authors.getByRole("row", { name: /^Show Sample User \d+ / }).first()).toBeVisible();
}

test.describe("reports, read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync (~1,200 issues) plus the derivation that follows it: the two waits below
    // are each bounded (340s / 240s / 240s), so the hook gets their sum plus headroom.
    test.setTimeout(960_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(adminApi, "e2e-reports-ds"));
    teamName = uniqueText("e2e-reports-team");
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, teamName));
    // A stub person on the team's roster, so the cost matrix has a real author-team row to drill into.
    ({ membershipId } = await addStubMemberViaApi(adminApi, teamId));

    const name = uniqueText("e2e-reports-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
    expect(created.status(), await created.text()).toBe(201);
    userId = (await created.json()).id as number;

    await awaitDerivedSprint(adminApi, teamId, GOLDEN.sprintId);
    await awaitDerivedTeamCost(adminApi, dataSourceId, teamId, WINDOW);
  });

  test.afterAll(async () => {
    // A FRESH admin session: the access token beforeAll minted (900 s) may have expired across the
    // bounded waits. Children first: the connection (soft delete), the roster membership (a deleted
    // team keeps its rows, and a person belongs to one team at a time, globally — so it goes BEFORE
    // the team), the team its board pointed at, the reader.
    const { api: cleanup } = await apiAsAdmin();
    const paths = [
      dataSourceId === undefined ? null : `/api/v1/data-sources/${dataSourceId}`,
      membershipId === undefined || teamId === 0 ? null : `/api/v1/teams/${teamId}/jira-memberships/${membershipId}`,
      teamId === 0 ? null : `/api/v1/teams/${teamId}`,
      userId === undefined ? null : `/api/v1/users/${userId}`,
    ];
    for (const path of paths) {
      if (path === null) continue;
      const removed = await cleanup.delete(path);
      expect([204, 404], `${path} -> ${removed.status()}`).toContain(removed.status());
    }
    await cleanup.dispose();
    await api?.dispose();
  });

  test("a regular user reads the golden sprint's velocity", async ({ page }) => {
    await openGroup(page, "Delivery");
    await expect(page.getByRole("heading", { level: 2, name: "Velocity", exact: true })).toBeVisible();
    await pickGoldenSprint(page);

    await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: initial and final scope by sprint" })).toBeVisible();

    // Initial = the committed scope, final = the final scope (A17) — the generator's own figures.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(0)).toHaveText(GOLDEN.name);
    await expect(cells.nth(1)).toHaveText(teamName);
    await expect(cells.nth(3)).toHaveText(md(GOLDEN.committedMd));
    await expect(cells.nth(4)).toHaveText(String(GOLDEN.committedItems));
    await expect(cells.nth(5)).toHaveText(md(GOLDEN.finalMd));
    await expect(cells.nth(6)).toHaveText(String(GOLDEN.finalItems));
    // Nothing moved since the sprint closed, so no drift badge.
    await expect(page.getByText("Drift", { exact: true })).toHaveCount(0);
  });

  test("the user regroups throughput by month", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickGoldenSprint(page);
    await page.getByRole("tab", { name: "Throughput", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
    // Switching tabs carries the period and team with it.
    await expect(page).toHaveURL(/\/reports\/throughput\?/);
    await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));

    // The sprint view: what was done inside the golden sprint while in it.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(3)).toHaveText(md(GOLDEN.deliveredMd));
    await expect(cells.nth(4)).toHaveText(String(GOLDEN.deliveredItems));

    // The period view, by week (the default): a row per Monday across the sprint's window.
    await expect(page.getByText(/^Delivered in the period: [\d.]+ MD \(\d+ items?\)$/)).toBeVisible();
    const bucketTable = page.getByRole("table", { name: "Delivered per period, as a table" });
    await expect(bucketTable.getByRole("columnheader", { name: "Week starting" })).toBeVisible();
    const bucketRows = bucketTable.getByRole("row");
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
    expect(await bucketRows.count(), "header + at least two weekly buckets").toBeGreaterThan(2);

    // Regroup by month: the URL, the table's column and its rows (one month covers the window) follow.
    await page.getByRole("radiogroup", { name: "Group by" }).getByText("Month", { exact: true }).click();
    await expect(page).toHaveURL(/bucket=MONTH/);
    await expect(bucketTable.getByRole("columnheader", { name: "Month", exact: true })).toBeVisible();
    await expect(bucketRows).toHaveCount(2);
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}$/);
    await expect(page.getByRole("group", { name: "Chart: delivered scope by period" })).toBeVisible();
  });

  test("the user reads the golden sprint's scope movement", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickGoldenSprint(page);
    await page.getByRole("tab", { name: "Sprint consistency", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Sprint consistency", exact: true })).toBeVisible();

    await expect(page.getByRole("group", { name: "Chart: committed and delivered scope by sprint" })).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: final scope split into carried over, delivered and dropped, by sprint" })).toBeVisible();

    // The full table, in column order: committed, added, removed, final, delivered, carried over, dropped.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(3)).toHaveText(mdItems(GOLDEN.committedMd, GOLDEN.committedItems));
    await expect(cells.nth(4)).toHaveText(mdItems(GOLDEN.addedMd, GOLDEN.addedItems));
    await expect(cells.nth(5)).toHaveText(mdItems(GOLDEN.removedMd, GOLDEN.removedItems));
    await expect(cells.nth(6)).toHaveText(mdItems(GOLDEN.finalMd, GOLDEN.finalItems));
    await expect(cells.nth(7)).toHaveText(mdItems(GOLDEN.deliveredMd, GOLDEN.deliveredItems));
    await expect(cells.nth(8)).toHaveText(mdItems(GOLDEN.carriedOverMd, GOLDEN.carriedOverItems));
    await expect(cells.nth(9)).toHaveText(mdItems(GOLDEN.droppedMd, GOLDEN.droppedItems));
  });

  test("the user reads cycle time", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickGoldenSprint(page);
    await page.getByRole("tab", { name: "Cycle time", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();

    // Both views state their own accounting, and each is either a distribution or the counts-only notice.
    await expect(page.getByRole("heading", { name: "Working days", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Elapsed days", exact: true })).toBeVisible();
    await expect(page.getByText(/^Of \d+ finished in this period:$/).first()).toBeVisible();
    await expect(percentilesOrNotice(page).first()).toBeVisible();
    // The golden sprint's team finished work in the window, so the per-period trend has its table.
    await expect(page.getByRole("table", { name: "Trend, as a table" })).toBeVisible();
  });

  test("the user reads task estimation accuracy", async ({ page }) => {
    await openGroup(page, "Estimation");
    await expect(page.getByRole("heading", { level: 2, name: "Task estimation accuracy", exact: true })).toBeVisible();
    await pickGoldenSprint(page);

    // Two views — at start (primary) and at done — each with its own "left out" accounting.
    await expect(page.getByRole("heading", { name: "Against the estimate at start" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Against the estimate at done" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
    await expect(page.getByText(/^Of \d+ finished in this period:$/).first()).toBeVisible();
    // A distribution (percentile strip) or, below the minimum sample, the counts-only notice.
    await expect(percentilesOrNotice(page).first()).toBeVisible();
  });

  test("the user reads the last three sprints and drills to a member", async ({ page }) => {
    await openGroup(page, "Delivery");
    const team = page.getByRole("combobox", { name: "Team", exact: true });
    await team.click();
    await team.fill(teamName);
    await page.getByRole("option", { name: teamName, exact: true }).click();
    await page.getByRole("combobox", { name: "Period", exact: true }).click();
    await page.getByRole("option", { name: "Last 3 sprints", exact: true }).click();
    await expect(page).toHaveURL(/lastSprints=3/);
    await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

    // The team's three newest sprints, one row each.
    const sprintRows = page.getByRole("row", { name: /FLO Sprint \d+/ });
    await expect(sprintRows).toHaveCount(3);

    // The team level lists its members; take the first and remember its figures.
    await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();
    const memberLink = page.getByRole("link", { name: /^Show Sample User \d+$/ }).first();
    const memberName = (await memberLink.innerText()).trim();
    const memberRow = page.getByRole("row", { name: new RegExp(`^Show ${memberName} `) });
    const memberInitialMd = Number(await memberRow.getByRole("cell").nth(1).innerText());
    expect(memberInitialMd).toBeGreaterThanOrEqual(0);

    // Drilling narrows to the person: the URL names them, the member table is gone (nothing is
    // below a person), and their sprint rows add up to the figure the team level showed for them.
    await memberLink.click();
    await expect(page).toHaveURL(/accountId=/);
    await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
    await expect(sprintRows.first()).toBeVisible();
    const perSprint = await sprintRows.evaluateAll((rows) =>
      rows.map((row) => Number(row.querySelectorAll("td")[3]?.textContent ?? Number.NaN)),
    );
    expect(perSprint.length).toBeGreaterThan(0);
    expect(perSprint.reduce((sum, value) => sum + value, 0)).toBeCloseTo(memberInitialMd, 1);
  });

  test("the user counts work in progress by stage and by status", async ({ page }) => {
    await openReport(page, "/reports/wip", `&teamId=${teamId}`);
    await expect(page.getByRole("heading", { level: 2, name: "WIP", exact: true })).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeVisible();

    // By stage (the default): the four stages, as the chart's text alternative.
    const bands = page.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
    for (const stage of ["Not started", "In progress", "Done", "Unmapped status"]) {
      await expect(bands.getByRole("cell", { name: stage, exact: true })).toBeVisible();
    }

    // By status: the URL, the caption and the bands all follow — the workflow's own statuses.
    await page.getByRole("radiogroup", { name: "Count by" }).getByText("Status", { exact: true }).click();
    await expect(page).toHaveURL(/by=STATUS/);
    await expect(page.getByText("By status, ordered by stage and then by name.")).toBeVisible();
    for (const status of ["To Do", "In Progress", "Done"]) {
      await expect(bands.getByRole("cell", { name: status, exact: true })).toBeVisible();
    }
    await expect(bands.getByRole("cell", { name: "Not started", exact: true })).toHaveCount(0);
    await expect(page.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeVisible();
  });

  test("the user reads aging work in progress and blocked time", async ({ page }) => {
    await openReport(page, "/reports/wip", `&teamId=${teamId}`);
    await page.getByRole("tab", { name: "Aging WIP", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Aging WIP", exact: true })).toBeVisible();
    // The tab hop keeps the team.
    await expect(page).toHaveURL(new RegExp(`/reports/aging-wip\\?.*teamId=${teamId}`));

    // The task thresholds (the team finished plenty), and the open work, oldest first.
    const thresholds = page.getByRole("group", { name: "Tasks", exact: true });
    for (const percentile of ["p50", "p85", "p95"]) await expect(thresholds.getByText(percentile, { exact: true })).toBeVisible();
    const openWork = page.getByRole("table", { name: "Open work, oldest first" });
    await expect(openWork.getByRole("columnheader", { name: "Age (working days)" })).toBeVisible();
    expect(await openWork.getByRole("row").count(), "header + at least one open item").toBeGreaterThan(1);
    await expect(openWork.getByRole("row").nth(1)).toContainText(/\b(FLO|PLT|GTM|OPS)-\d+/);

    // Blocked time: both distributions, the "blocked at all" line and the most-blocked table.
    await page.getByRole("tab", { name: "Blocked time", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Blocked time", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Blocked working days", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Blocked share of cycle", exact: true })).toBeVisible();
    await expect(page.getByText(/^\d+ of \d+ finished items? (was|were) blocked at all/)).toBeVisible();
    await expect(percentilesOrNotice(page).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: "Most blocked items", exact: true })).toBeVisible();
  });

  test("the user drills from a domain into the golden epic's progress", async ({ page }) => {
    await drillIntoGoldenEpic(page);
  });

  test("the user reads the data-quality findings", async ({ page }) => {
    await readDataQualityFindings(page);
  });

  test("the user reads the cost matrix and drills to a team", async ({ page }) => {
    await readCostMatrix(page);
  });

  test("the user sees the four overview tiles on Home and follows one into its report", async ({ page }) => {
    await login(page, reader.email, reader.password);
    await expect(page.getByRole("heading", { level: 2, name: "Flow", exact: true })).toBeVisible();

    // The four tiles of the whole unit, each with its title as the way into the report.
    const overview = page.getByRole("group", { name: "Unit overview" });
    for (const title of ["Velocity and throughput", "Cycle time", "Work in progress", "Data quality"]) {
      const tile = overview.getByRole("region", { name: title, exact: true });
      await expect(tile).toBeVisible();
      await expect(tile).toHaveAttribute("aria-busy", "false");
      await expect(tile.getByRole("link", { name: title, exact: true })).toBeVisible();
    }
    // The velocity tile lists this spec's team with its last closed sprint.
    await expect(
      overview.getByRole("table", { name: "Each team's last closed sprint, as a table" }).getByRole("row", { name: new RegExp(`^${teamName} FLO Sprint \\d+`) }),
    ).toBeVisible();

    // A tile link never lands bare: the report opens with the tile's own period in the URL.
    await overview.getByRole("link", { name: "Cycle time", exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/cycle-time\?.*from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/);
    await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();

    await page.getByRole("link", { name: "Home", exact: true }).click();
    await overview.getByRole("link", { name: "Velocity and throughput", exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/velocity\?.*lastSprints=1/);
    await expect(page.getByRole("heading", { level: 2, name: "Velocity", exact: true })).toBeVisible();
  });

  test("the populated flow, epic and cost pages have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    expect(
      await populatedViolations(page, [
        { path: "/reports/wip", extra: `&teamId=${teamId}`, settled: (p) => p.getByRole("table", { name: "Latest day, average and peak per band, as a table" }) },
        { path: "/reports/epic-progress", extra: `&epicId=${GOLDEN_EPIC.issueKey}`, settled: (p) => p.getByRole("heading", { level: 3, name: "Plan", exact: true }) },
        { path: "/reports/cost-matrix", settled: (p) => p.getByRole("table", { name: /^Man-days logged in the period/ }) },
      ]),
    ).toEqual({});
  });

  // Home is scanned once its four tiles loaded; its velocity tile's table scrolls sideways in the
  // half-width tile (a focusable, named scroll region), and the drift table's "reconstructed" badge
  // is the orange light badge — both were findings of this very scan.
  test("the populated Home overview and data-quality page have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    // Home first: the session lands there. Its four tiles must have loaded before the scan.
    for (const title of ["Velocity and throughput", "Cycle time", "Work in progress", "Data quality"]) {
      await expect(page.getByRole("region", { name: title, exact: true })).toHaveAttribute("aria-busy", "false");
    }
    const home = await axeViolations(page);
    const rest = await populatedViolations(page, [{ path: "/reports/data-quality", settled: (p) => p.getByRole("group", { name: "Findings" }) }]);
    expect({ ...(home.length > 0 ? { "/": home } : {}), ...rest }).toEqual({});
  });
});
