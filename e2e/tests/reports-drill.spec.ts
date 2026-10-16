// The DRILL from a bar to the items behind it (`.claude/docs/reports.md` "Drill to items"), read the way D12 says everyone reads
// reports: a NON-ADMIN user opens a report over a synced-and-derived Jira-stub connection, double-clicks a bar or presses a
// row's Items button, and the drawer lists exactly the work that bar adds up. The admin side is API-seeded like
// `reports.spec.ts` (a synced stub connection, a team, the FLO board mapped to it); this spec owns its OWN connection
// (`e2e-drill-ds-*`), team (`e2e-drill-team-*`) and a throwaway reader, all created through the API before the block and
// deleted after it — one extra sync + derive, so the larger `reports.spec.ts` stays untouched. One scenario per chart family.
import { readFileSync } from "node:fs";
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { apiAsAdmin, awaitDerivedSprint, axeViolations, configureMetricsViaApi, expect, login, syncStubDataSourceViaApi, test, uniqueText } from "./helpers";

const EXPECTED = JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
  golden: { sprint: { sprintId: number; name: string } };
};
const GOLDEN = EXPECTED.golden.sprint;

let api: APIRequestContext | undefined;
// Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
let dataSourceId: number | undefined;
let teamId = 0;
let userId: number | undefined;
let reader = { email: "", password: "" };

/** "1 item" / "5 items" — the way the SPA counts. */
const items = (count: number) => `${count} ${count === 1 ? "item" : "items"}`;

/** The numbers of the drawer's figure column (the last cell of each row; a dash is an unestimated item, worth 0 MD). */
async function figures(rows: Locator): Promise<number[]> {
  const cells = await rows.evaluateAll((trs) => trs.map((tr) => (tr.lastElementChild as HTMLElement).innerText.trim()));
  return cells.map((text) => (text === "—" ? 0 : Number(text)));
}

/**
 * Wait for every running CSS transition on the page to finish (the drawer slides in and its overlay fades in over ~200 ms): an axe
 * colour-contrast scan taken mid-fade reads the half-transparent overlay and flags text that is fine once settled. Endless animations
 * (a spinner) are not waited for.
 */
async function transitionsSettled(page: Page): Promise<void> {
  await page.waitForFunction(() =>
    document.getAnimations().every((animation) => animation.playState !== "running" || animation.effect?.getComputedTiming().iterations === Infinity),
  );
}

interface WeekRow {
  index: number;
  label: string;
  md: number;
  items: number;
}

/** The period table's data rows (week label, MD, items) — read from the page, the oracle the drawer must reconcile with. */
async function readWeeks(table: Locator): Promise<WeekRow[]> {
  const rows = await table.getByRole("row").all();
  const weeks: WeekRow[] = [];
  for (const [index, row] of rows.slice(1).entries()) {
    const [label, mdText, itemsText] = await row.getByRole("cell").allInnerTexts();
    weeks.push({ index, label: label.trim(), md: Number(mdText), items: Number(itemsText) });
  }
  return weeks;
}

test.describe("drill to items, read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync plus the derivation that follows it, each wait bounded (340s / 240s).
    test.setTimeout(720_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(adminApi, "e2e-drill-ds"));
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, uniqueText("e2e-drill-team")));

    const name = uniqueText("e2e-drill-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
    expect(created.status(), await created.text()).toBe(201);
    userId = (await created.json()).id as number;

    await awaitDerivedSprint(adminApi, teamId, GOLDEN.sprintId);
  });

  test.afterAll(async () => {
    // A FRESH admin session (the beforeAll token may have expired across the bounded waits). Children first: the connection
    // (soft delete), the team its board pointed at, the reader.
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

  test("throughput: a week's Items button lists the tasks behind the bar, the list reconciles with the row, Back and browser Back restore the chart", async ({
    page,
  }) => {
    await login(page, reader.email, reader.password);
    // The golden sprint's window holds several weeks of deliveries; the deep link names this spec's connection, team and sprint.
    await page.goto(`/reports/throughput?connectionId=${dataSourceId}&teamId=${teamId}&sprintId=${GOLDEN.sprintId}`);
    await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
    const chart = page.getByRole("group", { name: "Chart: delivered scope by period", exact: true });
    await expect(chart).toBeVisible();
    await expect(page.getByText("Double-click a bar to list the items behind it — or use a row's Items button.")).toBeVisible();

    const table = page.getByRole("table", { name: "Delivered per period, as a table" });
    await expect(table.getByRole("columnheader", { name: "Items", exact: true })).toBeVisible();
    const weeks = await readWeeks(table);
    const busiest = weeks.filter((week) => week.items > 0 && week.md > 0).sort((a, b) => b.md - a.md)[0];
    expect(busiest, "the golden sprint's window delivered estimated work").toBeDefined();
    const button = table.getByRole("button", { name: `Show the items of ${busiest.label}`, exact: true });

    // The keyboard route: the row's Items button opens the drawer for exactly that week.
    await button.focus();
    await page.keyboard.press("Enter");
    const drawer = page.getByRole("dialog", { name: `${busiest.label} · Delivered`, exact: true });
    await expect(drawer).toBeVisible();
    await expect(page).toHaveURL(/drill=bucket/);

    // The list reconciles with the bar: the server's own line equals the row, one table row per item, the figures add up to the MD.
    await expect(drawer.getByText(`${items(busiest.items)} · ${busiest.md} MD`, { exact: true })).toBeVisible();
    const listed = drawer.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr");
    await expect(listed).toHaveCount(busiest.items);
    const total = (await figures(listed)).reduce((sum, value) => sum + value, 0);
    expect(Math.round(total * 100) / 100, "the listed figures add up to the bar's MD").toBe(busiest.md);
    await expect(drawer.getByText("The data changed since the chart was drawn.")).toHaveCount(0);

    // An open dialog is clean for assistive technology (named, X labelled, the table region focusable) — scanned once it has settled.
    await transitionsSettled(page);
    expect(await axeViolations(page)).toEqual([]);

    // Back to the chart closes it, the URL loses the drill and focus returns to the button that opened it.
    await drawer.getByRole("button", { name: "Back to the chart", exact: true }).click();
    await expect(drawer).toHaveCount(0);
    await expect(page).not.toHaveURL(/drill=/);
    await expect(button).toBeFocused();

    // The browser's Back button closes it too, back on the same page with the same filters.
    await button.click();
    await expect(drawer).toBeVisible();
    await page.goBack();
    await expect(drawer).toHaveCount(0);
    await expect(page).toHaveURL(/\/reports\/throughput\?/);
    await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));
    await expect(page).not.toHaveURL(/drill=/);

    // Escape closes it as well.
    await button.click();
    await expect(drawer).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);

    // The pointer route: a double-click on the tallest bar opens the same week; closing returns focus to the chart's card.
    await chart.locator(".recharts-bar-rectangle").nth(busiest.index).dblclick();
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText(`${items(busiest.items)} · ${busiest.md} MD`, { exact: true })).toBeVisible();
    await drawer.getByRole("button", { name: "Close", exact: true }).click();
    await expect(drawer).toHaveCount(0);
    await expect(page.locator('[data-drill-anchor="throughput-period"]')).toBeFocused();
  });
});
