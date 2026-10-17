// The DRILL from a bar to the items behind it (`.claude/docs/reports.md` "Drill to items"), read the way D12 says everyone reads
// reports: a NON-ADMIN user opens a report over a synced-and-derived Jira-stub connection, double-clicks a bar or presses a
// row's Items button, and the drawer lists exactly the work that bar adds up. The admin side is API-seeded like
// `reports.spec.ts` (a synced stub connection, a team, the FLO board mapped to it); this spec owns its OWN connection
// (`e2e-drill-ds-*`), team (`e2e-drill-team-*`) and a throwaway reader, all created through the API before the block and
// deleted after it — one extra sync + derive, so the larger `reports.spec.ts` stays untouched. One scenario per chart family.
import { readFileSync } from "node:fs";
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

const EXPECTED = JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
  golden: {
    sprint: {
      sprintId: number;
      name: string;
      committedMd: number;
      committedItems: number;
      committedIssueKeys: string[];
      addedIssueKeys: string[];
      finalMd: number;
      finalItems: number;
      carriedOverMd: number;
      carriedOverItems: number;
      carriedOverIssueKeys: string[];
    };
  };
};
const GOLDEN = EXPECTED.golden.sprint;

let api: APIRequestContext | undefined;
// Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
let dataSourceId: number | undefined;
let teamId = 0;
let teamName = "";
let userId: number | undefined;
let reader = { email: "", password: "" };

/**
 * A year-long window ending a week after the stub's reference date: the stub's work is finished in autumn 2025, so the
 * distribution reports (which read the tasks finished in the period) need a window that wide to have a histogram to drill.
 */
const YEAR_WINDOW = `from=${stubDayOffset(-365)}&to=${stubDayOffset(7)}`;

/** "1 item" / "5 items" — the way the SPA counts. */
const items = (count: number) => `${count} ${count === 1 ? "item" : "items"}`;

/** An MD figure the way the SPA prints it ("61", "12.5"). */
const md = (value: number) => String(Math.round(value * 100) / 100);

/** The issue keys of the drawer's rows (the first cell of each). */
async function issueKeys(rows: Locator): Promise<string[]> {
  return rows.evaluateAll((trs) => trs.map((tr) => (tr.firstElementChild as HTMLElement).innerText.trim()));
}

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

/** The most items a drill lists (`DRILL_MAX_ITEMS`); the summary line still counts them all. */
const DRILL_CAP = 500;

/**
 * The drawer lists one table row per item up to the cap; a bar with more says so ("Showing the 500 largest of 685 items.") and the
 * summary line (asserted by the caller) keeps the full count.
 */
async function expectListedRows(drawer: Locator, listed: Locator, count: number): Promise<void> {
  await expect(listed).toHaveCount(Math.min(count, DRILL_CAP));
  const note = drawer.getByText(/^Showing the \d+ largest of \d+ items\.$/);
  if (count > DRILL_CAP) await expect(note).toHaveText(`Showing the ${DRILL_CAP} largest of ${count} items.`);
  else await expect(note).toHaveCount(0);
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

interface RangeRow {
  index: number;
  /** The range as the histogram table words it ("2 – 4", "≥ 8"), without the table's "(long tail)" marker. */
  label: string;
  count: number;
}

/** The histogram table's data rows (range, item count) — read from the page, the oracle the drawer must reconcile with. */
async function readRanges(table: Locator): Promise<RangeRow[]> {
  const rows = await table.getByRole("row").all();
  const ranges: RangeRow[] = [];
  for (const [index, row] of rows.slice(1).entries()) {
    const [label, countText] = await row.getByRole("cell").allInnerTexts();
    ranges.push({ index, label: label.replace("(long tail)", "").trim(), count: Number(countText) });
  }
  return ranges;
}

/** The bounds a range label spells: "2 – 4" is [2, 4], the open tail "≥ 8" is [8, ∞). */
function boundsOf(label: string): [number, number] {
  const tail = /^≥ (\S+)$/.exec(label);
  if (tail) return [Number(tail[1]), Number.POSITIVE_INFINITY];
  const [from, to] = label.split(" – ").map(Number);
  return [from, to];
}

/**
 * The keyboard route of ONE histogram drill, reconciled against the table beside the chart: pick the busiest range that is not the
 * open tail, press Enter on its Items button, and check the drawer (title, URL, the server's summary line, one table row per item,
 * every figure inside the range, no "data changed" note, a clean axe scan), then close it with Back to the chart and land focus
 * on the button again. [view] is the report's own `view` value as the URL spells it (`AT_START`, `BLOCKED_WORKING_DAYS`).
 */
async function drillBusiestRange(
  page: Page,
  { title, view, figureHeader }: { title: string; view: string; figureHeader: string },
): Promise<void> {
  const table = page.getByRole("table", { name: `Histogram, as a table — ${title}`, exact: true });
  await expect(table, `the team finished enough items in the year for the ${title} histogram`).toBeVisible();
  await expect(table.getByRole("columnheader", { name: "Item list", exact: true })).toBeVisible();
  const ranges = await readRanges(table);
  const busiest = ranges.filter((range) => !range.label.startsWith("≥") && range.count > 0).sort((a, b) => b.count - a.count)[0];
  expect(busiest, `some ${title} range holds items`).toBeDefined();
  const button = table.getByRole("button", { name: `Show the items of ${title}: ${busiest.label}`, exact: true });

  await button.focus();
  await page.keyboard.press("Enter");
  const drawer = page.getByRole("dialog", { name: `${busiest.label} · ${title}`, exact: true });
  await expect(drawer).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`drill=hist%3A${view}%3A\\d+`));

  await expect(drawer.getByText(items(busiest.count), { exact: true })).toBeVisible();
  const listed = drawer.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr");
  await expectListedRows(drawer, listed, busiest.count);
  await expect(drawer.getByRole("columnheader", { name: figureHeader, exact: true })).toBeVisible();
  const [from, to] = boundsOf(busiest.label);
  // Figures and range labels are both rounded, so a value on an edge may read a tenth either side.
  for (const value of await figures(listed)) {
    expect(value, `${value} lies in ${busiest.label}`).toBeGreaterThanOrEqual(from - 0.1);
    expect(value, `${value} lies in ${busiest.label}`).toBeLessThanOrEqual(to + 0.1);
  }
  await expect(drawer.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
  await transitionsSettled(page);
  expect(await axeViolations(page)).toEqual([]);

  await drawer.getByRole("button", { name: "Back to the chart", exact: true }).click();
  await expect(drawer).toHaveCount(0);
  await expect(page).not.toHaveURL(/drill=/);
  await expect(button).toBeFocused();
}

/**
 * The cycle-time journey: the working-days histogram's busiest body range drilled by the keyboard route (the row's Items button,
 * reconciled with the table), closed by Back and by the browser's Back, then by the pointer route (a double-click on the bar).
 */
async function drillCycleTimeRange(page: Page): Promise<void> {
  await page.goto(`/reports/cycle-time?connectionId=${dataSourceId}&${YEAR_WINDOW}&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();
  const table = page.getByRole("table", { name: "Histogram, as a table — Working days", exact: true });
  await expect(table, "the team finished enough tasks in the year for a histogram").toBeVisible();
  await expect(table.getByRole("columnheader", { name: "Item list", exact: true })).toBeVisible();
  await expect(page.getByText("Double-click a bar to list the items behind it — or use the button in a row's Item list column.").first()).toBeVisible();

  // The busiest range that is not the open tail (the tail is its own series; the pointer route below hits a body bar).
  const ranges = await readRanges(table);
  const busiest = ranges.filter((range) => !range.label.startsWith("≥") && range.count > 0).sort((a, b) => b.count - a.count)[0];
  expect(busiest, "some working-days range holds tasks").toBeDefined();
  const button = table.getByRole("button", { name: `Show the items of Working days: ${busiest.label}`, exact: true });

  // The keyboard route: the row's Items button opens the drawer for exactly that range of that distribution.
  await button.focus();
  await page.keyboard.press("Enter");
  const drawer = page.getByRole("dialog", { name: `${busiest.label} · Working days`, exact: true });
  await expect(drawer).toBeVisible();
  await expect(page).toHaveURL(/drill=hist%3AWORKING_DAYS%3A\d+/);

  // The list reconciles with the bar: the server's own line equals the row's count, one table row per task, each inside the range.
  await expect(drawer.getByText(items(busiest.count), { exact: true })).toBeVisible();
  const listed = drawer.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr");
  await expectListedRows(drawer, listed, busiest.count);
  await expect(drawer.getByRole("columnheader", { name: "Working days", exact: true })).toBeVisible();
  const [from, to] = boundsOf(busiest.label);
  // Figures and range labels are both rounded to one decimal, so a value on an edge may read a tenth either side.
  for (const value of await figures(listed)) {
    expect(value, `${value} working days lies in ${busiest.label}`).toBeGreaterThanOrEqual(from - 0.1);
    expect(value, `${value} working days lies in ${busiest.label}`).toBeLessThanOrEqual(to + 0.1);
  }
  await expect(drawer.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
  await transitionsSettled(page);
  expect(await axeViolations(page)).toEqual([]);

  // Back to the chart closes it, the URL loses the drill and focus returns to the button; the browser's Back does the same.
  await drawer.getByRole("button", { name: "Back to the chart", exact: true }).click();
  await expect(drawer).toHaveCount(0);
  await expect(page).not.toHaveURL(/drill=/);
  await expect(button).toBeFocused();
  await button.click();
  await expect(drawer).toBeVisible();
  await page.goBack();
  await expect(drawer).toHaveCount(0);
  await expect(page).toHaveURL(/\/reports\/cycle-time\?/);
  await expect(page).not.toHaveURL(/drill=/);

  // The pointer route: a double-click on that range's bar opens the same drawer; closing returns focus to the tasks card.
  const chart = page.getByRole("group", { name: "Histogram: number of items per range — Working days", exact: true });
  await chart.locator(".recharts-bar-rectangle").nth(busiest.index).dblclick();
  await expect(drawer).toBeVisible();
  await expect(drawer.getByText(items(busiest.count), { exact: true })).toBeVisible();
  await drawer.getByRole("button", { name: "Close", exact: true }).click();
  await expect(drawer).toHaveCount(0);
  await expect(page.locator('[data-drill-anchor="cycle-time-tasks"]')).toBeFocused();
}

test.describe("drill to items, read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync plus the derivation that follows it, each wait bounded (340s / 240s).
    test.setTimeout(720_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(adminApi, "e2e-drill-ds"));
    teamName = uniqueText("e2e-drill-team");
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, teamName));

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
    // Throughput has two drillable chart cards (period, sprint) and each repeats the hint: read the period card's own.
    await expect(
      page.locator('[data-drill-anchor="throughput-period"]').getByText("Double-click a bar to list the items behind it — or use a row's Items button."),
    ).toBeVisible();

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
  test("commitments and Home: the golden sprint's committed and final items reconcile with the figures, by Items button and by double-click, and Home's Items menu lists a team's last sprint", async ({
    page,
  }) => {
    await login(page, reader.email, reader.password);
    await page.goto(`/reports/commitments?connectionId=${dataSourceId}&teamId=${teamId}&sprintId=${GOLDEN.sprintId}`);
    await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
    const chart = page.getByRole("group", { name: "Chart: initial and final scope by sprint", exact: true });
    await expect(chart).toBeVisible();
    await expect(page.getByText("Double-click a bar to list the items behind it — or use a row's Items button.")).toBeVisible();
    const sprints = page.getByRole("table", { name: "Sprints and their figures" });
    await expect(sprints.getByRole("columnheader", { name: "Items", exact: true })).toBeVisible();

    // The keyboard route: the sprint row has one Items button per bar, the committed one first.
    const committedButton = sprints.getByRole("button", { name: `Show the items of ${GOLDEN.name} · Committed`, exact: true });
    await expect(sprints.getByRole("button", { name: `Show the items of ${GOLDEN.name} · Final`, exact: true })).toBeVisible();
    await committedButton.focus();
    await page.keyboard.press("Enter");
    const committed = page.getByRole("dialog", { name: `${GOLDEN.name} · Committed`, exact: true });
    await expect(committed).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`drill=sprint%3A${teamId}%3A${GOLDEN.sprintId}%3ACOMMITTED`));

    // The list IS the generator's committed scope: the count and MD of the figure, the very issue keys, the figures adding up.
    await expect(committed.getByText(`${items(GOLDEN.committedItems)} · ${md(GOLDEN.committedMd)} MD`, { exact: true })).toBeVisible();
    const listed = committed.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr");
    await expect(listed).toHaveCount(GOLDEN.committedItems);
    expect((await issueKeys(listed)).sort()).toEqual([...GOLDEN.committedIssueKeys].sort());
    const total = (await figures(listed)).reduce((sum, value) => sum + value, 0);
    expect(Math.round(total * 100) / 100, "the listed estimates add up to the committed MD").toBe(GOLDEN.committedMd);
    await expect(committed.getByText("The data changed since the chart was drawn.")).toHaveCount(0);

    await committed.getByRole("button", { name: "Back to the chart", exact: true }).click();
    await expect(committed).toHaveCount(0);
    await expect(page).not.toHaveURL(/drill=/);
    await expect(committedButton).toBeFocused();

    // The final bar's button lists the final scope (committed + added).
    await sprints.getByRole("button", { name: `Show the items of ${GOLDEN.name} · Final`, exact: true }).click();
    const final = page.getByRole("dialog", { name: `${GOLDEN.name} · Final`, exact: true });
    await expect(final.getByText(`${items(GOLDEN.finalItems)} · ${md(GOLDEN.finalMd)} MD`, { exact: true })).toBeVisible();
    await expect(final.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr")).toHaveCount(GOLDEN.finalItems);
    await page.keyboard.press("Escape");
    await expect(final).toHaveCount(0);

    // The pointer route: a double-click on the sprint's first bar (the initial one) opens the committed list again.
    await chart.locator(".recharts-bar-rectangle").first().dblclick();
    await expect(committed).toBeVisible();
    await expect(committed.getByText(`${items(GOLDEN.committedItems)} · ${md(GOLDEN.committedMd)} MD`, { exact: true })).toBeVisible();
    await committed.getByRole("button", { name: "Close", exact: true }).click();
    await expect(committed).toHaveCount(0);

    // Home: the team's last closed sprint — whichever it is — opens from the row's Items menu and equals the figure printed in the row.
    await page.goto("/");
    const overview = page.getByRole("group", { name: "Unit overview" });
    const tile = overview.getByRole("region", { name: "Commitments and velocity", exact: true });
    await expect(tile).toHaveAttribute("aria-busy", "false");
    const row = tile
      .getByRole("table", { name: "Each team's last closed sprint, as a table" })
      .getByRole("row", { name: new RegExp(`^${teamName} FLO Sprint \\d+`) });
    await expect(row).toBeVisible();
    const cells = await row.getByRole("cell").allInnerTexts();
    const [, sprintName, , , finalMdText] = cells.map((text) => text.trim());
    await row.getByRole("button", { name: `Operations for ${teamName} · ${sprintName}`, exact: true }).click();
    await page.getByRole("menuitem", { name: "Final", exact: true }).click();
    const home = page.getByRole("dialog", { name: `${teamName} · ${sprintName} · Final`, exact: true });
    await expect(home).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/\\?drill=sprint%3A${teamId}%3A\\d+%3AFINAL`));
    await expect(home.getByText(new RegExp(`^\\d+ items? · ${finalMdText} MD$`))).toBeVisible();
    await expect(home.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
    await page.goBack();
    await expect(home).toHaveCount(0);
    await expect(page).not.toHaveURL(/drill=/);
  });

  test("sprint consistency: a carried-over segment's items, the final scope's Items menu entry and the sliced (Bug) list reconcile with the figures", async ({
    page,
  }) => {
    await login(page, reader.email, reader.password);
    const consistency = `/reports/sprint-consistency?connectionId=${dataSourceId}&teamId=${teamId}&sprintId=${GOLDEN.sprintId}`;
    await page.goto(consistency);
    await expect(page.getByRole("heading", { level: 2, name: "Sprint consistency", exact: true })).toBeVisible();
    const partition = page.getByRole("group", { name: "Chart: final scope split into carried over, delivered and dropped, by sprint", exact: true });
    await expect(partition).toBeVisible();
    await expect(page.getByText("Double-click a bar to list the items behind it — or use the Items entry in a row's menu.")).toHaveCount(3);
    const sprints = page.getByRole("table", { name: "Sprints and their figures" });

    // The pointer route: the stacked partition's first segment (bottom to top: carried over, delivered, dropped) is the carried-over bucket.
    await partition.locator(".recharts-bar-rectangle").first().dblclick();
    const carried = page.getByRole("dialog", { name: `${GOLDEN.name} · Carried over`, exact: true });
    await expect(carried).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`drill=sprint%3A${teamId}%3A${GOLDEN.sprintId}%3ACARRIED_OVER`));
    await expect(carried.getByText(`${items(GOLDEN.carriedOverItems)} · ${md(GOLDEN.carriedOverMd)} MD`, { exact: true })).toBeVisible();
    const carriedRows = carried.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr");
    await expect(carriedRows).toHaveCount(GOLDEN.carriedOverItems);
    expect((await issueKeys(carriedRows)).sort()).toEqual([...GOLDEN.carriedOverIssueKeys].sort());
    const carriedTotal = (await figures(carriedRows)).reduce((sum, value) => sum + value, 0);
    expect(Math.round(carriedTotal * 100) / 100, "the listed estimates add up to the carried-over MD").toBe(GOLDEN.carriedOverMd);
    await expect(carried.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
    await expect(carried.getByText(/^Sliced by activity type or work category/)).toHaveCount(0);
    await carried.getByRole("button", { name: "Close", exact: true }).click();
    await expect(carried).toHaveCount(0);
    await expect(page.locator('[data-drill-anchor="consistency-partition"]')).toBeFocused();

    // The keyboard route: the row's Items menu has an entry per bucket, including Final, which no chart draws (committed + added).
    const menu = sprints.getByRole("button", { name: `Operations for ${GOLDEN.name}`, exact: true });
    await menu.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("menuitem")).toHaveText(["Committed", "Added", "Removed", "Final", "Delivered", "Carried over", "Dropped"]);
    await page.getByRole("menuitem", { name: "Final", exact: true }).click();
    const final = page.getByRole("dialog", { name: `${GOLDEN.name} · Final`, exact: true });
    await expect(final.getByText(`${items(GOLDEN.finalItems)} · ${md(GOLDEN.finalMd)} MD`, { exact: true })).toBeVisible();
    const finalRows = final.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr");
    await expect(finalRows).toHaveCount(GOLDEN.finalItems);
    expect((await issueKeys(finalRows)).sort()).toEqual([...GOLDEN.committedIssueKeys, ...GOLDEN.addedIssueKeys].sort());
    await page.keyboard.press("Escape");
    await expect(final).toHaveCount(0);
    await expect(menu).toBeFocused();

    // Sliced by activity type the figures are live: the table row is the oracle, whichever bucket holds the most Bug items.
    await page.goto(`${consistency}&activityType=Bug`);
    await expect(page.getByText(/^Sliced by activity type or work category/)).toBeVisible();
    const row = sprints.getByRole("row", { name: new RegExp(GOLDEN.name) });
    // `allInnerTexts` does not wait: the "Sliced by…" note renders before the report row does, so wait for the row's twelve cells
    // (sprint, team, completed, the seven figures, drift, items) and for a figure to be there before reading them.
    await expect(row.getByRole("cell")).toHaveCount(12);
    await expect(row.getByRole("cell").nth(3)).toHaveText(/^\S+ \(\d+\)$/);
    const cells = (await row.getByRole("cell").allInnerTexts()).map((text) => text.trim());
    // Cells 3..9 are the seven figures in the table's column order, each "MD (items)".
    const buckets = ["Committed", "Added", "Removed", "Final", "Delivered", "Carried over", "Dropped"].map((label, index) => {
      const [, mdText, itemsText] = /^(\S+) \((\d+)\)$/.exec(cells[3 + index]) ?? [];
      return { label, md: Number(mdText), items: Number(itemsText) };
    });
    const biggest = buckets.reduce((best, bucket) => (bucket.items > best.items ? bucket : best));
    expect(biggest.items, "the Bug slice has items in some bucket of the golden sprint").toBeGreaterThan(0);
    await row.getByRole("button", { name: `Operations for ${GOLDEN.name}`, exact: true }).click();
    await page.getByRole("menuitem", { name: biggest.label, exact: true }).click();
    const sliced = page.getByRole("dialog", { name: `${GOLDEN.name} · ${biggest.label}`, exact: true });
    await expect(sliced.getByText(`${items(biggest.items)} · ${md(biggest.md)} MD`, { exact: true })).toBeVisible();
    await expect(sliced.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr")).toHaveCount(biggest.items);
    await expect(sliced.getByText(/^Sliced by activity type or work category/)).toBeVisible();
    await expect(sliced.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
    await page.goBack();
    await expect(sliced).toHaveCount(0);
    await expect(page).toHaveURL(/activityType=Bug/);
    await expect(page).not.toHaveURL(/drill=/);
  });

  test("cycle time: a working-days range's Items button lists the tasks inside the range, the list reconciles with the histogram row, Back restores the page", async ({
    page,
  }) => {
    await login(page, reader.email, reader.password);
    await drillCycleTimeRange(page);
  });

  test("task accuracy and blocked time: a ratio range and a blocked-days range drill to their items, each list reconciling with its histogram row", async ({
    page,
  }) => {
    await login(page, reader.email, reader.password);

    // Task accuracy, at-start view: the figure is actual ÷ estimate.
    await page.goto(`/reports/task-estimation-accuracy?connectionId=${dataSourceId}&${YEAR_WINDOW}&teamId=${teamId}`);
    await expect(page.getByRole("heading", { level: 2, name: "Task estimation accuracy", exact: true })).toBeVisible();
    await drillBusiestRange(page, { title: "Against the estimate at start", view: "AT_START", figureHeader: "Actual ÷ estimate" });

    // Blocked time (the tasks, the default item kind): the figure is blocked working days, over the blocked tasks only (the never-blocked ones are not in the histogram).
    await page.goto(`/reports/blocked-time?connectionId=${dataSourceId}&${YEAR_WINDOW}`);
    await expect(page.getByRole("heading", { level: 2, name: "Blocked time", exact: true })).toBeVisible();
    await drillBusiestRange(page, { title: "Blocked working days", view: "BLOCKED_WORKING_DAYS", figureHeader: "Blocked working days" });
  });

  test("estimate adjustments: a withheld change histogram offers no drill, and the page's other figures stay", async ({ page }) => {
    await login(page, reader.email, reader.password);

    // The change histogram charts only the ADJUSTED tasks, so a small team's year can fall under the minimum sample size (the stub's
    // FLO board has a handful): the drill is offered exactly where a histogram is drawn.
    await page.goto(`/reports/estimate-adjustments?connectionId=${dataSourceId}&${YEAR_WINDOW}&teamId=${teamId}`);
    await expect(page.getByRole("heading", { level: 2, name: "Estimate adjustments", exact: true })).toBeVisible();
    const tasks = page.getByRole("heading", { level: 4, name: "Tasks", exact: true }).locator("..");
    await expect(tasks.getByText(/^Only \d+ items? in this selection — at least \d+ are needed to show percentiles and a histogram\.$/)).toBeVisible();
    await expect(tasks.getByText(/^Not charted: \d+ finished items? with an unchanged estimate/)).toBeVisible();
    await expect(page.getByRole("table", { name: /^Histogram, as a table/ })).toHaveCount(0);
    await expect(page.getByRole("columnheader", { name: "Item list", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Show the items of/ })).toHaveCount(0);
    await expect(page.getByText(/Double-click a bar to list the items/)).toHaveCount(0);
  });
});
