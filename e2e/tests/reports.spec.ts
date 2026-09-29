// The v0.3.0 REPORTS journey, read the way D12 says everyone reads them: a NON-ADMIN user opens
// the Delivery and Estimation reports over a synced-and-derived Jira-stub connection and finds the
// golden FLO sprint's figures (`sample-data/jira/expected.json` `golden.sprint`) exactly. The
// admin side is API-seeded (a synced stub connection, a team, the FLO board mapped to it — the
// same shape the server's `DerivedStubFixture` uses) so the journeys stay fast; the sync + DERIVE
// wait is a bounded poll of the report API, never a sleep. Owns: its own Jira-stub data source
// (`e2e-reports-ds-*`), team (`e2e-reports-team-*`) and a throwaway regular user, all created
// through the API before the block and deleted after it.
import { readFileSync } from "node:fs";
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { apiAsAdmin, awaitDerivedSprint, configureMetricsViaApi, expect, login, syncStubDataSourceViaApi, test, uniqueText } from "./helpers";

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

const GOLDEN = (
  JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
    golden: { sprint: GoldenSprint };
  }
).golden.sprint;

/** Man-days the way the SPA writes them (at most two decimals, no trailing zeros). */
const md = (value: number) => String(Math.round(value * 100) / 100);
/** A sprint-consistency cell: "MD (items)". */
const mdItems = (mdValue: number, items: number) => `${md(mdValue)} (${items})`;

test.describe("reports, read by a regular user", () => {
  let api: APIRequestContext | undefined;
  // Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
  let dataSourceId: number | undefined;
  let teamId = 0;
  let teamName: string;
  let userId: number | undefined;
  let reader: { email: string; password: string };

  test.beforeAll(async () => {
    // A full stub sync (~1,200 issues) plus the derivation that follows it: the two waits below
    // are each bounded (340s / 240s), so the hook gets their sum plus headroom.
    test.setTimeout(720_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(adminApi, "e2e-reports-ds"));
    teamName = uniqueText("e2e-reports-team");
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, teamName));

    const name = uniqueText("e2e-reports-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
    expect(created.status(), await created.text()).toBe(201);
    userId = (await created.json()).id as number;

    await awaitDerivedSprint(adminApi, teamId, GOLDEN.sprintId);
  });

  test.afterAll(async () => {
    // A FRESH admin session: the access token beforeAll minted (900 s) may have expired across the
    // bounded waits. Children first: the connection (soft delete), the team its board pointed at, the reader.
    const { api: cleanup } = await apiAsAdmin();
    const paths = [
      dataSourceId === undefined ? null : `/api/v1/data-sources/${dataSourceId}`,
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
});
