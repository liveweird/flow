// Compare people (report 18, `.claude/docs/reports.md` "Report 18"): two people head to head over a date range, across every team, read
// the way D12 says everyone reads reports — by a NON-ADMIN user. The admin side is API-seeded like `reports-drill.spec.ts`: this spec owns
// ITS OWN synced Jira-stub connection (`e2e-compare-ds-*`), a team the FLO board is mapped to (the derive needs a configuration; the
// people compared need no team) and a throwaway reader, all created through the API before the block and deleted after it. The two
// people are the stub members that finished the most tasks in the window, found through the report itself (the oracle for the UI is the
// table's own figures, never a recomputation).
import { readFileSync } from "node:fs";
import type { APIRequestContext } from "@playwright/test";
import {
  STUB_MEMBER_CANDIDATES,
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

/** The golden sprint of the stub (`sample-data/jira/expected.json`): the derivation is done once it shows up in a team's commitments. */
const GOLDEN_SPRINT_ID = (
  JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as { golden: { sprint: { sprintId: number } } }
).golden.sprint.sprintId;

/** A year-long window ending a week after the stub's reference date: the stub's work is finished in autumn 2025. */
const YEAR_WINDOW = `from=${stubDayOffset(-365)}&to=${stubDayOffset(7)}`;

let api: APIRequestContext | undefined;
let dataSourceId: number | undefined;
let teamId = 0;
let userId: number | undefined;
let reader = { email: "", password: "" };
/** The two people compared: the stub members with the most finished tasks in the window, busiest first. */
let people: { accountId: string; displayName: string }[] = [];

const escapeRe = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** The people's finished tasks in the window, read from the report through the admin API (two at a time, as the report takes exactly two). */
async function busiestPair(adminApi: APIRequestContext): Promise<{ accountId: string; displayName: string }[]> {
  const counted: { accountId: string; displayName: string; finished: number }[] = [];
  for (let i = 0; i + 1 < STUB_MEMBER_CANDIDATES.length; i += 2) {
    const [a, b] = [STUB_MEMBER_CANDIDATES[i], STUB_MEMBER_CANDIDATES[i + 1]];
    const res = await adminApi.get(
      `/api/v1/reports/compare-people?connectionId=${dataSourceId}&${YEAR_WINDOW}&accountId=${a.accountId}&accountId=${b.accountId}`,
    );
    expect(res.ok(), await res.text()).toBeTruthy();
    const row = ((await res.json()) as { rows: { key: string; a: { n: number }; b: { n: number } }[] }).rows.find((r) => r.key === "deliveredItems");
    counted.push({ ...a, finished: row?.a.n ?? 0 }, { ...b, finished: row?.b.n ?? 0 });
  }
  return counted
    .sort((x, y) => y.finished - x.finished)
    .slice(0, 2)
    .map(({ accountId, displayName }) => ({ accountId, displayName }));
}

test.describe("compare people, read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync plus the derivation that follows it, each wait bounded (340s / 240s).
    test.setTimeout(720_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(adminApi, "e2e-compare-ds"));
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, uniqueText("e2e-compare-team")));

    const name = uniqueText("e2e-compare-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
    expect(created.status(), await created.text()).toBe(201);
    userId = (await created.json()).id as number;

    await awaitDerivedSprint(adminApi, teamId, GOLDEN_SPRINT_ID);
    people = await busiestPair(adminApi);
  });

  test.afterAll(async () => {
    // A FRESH admin session (the beforeAll token may have expired across the bounded waits). Children first.
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

  test("a regular user compares two people: the table has both, the better figure is tinted teal at most once per row, the cycle-time histogram draws both", async ({ page }) => {
    const [a, b] = people;
    await login(page, reader.email, reader.password);
    // A bare visit is the pickers and an empty state; nothing is compared until both people are picked.
    await page.goto("/reports/compare-people");
    await expect(page.getByRole("heading", { level: 2, name: "Compare people", exact: true })).toBeVisible();
    await expect(page.getByText("Pick two people to compare them.")).toBeVisible();
    await expect(page.getByRole("combobox", { name: "Person B", exact: true })).toBeDisabled();

    // The deep link of the spec's connection and window: both people ride in the URL, side A first.
    await page.goto(`/reports/compare-people?connectionId=${dataSourceId}&${YEAR_WINDOW}&accountId=${a.accountId}&accountId=${b.accountId}`);
    const table = page.getByRole("table", { name: "Comparison of the two people, metric by metric", exact: true });
    await expect(table).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`accountId=${a.accountId}&accountId=${b.accountId}`));
    await expect(table.getByRole("columnheader", { name: `A · ${a.displayName}`, exact: true })).toBeVisible();
    await expect(table.getByRole("columnheader", { name: `B · ${b.displayName}`, exact: true })).toBeVisible();

    // Delivered (items): a figure with its sample on both sides, and a difference cell.
    const delivered = table.getByRole("rowheader", { name: "Delivered (items)", exact: true }).locator("xpath=ancestor::tr");
    const cells = delivered.getByRole("cell");
    // The better side's cell ends with its words, so the sample may be followed by " (better by 3 items)".
    await expect(cells.nth(0)).toHaveText(/^\d+n = \d+( \(better by \d+ items?\))?$/);
    await expect(cells.nth(1)).toHaveText(/^\d+n = \d+( \(better by \d+ items?\))?$/);

    // The Difference column is the plain absolute gap: no tint, no icon, no sign. The better figure's cell (at most one per row) is tinted teal
    // (never red), carries a check icon and ends with its words ("better by 1.4 days", "closer to 1.0 by 0.1").
    const tinted = table.locator("tbody td[data-better]");
    for (const text of await tinted.evaluateAll((els) => els.map((el) => el.textContent ?? ""))) {
      expect(text).toMatch(/\((better|closer to 1\.0) by [^)]+\)$/);
    }
    const perRow = await table.locator("tbody tr").evaluateAll((rows) => rows.map((row) => row.querySelectorAll("td[data-better]").length));
    expect(Math.max(...perRow)).toBeLessThanOrEqual(1);
    const badStyles = await tinted.evaluateAll((els) => els.filter((el) => /red/.test(`${el.getAttribute("style")} ${el.className}`)).length);
    expect(badStyles, "no tint is red").toBe(0);
    const differences = await table.locator("tbody tr").evaluateAll((rows) =>
      rows.flatMap((row) => {
        const cellsOfRow = row.querySelectorAll("td");
        const last = cellsOfRow[cellsOfRow.length - 1];
        return last ? [{ text: last.textContent ?? "", tinted: last.hasAttribute("data-better"), icons: last.querySelectorAll("svg").length }] : [];
      }),
    );
    for (const difference of differences) {
      expect(difference.text).toMatch(/^(—|\d[\d.]*( pp)?)$/);
      expect(difference.tinted).toBe(false);
      expect(difference.icons).toBe(0);
    }
    // A tinted cell carries its check icon (the stub's two busiest people may tie on every row, so there may be none at all).
    if ((await tinted.count()) > 0) await expect(tinted.first().locator("svg")).toHaveCount(1);

    // The cycle-time histogram draws the two people side by side: a numbered legend of both, a table column each.
    const working = page.getByRole("table", { name: "Histogram, as a table — Working days", exact: true });
    await expect(working).toBeVisible();
    await expect(working.getByRole("columnheader", { name: `1. ${a.displayName}`, exact: true })).toBeVisible();
    await expect(working.getByRole("columnheader", { name: `2. ${b.displayName}`, exact: true })).toBeVisible();
    await expect(page.getByRole("list", { name: "Chart legend" }).first().getByRole("listitem")).toHaveCount(2);

    expect(await axeViolations(page)).toEqual([]);
  });

  test("a figure opens the items behind it: the drawer's count is the cell's, Escape closes it and focus returns to the figure", async ({ page }) => {
    const [a, b] = people;
    await login(page, reader.email, reader.password);
    await page.goto(`/reports/compare-people?connectionId=${dataSourceId}&${YEAR_WINDOW}&accountId=${a.accountId}&accountId=${b.accountId}`);
    const table = page.getByRole("table", { name: "Comparison of the two people, metric by metric", exact: true });
    const delivered = table.getByRole("rowheader", { name: "Delivered (items)", exact: true }).locator("xpath=ancestor::tr");
    const figure = (await delivered.getByRole("cell").nth(0).innerText()).split("\n")[0].trim();
    const button = delivered.getByRole("button", { name: new RegExp(`^${escapeRe(figure)} — A · ${escapeRe(a.displayName)}: show the items of Delivered \\(items\\)$`) });

    await button.focus();
    await page.keyboard.press("Enter");
    const drawer = page.getByRole("dialog", { name: `Delivered tasks · A · ${a.displayName}`, exact: true });
    await expect(drawer).toBeVisible();
    await expect(page).toHaveURL(/drill=cell%3ADELIVERED%3Bs%3D/);
    // The server's own line equals the cell's count (singular for one), and the table lists at most the cap.
    await expect(drawer.getByText(new RegExp(`^${escapeRe(figure)} items? ·`))).toBeVisible();
    await expect(drawer.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
    await expect(drawer.getByRole("table", { name: "Items behind the bar" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);
    await expect(page).not.toHaveURL(/drill=/);
    await expect(button).toBeFocused();
  });
});
