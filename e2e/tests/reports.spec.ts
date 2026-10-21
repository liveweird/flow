// The v0.3.0 REPORTS journey, read the way D12 says everyone reads them: a NON-ADMIN user opens
// the Delivery and Estimation reports over a synced-and-derived Jira-stub connection and finds the
// golden FLO sprint's figures (`sample-data/jira/expected.json` `golden.sprint`) exactly; batch 2
// adds the Flow metrics group (WIP, aging, blocked time, epic progress), Data quality, the Cost
// matrix, Home's overview of the picked teams (and the picker) and Commitments' last-N-sprints and member drill; the checkup's A15
// closes the last four pages (epic accuracy, estimate adjustments, reported time, estimated backlog). The
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
  deleteViaApi,
  expect,
  login,
  openFilters,
  showTeamsOnHome,
  stubDayOffset,
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
  childSumMd: number;
  startDate: string;
  dueDate: string;
}

const EXPECTED = JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
  golden: { sprint: GoldenSprint; epic: GoldenEpic };
};
const GOLDEN = EXPECTED.golden.sprint;
const GOLDEN_EPIC = EXPECTED.golden.epic;

/**
 * The period the period-bound batch-2 reports read: the stub's data ends at its reference date, so
 * a fixed window around it keeps them independent of today (the default "last 90 days" would slide
 * off the sample data), passed as `from`/`to` in the URL — the filter IS the URL.
 */
const dayOffset = stubDayOffset;
const WINDOW = `from=${dayOffset(-184)}&to=${dayOffset(7)}`;
/**
 * The stub's epics all FINISHED in autumn 2025, before `WINDOW` opens, so the epic reports (which
 * read epics finished — or started — in the period) read a year-long window instead; the snapshot
 * reports keep `WINDOW`.
 */
const EPIC_WINDOW = `from=${dayOffset(-365)}&to=${dayOffset(7)}`;

/** Man-days the way the SPA writes them (at most two decimals, no trailing zeros). */
const md = (value: number) => String(Math.round(value * 100) / 100);
/** A sprint-consistency cell: "MD (items)". */
const mdItems = (mdValue: number, items: number) => `${md(mdValue)} (${items})`;

/** The seven sections of Home, in order (region names; each title is also the link into the full report). */
const HOME_SECTIONS = [
  "Last sprint",
  "How big the work is",
  "How well estimates hold",
  "Epics in progress",
  "Tasks in progress",
  "How much is delivered",
  "What is missing from the data",
];

/** A literal as a regular-expression source (a team name). */
const escapeRe = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** A Home chart's visually hidden table (clipped, not `display: none`: attached and in the accessibility tree, never to be asserted visible). */
const homeTable = (page: Page, section: string, name: string): Locator =>
  page.getByRole("region", { name: section, exact: true }).getByRole("table", { name, exact: true });

/** A drill figure's accessible name ends "show the items of <target>": the target starts with `prefix`. */
const showFigure = (prefix: string) => new RegExp(`: show the items of ${escapeRe(prefix)}`);

let api: APIRequestContext | undefined;
// Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
let dataSourceId: number | undefined;
let dataSourceName = "";
let teamId = 0;
let teamName = "";
let memberName = "";
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
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await team.click();
  await team.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await page.getByRole("combobox", { name: "Period", exact: true }).click();
  await page.getByRole("option", { name: "One sprint", exact: true }).click();
  await page.getByRole("combobox", { name: "Sprint", exact: true }).click();
  // A sprint option is labelled with its dates: `name · from → to`.
  await page.getByRole("option", { name: new RegExp(`^${GOLDEN.name} · `) }).click();
  await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
}

/** Narrow the open report through the filter bar: the seeded team, then a period preset (the bar is unfolded first). */
async function pickTeamAndPeriod(page: Page, period: string): Promise<void> {
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await team.click();
  await team.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await page.getByRole("combobox", { name: "Period", exact: true }).click();
  await page.getByRole("option", { name: period, exact: true }).click();
}

/**
 * A distribution's percentile strip OR its counts-only minimum-sample notice — the notice matched
 * by its own wording, so the cycle-time trend's "no period has enough items" note cannot stand in
 * for it. The union is what `.first()` wraps at the call sites.
 */
const percentilesOrNotice = (page: Page): Locator =>
  page
    .getByText("Median (p50)")
    .or(page.getByRole("note").filter({ hasText: /are needed to show|Nothing to measure|No estimate changed/ }));

/**
 * The golden sprint's row in the page's sprint table. Commitments' and Throughput's by-epic table repeats the sprint's name once per
 * epic segment, but it sits BELOW the sprint table (the sprint table is the first row of that name in the document).
 */
const goldenRow = (page: Page): Locator => page.getByRole("row", { name: new RegExp(GOLDEN.name) }).first();

/** One by-epic section of a sprint report, as the golden-sprint journeys read it. */
interface EpicSplit {
  /** The card's h3 and its table's accessible name. */
  heading: string;
  table: string;
  /** The table's figure headers after "Sprint" and "Epic". */
  headers: string[];
  /** What each figure column must add up to: the golden sprint's own figures. */
  totals: number[];
  /** Which figure column (an index of `headers`) is the item count to drill, and the one beside it that carries its man-days. */
  itemsColumn: number;
  mdColumn: number;
  /** The sprint bucket the drill lists, as the drawer's title words it. */
  bucket: "Committed" | "Delivered";
}

/**
 * A sprint report's by-epic section on the golden sprint: the table lists one row per epic segment, each figure column adds up to the
 * golden sprint's own figure (the segments cut the sprint exactly), and the biggest segment's items figure opens a drawer whose
 * summary line is that row's own item count and man-days, listing one table row per item. Escape closes it, focus back on the figure.
 */
async function readEpicSplit(page: Page, split: EpicSplit): Promise<void> {
  await expect(page.getByRole("heading", { level: 3, name: split.heading, exact: true })).toBeVisible();
  const table = page.getByRole("table", { name: split.table, exact: true });
  await expect(table).toBeVisible();
  const headers = (await table.getByRole("columnheader").allInnerTexts()).map((text) => text.trim());
  expect(headers).toEqual(["Sprint", "Epic", ...split.headers]);

  const rows = table.locator("tbody tr");
  const read: number[][] = [];
  for (let index = 0; index < (await rows.count()); index += 1) {
    const cells = (await rows.nth(index).getByRole("cell").allInnerTexts()).map((text) => text.trim());
    expect(cells[0], "the page is narrowed to the golden sprint").toBe(GOLDEN.name);
    read.push(cells.slice(2).map(Number));
  }
  expect(read.length, "at least one epic segment (an epic, Other epics or No epic)").toBeGreaterThan(0);
  split.totals.forEach((total, column) => {
    const sum = Math.round(read.reduce((acc, figures) => acc + figures[column], 0) * 100) / 100;
    expect(sum, `the segments' ${split.headers[column]} add up to the golden sprint's`).toBe(Math.round(total * 100) / 100);
  });

  const biggest = read.reduce((best, figures, index) => (figures[split.itemsColumn] > read[best][split.itemsColumn] ? index : best), 0);
  const itemsCount = read[biggest][split.itemsColumn];
  const button = rows.nth(biggest).getByRole("button", { name: new RegExp(`— ${escapeRe(split.headers[split.itemsColumn])}: show the items of `) });
  await button.click();
  const drawer = page.getByRole("dialog", { name: new RegExp(`^${split.bucket} — .+ · ${escapeRe(GOLDEN.name)}$`) });
  await expect(drawer).toBeVisible();
  await expect(page).toHaveURL(/drill=sprint%3A\d+%3A\d+%3A[A-Z_]+%3Ae%3D/);
  // The drawer's total IS the segment's figure: the item count and man-days of the table row, one listed task per item.
  const summary = `${itemsCount} ${itemsCount === 1 ? "item" : "items"} · ${md(read[biggest][split.mdColumn])} MD`;
  await expect(drawer.getByText(summary, { exact: true })).toBeVisible();
  await expect(drawer.getByRole("table", { name: "Items behind the bar" }).locator("tbody tr")).toHaveCount(itemsCount);
  await expect(drawer.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(drawer).toHaveCount(0);
  await expect(page).not.toHaveURL(/drill=/);
  await expect(button).toBeFocused();
}

/**
 * Sign in as the regular user and open a report by its deep link, narrowed to THIS spec's
 * connection (an unnarrowed unit-level report would also count the other specs' synced
 * connections) and to the stub's fixed window. `extra` adds the team or scope under test.
 */
async function openReport(page: Page, path: string, extra = "", window = WINDOW): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.goto(`${path}?connectionId=${dataSourceId}&${window}${extra}`);
}

/** Choose `option` in the filter bar's searchable dropdown called `label` (typing narrows the list, the click picks). */
async function pickFilter(page: Page, label: string, option: string): Promise<void> {
  const select = page.getByRole("combobox", { name: label, exact: true });
  await select.click();
  await select.fill(option);
  await page.getByRole("option", { name: option, exact: true }).click();
}

/** Empty a filter dropdown through its clear button (Mantine hides that button from the accessibility tree, so it is found by its label). */
async function clearFilter(page: Page, label: string): Promise<void> {
  await page.getByLabel(`Clear ${label}`, { exact: true }).click();
}

/**
 * Every distribution's accounting ends in a dimmed "n + reason + … = population" line; each must
 * add up (the server's per-view partition), and `lines` of them are on the page.
 */
async function expectAccountingToReconcile(page: Page, lines: number): Promise<void> {
  const equations = page.getByText(/^\d+( \+ \d+)+ = \d+$/);
  await expect(equations).toHaveCount(lines);
  for (const text of await equations.allInnerTexts()) {
    const [terms, total] = text.split(" = ");
    expect(terms.split(" + ").map(Number).reduce((sum, term) => sum + term, 0), text).toBe(Number(total));
  }
}

/** The population the first accounting list states: "Of N finished in this period:". */
async function finishedInPeriod(page: Page): Promise<number> {
  const text = await page.getByText(/^Of \d+ finished in this period:$/).first().innerText();
  return Number(/\d+/.exec(text)?.[0]);
}

/** One figure of an adjustments block (Started, Changed after start, …): the value under its label. */
async function blockFigure(block: Locator, label: string): Promise<string> {
  return (await block.getByText(label, { exact: true }).locator("xpath=following-sibling::*[1]").innerText()).trim();
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

/** The epic-progress journey: the FLO domain (the page is never read for the whole unit) → the golden epic, with its plan panel and daily table. */
async function drillIntoGoldenEpic(page: Page): Promise<void> {
  await openReport(page, "/reports/epic-progress", "&domain=FLO");
  await expect(page.getByRole("heading", { level: 2, name: "Epic progress", exact: true })).toBeVisible();

  // The domain level: the headline tiles, the connection and the domain in the address, and its epics, the golden one among them.
  await expect(page.getByRole("heading", { level: 3, name: "Domain: FLO", exact: true })).toBeVisible();
  for (const tile of ["PV (MD)", "EV (MD)", "AC (MD)", "SPI", "CPI"]) await expect(page.getByRole("group", { name: tile, exact: true })).toBeVisible();
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

/**
 * The epic-progress period view (A37): a domain opens on "This period" (the server's per-level default, so the URL carries no
 * `basis`), its curves are the period's, the weekly SPI/CPI trend sits beside its table, and "Since start" switches to the
 * cumulative curves with the choice in the URL.
 */
async function readDomainPeriodView(page: Page): Promise<void> {
  await openReport(page, "/reports/epic-progress", "&domain=FLO");
  await expect(page.getByRole("heading", { level: 3, name: "Domain: FLO", exact: true })).toBeVisible();
  const basis = page.getByRole("radiogroup", { name: "Measured over", exact: true });
  await expect(basis.getByRole("radio", { name: "This period", exact: true })).toBeChecked();
  expect(page.url(), "the default basis is not written to the URL").not.toContain("basis=");

  // The period's curves, and the headline with the cumulative figure as its small second line.
  await expect(page.getByRole("heading", { level: 3, name: "PV, EV and AC in the period", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "PV (MD)", exact: true })).toContainText("Since start:");

  // The trend: one index chart (legend, 1.0 reference line) and its numbers in a table beside it, one row per week of the window.
  await expect(page.getByRole("heading", { level: 3, name: "SPI and CPI trend", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "Chart: schedule and cost performance index per week", exact: true })).toBeVisible();
  const weeks = page.getByRole("table", { name: "SPI and CPI per week, as a table", exact: true });
  await expect(weeks.getByRole("row").nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  expect(await weeks.getByRole("row").count(), "header + one row per week of the half-year window").toBeGreaterThan(20);

  // Since start: the explicit choice lands in the URL and the cumulative curves come back.
  await basis.getByText("Since start", { exact: true }).click();
  await expect(page).toHaveURL(/basis=CUMULATIVE/);
  await expect(page.getByRole("heading", { level: 3, name: "Cumulative PV, EV and AC", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "PV (MD)", exact: true })).toContainText("This period:");
}

/** The data-quality journey: overview tiles against their cards, the stub-guaranteed findings, no admin link. */
async function readDataQualityFindings(page: Page): Promise<void> {
  // The page is read for a team, never the whole unit: the spec's team.
  await openReport(page, "/reports/data-quality", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 2, name: "Data quality", exact: true })).toBeVisible();

  // The overview: what was checked, and the headline tiles.
  const populations = page.getByRole("group", { name: "What was checked" });
  await expect(populations.getByText("Done tasks", { exact: true })).toBeVisible();
  const tiles = page.getByRole("group", { name: "Headline counts, each linking to its card" });
  const tileValue = async (label: string): Promise<number> => {
    const tile = tiles.getByRole("link", { name: label, exact: true }).locator("xpath=..");
    return Number((await tile.locator("p").first().innerText()).trim());
  };

  // The stub guarantees work logged days late, and the team's members logged it. Authors on no team belong to no team, so a single team's view lists none;
  // work done outside any sprint is whatever the team's own work holds (the OPS Kanban board is not its board). Each card states the same count its tile does.
  const cards = page.getByRole("group", { name: "Findings" });
  const findings = [
    { tile: "Over 1 day late", card: "Late logging" },
    { tile: "Authors without a team", card: "Authors without a team" },
    { tile: "Work done outside any sprint", card: "Work done outside any sprint" },
  ];
  for (const { tile, card } of findings) {
    const count = await tileValue(tile);
    if (tile === "Over 1 day late") expect(count, `${tile} is found in the stub data`).toBeGreaterThan(0);
    const cardBox = cards.getByRole("group", { name: card, exact: true });
    await expect(cardBox.getByText(count > 0 ? `Found: ${count}` : "None found", { exact: true })).toBeVisible();
  }
  await expect(
    cards.getByRole("group", { name: "Authors without a team", exact: true }).getByText("This belongs to no team, so a single team's view lists none."),
  ).toBeVisible();

  // A tile leads to its card without touching the URL; focus lands in it.
  const before = page.url();
  await tiles.getByRole("link", { name: "Authors without a team", exact: true }).click();
  await expect(cards.getByRole("group", { name: "Authors without a team", exact: true })).toBeFocused();
  expect(page.url()).toBe(before);

  // The configuration findings name their connection, but only an administrator gets the link to fix it.
  await expect(cards.getByRole("group", { name: "Boards without a team", exact: true })).toContainText("Found:");
  await expect(page.getByRole("link", { name: /^Open the metrics configuration of / })).toHaveCount(0);
}

/** The Monday on or before a `YYYY-MM-DD` day (calendar arithmetic only, so the host's zone cannot move it) — a week bucket's key. */
function mondayOf(day: string): string {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7));
  return date.toISOString().slice(0, 10);
}

/** Makes the reader the team's manager (or, with `null`, clears it) through a fresh admin session — a PUT is a full replace. */
async function setTeamManager(managerUserId: number | null): Promise<void> {
  const { api: admin } = await apiAsAdmin();
  try {
    const updated = await admin.put(`/api/v1/teams/${teamId}`, {
      data: managerUserId === null ? { name: teamName } : { name: teamName, managerUserId },
    });
    expect(updated.status(), await updated.text()).toBe(204);
  } finally {
    await admin.dispose();
  }
}

/**
 * The cost-matrix journey, read for the roster team (the page is never read for the whole unit): the heat table of its authors, its totals, the
 * donuts and the epic table, the capacity column (the team's manager only), the week split and the table staying inside its own scroller on a phone.
 */
async function readCostMatrix(page: Page): Promise<void> {
  await openReport(page, "/reports/cost-matrix", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 2, name: "Cost matrix", exact: true })).toBeVisible();

  // The team's heat table: its authors in rows, the domains in columns, totals both ways; the roster person is a row.
  const matrix = page.getByRole("table", { name: /^Man-days logged in the period: the team's authors and members in rows/ });
  await expect(page.getByRole("heading", { level: 3, name: "Man-days by author and domain", exact: true })).toBeVisible();
  for (const column of ["Author", "Total", "Foreign work (MD)", "Foreign share"]) {
    await expect(matrix.getByRole("columnheader", { name: column, exact: true })).toBeVisible();
  }
  const personRow = matrix.getByRole("row", { name: /^Show Sample User \d+ / }).first();
  await expect(personRow).toBeVisible();
  // The cells follow the row header, so the Total cell is one before its column header's index.
  const totalCell = (await matrix.getByRole("columnheader").allInnerTexts()).indexOf("Total") - 1;
  const totalsRow = matrix.getByRole("row", { name: /^Total / });
  const cellValues = async (row: Locator): Promise<number[]> =>
    (await row.getByRole("cell").allInnerTexts()).map((text) => Number(text.replace("%", "")));
  const rowTotal = async (row: Locator) => (await cellValues(row))[totalCell];
  // The totals row repeats the tile; the rows add up to it (each figure is rounded once, so within 0.005 apiece).
  const grandTotal = await rowTotal(totalsRow);
  await expect(page.getByRole("group", { name: "Man-days logged", exact: true })).toContainText(String(grandTotal));
  const rowsSum = (await matrix.getByRole("row").filter({ hasNot: page.getByRole("columnheader") }).evaluateAll(
    (rows, column) => rows.map((row) => row.querySelectorAll("td")[column]?.textContent ?? "").map(Number),
    totalCell,
  ));
  const bodyTotals = rowsSum.slice(0, -1);
  expect(bodyTotals.reduce((sum, value) => sum + value, 0)).toBeCloseTo(grandTotal, 1);

  // The two donuts split the same worklogs: each legend table adds up to the total (each figure is rounded once, so within 0.005 a slice),
  // and a figure opens the tasks behind its slice, the drawer's bar being that figure. Every legend figure is ONE rounding of an exact sum (the folded
  // "Other" adds the folded exact sums first), so the sum differs from the total by at most 0.005 for each figure plus 0.005 for the total itself.
  for (const title of ["Man-days by activity type", "Man-days by work category"]) {
    const legend = page.getByRole("table", { name: title, exact: true });
    await expect(legend).toBeVisible();
    const figures = await legend.locator("tbody tr").evaluateAll((rows) => rows.map((row) => Number(row.querySelectorAll("td")[1]?.textContent)));
    expect(figures.length, `${title}: at least one slice`).toBeGreaterThan(0);
    expect(Math.abs(figures.reduce((sum, value) => sum + value, 0) - grandTotal), `${title} adds up to the total`).toBeLessThanOrEqual(0.005 * (figures.length + 1) + 1e-9);
  }
  const activity = page.getByRole("table", { name: "Man-days by activity type", exact: true });
  const largest = activity.getByRole("button", { name: /^[\d.]+ MD — show the tasks of / }).first();
  const largestMd = (await largest.innerText()).trim();
  await largest.click();
  const slice = page.getByRole("dialog", { name: / · Activity type$/ });
  await expect(slice.getByText(new RegExp(`^\\d+ items? · ${largestMd} MD$`))).toBeVisible();
  await expect(slice.getByRole("table", { name: "Items behind the bar", exact: true }).getByRole("columnheader", { name: "Authors", exact: true })).toBeVisible();
  await slice.getByRole("button", { name: "Close", exact: true }).click();
  await expect(slice).toHaveCount(0);

  // The epic table splits the same worklogs by epic: a column per author, a row per epic with its Jira key, and
  // a footer whose Total is the matrix's. A row's Total figure opens the tasks behind it; Escape closes the drawer.
  const epics = page.getByRole("table", { name: "Man-days by epic and author", exact: true });
  await expect(epics).toBeVisible();
  await expect(epics.getByRole("columnheader", { name: "Total", exact: true })).toBeVisible();
  await expect(epics.getByRole("link", { name: / \(opens in Jira\)$/ }).first()).toBeVisible();
  expect(Number((await epics.locator("tfoot td").last().innerText()).trim()), "the epic table's footer is the matrix's grand total").toBeCloseTo(grandTotal, 1);
  const epicTotal = epics.locator("tbody tr").first().getByRole("button", { name: /^[\d.]+ MD — total: show the tasks of / });
  await epicTotal.click();
  const epicDrill = page.getByRole("dialog");
  await expect(epicDrill.getByRole("table", { name: "Items behind the bar", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(epicDrill).toHaveCount(0);

  // The capacity column is the team's manager's (and an administrator's), never a plain reader's.
  const capacityHeader = matrix.getByRole("columnheader", { name: "Capacity (MD)", exact: true });
  await expect(capacityHeader).toHaveCount(0);
  if (userId === undefined) throw new Error("the reader was not created");
  try {
    await setTeamManager(userId);
    await page.reload();
    await expect(capacityHeader).toBeVisible();
    const headers = await matrix.getByRole("columnheader").allInnerTexts();
    const capacity = Number((await personRow.getByRole("cell").nth(headers.indexOf("Capacity (MD)") - 1).innerText()).trim());
    expect(capacity, "the roster person has a capacity over the window").toBeGreaterThan(0);

    // The week split: a group per week by its Monday, the period's figures last.
    await openFilters(page);
    await page.getByRole("radiogroup", { name: "Split" }).getByText("Week", { exact: true }).click();
    await expect(page).toHaveURL(/split=WEEK/);
    await expect(matrix.getByRole("columnheader", { name: mondayOf(dayOffset(-184)), exact: true })).toBeVisible();
    await expect(matrix.getByRole("columnheader", { name: "Period", exact: true })).toBeVisible();

    // On a phone the page itself never scrolls sideways; the table's own region does.
    await page.setViewportSize({ width: 390, height: 844 });
    const region = page.getByRole("region", { name: /^Man-days logged in the period/ });
    await expect(region).toBeVisible();
    // Polled: the shell animates its nav padding away when the viewport drops below the breakpoint, and for ~200 ms the
    // page is wider than the window while the content is still shifted right by it. Only a SETTLED overflow is a bug.
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), { message: "no page-level horizontal scroll" })
      .toBeLessThanOrEqual(0);
    expect(await region.evaluate((el) => el.scrollWidth > el.clientWidth), "the wide table scrolls inside its region").toBe(true);
  } finally {
    await setTeamManager(null);
  }
}

/**
 * The epic-accuracy journey: empty default window, both views (each a median/mean/items strip with no p90/p95 and an "Accuracy
 * measures" table, or the counts-only notice), the golden epic's ratio, the domain, then the required team (no "All teams") and no
 * Member control. Every deep link carries the spec's OWN team: the page is read per team, and a bare link would start on the first
 * team by name, which other specs' teams can precede.
 */
async function readEpicAccuracy(page: Page): Promise<void> {
  const teamLink = `&teamId=${teamId}`;
  // The default window holds no finished epic: the empty state, not a block of zeros.
  await openReport(page, "/reports/epic-estimation-accuracy", teamLink);
  await expect(page.getByRole("heading", { level: 2, name: "Epic estimation accuracy", exact: true })).toBeVisible();
  await expect(page.getByText("No data in this period")).toBeVisible();
  const epics = page.getByRole("table", { name: "Finished epics", exact: true });
  await expect(epics).toHaveCount(0);

  // The year-long window holds the stub's finished epics: both views, each a distribution with its accounting.
  await page.goto(`/reports/epic-estimation-accuracy?connectionId=${dataSourceId}&${EPIC_WINDOW}${teamLink}`);
  await expect(page.getByRole("heading", { name: "Against the own estimate at start", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Against the own estimate at done", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();
  // The team's few epics may sit under the minimum sample: a view is then the counts-only notice, with neither strip nor measures.
  await expect(percentilesOrNotice(page).first()).toBeVisible();
  await expectCentreStripAndMeasures(page);

  // One row per finished epic, as many as the accounting's population.
  const golden = epics.getByRole("row", { name: new RegExp(`^${GOLDEN_EPIC.issueKey}\\b`) });
  await expect(golden).toBeVisible();
  expect((await epics.getByRole("row").count()) - 1, "a row per finished epic").toBe(await finishedInPeriod(page));

  // The golden epic: the ratio is actual ÷ its OWN estimate — never the sum of its children's.
  const cells = golden.getByRole("cell");
  await expect(cells.nth(1)).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  await expect(cells.nth(2)).toHaveText(md(GOLDEN_EPIC.budgetMd));
  await expect(cells.nth(3)).toHaveText(md(GOLDEN_EPIC.budgetMd));
  await expect(cells.nth(4)).toHaveText(md(GOLDEN_EPIC.childSumMd));
  const actual = Number(await cells.nth(5).innerText());
  const ratio = Number(await cells.nth(6).innerText());
  expect(actual).toBeGreaterThan(0);
  expect(Math.abs(ratio - actual / GOLDEN_EPIC.budgetMd), "ratio = actual ÷ own estimate").toBeLessThan(0.006);
  expect(Math.abs(ratio - actual / GOLDEN_EPIC.childSumMd), "not actual ÷ child sum").toBeGreaterThan(0.05);
  // The read is the picked team's: no other team's epics.
  await expect(epics.getByRole("row", { name: /^(GTM|OPS|PLT)-\d+/ })).toHaveCount(0);

  // The domain narrows the epics: the seeded team owns no GTM epic, so GTM leaves nothing; clearing it brings the golden FLO epic back.
  await openFilters(page);
  await pickFilter(page, "Domain", "GTM");
  await expect(page).toHaveURL(/domain=GTM/);
  await expect(page.getByText("No data in this period")).toBeVisible();
  await expect(epics).toHaveCount(0);
  await clearFilter(page, "Domain");
  await expect(golden).toBeVisible();

  // A team is required and there is no Member control: epics belong to a team, not a person.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("combobox", { name: "Member", exact: true })).toHaveCount(0);
}

/**
 * The estimate-adjustments journey: the tasks and epics blocks of the spec's own team (the page is always read for one team, so a
 * bare link would start on the first team by name, which other specs' teams can precede), the activity-type filter, the required
 * team (no "All teams") and a one-person pick.
 */
async function readEstimateAdjustments(page: Page): Promise<void> {
  await openReport(page, "/reports/estimate-adjustments", `&teamId=${teamId}`, EPIC_WINDOW);
  await expect(page.getByRole("heading", { level: 2, name: "Estimate adjustments", exact: true })).toBeVisible();
  await expect(page.getByText("The domain view applies to tasks; epics always read their own space.")).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // Tasks and epics each read started / changed after start / estimated late / share changed. The seeded team is small, so the share
  // may be withheld behind the minimum-sample notice: only the relations between the counts are guaranteed.
  const tasks = page.getByRole("group", { name: "Tasks", exact: true });
  const epics = page.getByRole("group", { name: "Epics", exact: true });
  for (const block of [tasks, epics]) {
    const started = Number(await blockFigure(block, "Started"));
    const changed = Number(await blockFigure(block, "Changed after start"));
    const late = Number(await blockFigure(block, "Estimated late"));
    expect(changed).toBeLessThanOrEqual(started);
    expect(late, "estimated late is counted within changed after start").toBeLessThanOrEqual(changed);
  }
  // Each kind's change distribution (start → done) is a percentile strip or the counts-only notice, with its accounting.
  await expect(page.getByRole("heading", { name: "Change from start to done", exact: true })).toHaveCount(2);
  await expect(percentilesOrNotice(page).first()).toBeVisible();
  // The histogram charts only the items whose estimate changed: each block names the unchanged ones beside it (they stay in the accounting).
  // (a block with no unchanged item, or with nothing but unchanged ones, shows no such line: at most one per block.)
  expect(await page.getByText(/^Not charted: \d+ finished items? with an unchanged estimate \(a 0% change\)\.$/).count()).toBeLessThanOrEqual(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();

  // The activity type narrows the TASKS (epics carry none): the filter lands in the URL, and clearing it restores the figure.
  await openFilters(page);
  const allTasks = Number(await blockFigure(tasks, "Started"));
  // The slice round-trip is proven, not vacuous: the request carries the filter, and the narrowed figure is at most the unfiltered one.
  const bugResponse = page.waitForResponse((r) => {
    const url = new URL(r.url());
    return r.request().method() === "GET" && url.pathname === "/api/v1/reports/estimate-adjustments" && url.searchParams.get("activityType") === "Bug" && r.ok();
  });
  await pickFilter(page, "Activity type", "Bug");
  await bugResponse;
  await expect(page).toHaveURL(/activityType=Bug/);
  await expect.poll(async () => Number(await blockFigure(tasks, "Started"))).toBeLessThanOrEqual(allTasks);
  await clearFilter(page, "Activity type");
  await expect(page).not.toHaveURL(/activityType=/);
  await expect.poll(async () => Number(await blockFigure(tasks, "Started"))).toBe(allTasks);

  // A team is required: no "All teams", no clear button.
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and keeps the team; "Totals per person" replaces the by-member table with one row.
  // (The stub person may have too few changes for a histogram, so the "1." numbering is optional here; the stacking itself is the
  // page's unit tests'.) A single person has no epic figures.
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  await expect(page.getByRole("note").filter({ hasText: "Epics aren't attributed to individual people" })).toBeVisible();
  await expect(epics).toHaveCount(0);
  await expect(tasks).toBeVisible();
}

/** Sprint consistency, on the golden sprint: one team and no Member control, then sliced by Activity type (live figures, no drift) and back. */
async function sliceSprintConsistency(page: Page): Promise<void> {
  const cells = goldenRow(page).getByRole("cell");
  // The page always reads ONE team (the tab switch carried it) and every member of it: no Member control.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await openFilters(page);
  await expect(page.getByRole("combobox", { name: "Team", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Member", exact: true })).toHaveCount(0);

  // Sliced by activity type the figures are live and smaller, with no frozen snapshot (so no drift badge) and the page says so.
  await pickFilter(page, "Activity type", "Bug");
  await expect(page).toHaveURL(/activityType=Bug/);
  await expect(page.getByText(/^Sliced by activity type or work category/)).toBeVisible();
  await expect(cells.nth(3)).not.toHaveText(mdItems(GOLDEN.committedMd, GOLDEN.committedItems));
  await expect(page.getByText("Drift", { exact: true })).toHaveCount(0);
  // Cleared, the golden figures are back.
  await clearFilter(page, "Activity type");
  await expect(page).not.toHaveURL(/activityType/);
  await expect(page.getByText(/^Sliced by activity type or work category/)).toHaveCount(0);
  await expect(cells.nth(3)).toHaveText(mdItems(GOLDEN.committedMd, GOLDEN.committedItems));
}

/**
 * The cycle-time journey: the golden sprint's two views and trend, the epics beside them at team level, a required team (no
 * "All teams") and a one-person pick.
 */
async function readCycleTime(page: Page): Promise<void> {
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
  // Cycle time is read for one team: the picked team stays in the URL.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // The epics beside the tasks: the stub's epics all finished before the sprint-narrowed window, so a year-long
  // window reads them — two views of their own, each with its own accounting, next to the tasks' two.
  await page.goto(`/reports/cycle-time?connectionId=${dataSourceId}&${EPIC_WINDOW}&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 3, name: "Epics", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Epics: working days", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Epics: elapsed days", exact: true })).toBeVisible();
  await expectAccountingToReconcile(page, 4);
  // At team level the epics card stays (the team's own epics) and there is no owner table: epics split by owner team, never by person.
  await expect(page.getByRole("table", { name: "Epics by owner team", exact: true })).toHaveCount(0);

  // A team is required: no "All teams", no clear button.
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and "Totals per person" replaces the by-member table with one row. (The stub person may
  // have too few finished tasks for a histogram, so the "1." numbering is optional here; the stacking itself is the page's unit tests'.)
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  // The epics are not per person: one note replaces the epics' histograms.
  await expect(page.getByRole("heading", { name: "Epics: working days", exact: true })).toHaveCount(0);
}

/**
 * The task-accuracy journey: the golden sprint's two views, each either a distribution (a median/mean/items strip with no p90 or
 * p95, and an "Accuracy measures" table) or the counts-only notice; a required team (no "All teams") and a one-person pick.
 */
async function readTaskAccuracy(page: Page): Promise<void> {
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
  await expectCentreStripAndMeasures(page);
  // Task accuracy is read for one team: the picked team stays in the URL.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // A team is required: no "All teams", no clear button.
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and "Totals per person" lists them in one row. (The stub person may have too few
  // finished tasks for a histogram, so the "1." numbering is not asserted here; the two-person stacking is the page's unit tests'.)
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  await expectCentreStripAndMeasures(page);
}

/**
 * Every shown view has the median/mean/items strip — never a p90 or p95 — and its own nine-row "Accuracy measures" table; a view
 * below the minimum sample has neither and the counts-only notice ("Only N items in this selection") stands instead. The page has
 * two views (at start, at done), so strips + notices must account for both — unless the selection holds nothing at all, when the
 * page shows its one "No data in this period" state instead of either view. The check never passes on a page that shows none of these.
 */
async function expectCentreStripAndMeasures(page: Page): Promise<void> {
  const strips = page.getByRole("group", { name: "Percentiles", exact: true });
  const measures = page.getByRole("table", { name: /^Accuracy measures — / });
  const notices = page.getByRole("note").filter({ hasText: /^(Only \d+ items? in this selection|Nothing to measure in this selection)/ });
  const empty = page.getByText("No data in this period", { exact: true });
  await expect
    .poll(async () => (await empty.count()) > 0 || (await strips.count()) + (await notices.count()) === 2)
    .toBe(true);
  // Every shown view has exactly one measures table beside its strip. A polled difference (never a one-off count snapshot, which a
  // stale previous report could supply) settles on 0 once the page shows one coherent report: both counts are 0 (the notice) or equal.
  await expect.poll(async () => (await measures.count()) - (await strips.count())).toBe(0);
  await expect(page.getByText("p90", { exact: true })).toHaveCount(0);
  await expect(page.getByText("p95", { exact: true })).toHaveCount(0);
  for (let i = 0; i < (await strips.count()); i++) {
    await expect(strips.nth(i).getByText("Median (p50)", { exact: true })).toBeVisible();
    await expect(strips.nth(i).getByText("Mean", { exact: true })).toBeVisible();
    await expect(strips.nth(i).getByText("Items", { exact: true })).toBeVisible();
    await expect(measures.nth(i).getByRole("row")).toHaveCount(10);
  }
}

/**
 * The task-estimates journey: the Estimation tab hop, the golden sprint's two histograms (estimate at done first, then at start), each
 * with its own accounting and either the FULL percentile strip or the counts-only notice, the long-tail control, a required team (no
 * "All teams") and a one-person pick.
 */
async function readTaskEstimates(page: Page): Promise<void> {
  // Reached through the Estimation group's tabs: it opens on Task estimation accuracy, the last tab is Task estimates.
  await openGroup(page, "Estimation");
  await page.getByRole("tab", { name: "Task estimates", exact: true }).click();
  // The tab hop keeps the picked team (Task estimates is read for one team).
  await expect(page).toHaveURL(new RegExp(`/reports/task-estimates\\?.*teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 2, name: "Task estimates", exact: true })).toBeVisible();
  await pickGoldenSprint(page);

  // Two histograms — at done (primary, first) and at start — each with its own "left out" accounting, which adds up.
  const headings = page.getByRole("heading");
  await expect(page.getByRole("heading", { name: "Estimate at done", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Estimate at start", exact: true })).toBeVisible();
  const titles = await headings.allInnerTexts();
  expect(titles.indexOf("Estimate at done"), "at done comes first").toBeLessThan(titles.indexOf("Estimate at start"));
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expect(page.getByText(/^Of \d+ finished in this period:$/).first()).toBeVisible();
  // Each accounting line is "<count> <reason>" in one list item.
  for (const reason of ["No estimate at done", "Never started, so no estimate at start", "No estimate at start (including estimated late)"]) {
    await expect(page.getByRole("listitem").filter({ hasText: new RegExp(`^\\d+ ${reason.replace(/[()]/g, "\\$&")}$`) })).toBeVisible();
  }
  await expectAccountingToReconcile(page, 2);
  await expectFullStrips(page);
  // Task estimates is read for one team: the picked team stays in the URL.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // A team is required: no "All teams", no clear button.
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and "Totals per person" lists them in one row. (The stub person may have too few
  // finished tasks for a histogram, so the "1." numbering is not asserted here; the two-person stacking is the page's unit tests'.)
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  await expectFullStrips(page);

  await switchTaskEstimatesTail(page);
}

/**
 * Every shown histogram has the FULL strip — median, p90, p95, mean and items — and no "Accuracy measures" table; one below the
 * minimum sample has the counts-only notice instead. The two histograms must be accounted for — unless the selection holds nothing at
 * all, when the page shows its one "No data in this period" state. The check never passes on a page that shows none of these.
 */
async function expectFullStrips(page: Page): Promise<void> {
  const strips = page.getByRole("group", { name: "Percentiles", exact: true });
  const notices = page.getByRole("note").filter({ hasText: /^(Only \d+ items? in this selection|Nothing to measure in this selection)/ });
  const empty = page.getByText("No data in this period", { exact: true });
  await expect
    .poll(async () => (await empty.count()) > 0 || (await strips.count()) + (await notices.count()) === 2)
    .toBe(true);
  await expect(page.getByRole("table", { name: /^Accuracy measures — / })).toHaveCount(0);
  for (let i = 0; i < (await strips.count()); i++) {
    for (const label of ["Median (p50)", "p90", "p95", "Mean", "Items"]) {
      await expect(strips.nth(i).getByText(label, { exact: true })).toBeVisible();
    }
  }
}

/**
 * The long-tail control on the task-estimates histograms, over a year-long window (the golden sprint's team is too small for a
 * histogram there): 5% by default, 25%, 0, and back, the choice in the URL. Estimates are discrete, so — unlike cycle time's
 * continuous days — ties at the top may swallow the open "> X" row; the row is asserted absent at 0 and well-formed wherever it shows.
 */
async function switchTaskEstimatesTail(page: Page): Promise<void> {
  await page.goto(`/reports/task-estimates?connectionId=${dataSourceId}&${EPIC_WINDOW}&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 2, name: "Task estimates", exact: true })).toBeVisible();
  const table = page.getByRole("table", { name: "Histogram, as a table — Estimate at done", exact: true });
  await expect(table, "the team finished enough estimated tasks in the year for a histogram").toBeVisible();
  // Fixed-width bars are named by their upper bound, so the first column says "Up to (MD)" where the other reports say "Range".
  await expect(table.getByRole("columnheader").first()).toHaveText("Up to (MD)");
  const lastRange = table.getByRole("row").last().getByRole("cell").first();
  // The bars are 0.5 MD wide and labelled by their upper bound (a plain value); the open tail reads "> X (long tail)": the estimates
  // strictly above X (a task at exactly X sits on the "X" bar).
  const wellFormed = /^(\d+(\.\d+)?|> \S+ \(long tail\))$/;

  const tail = page.getByRole("combobox", { name: "Long tail", exact: true });
  await expect(tail).toHaveValue("5%");
  expect(new URL(page.url()).searchParams.has("tailPercent")).toBe(false);
  await expect(lastRange).toHaveText(wellFormed);

  await tail.click();
  await page.getByRole("option", { name: "25%", exact: true }).click();
  await expect(page).toHaveURL(/tailPercent=25/);
  await expect(tail).toHaveValue("25%");
  await expect(lastRange).toHaveText(wellFormed);

  // 0 restores the plain histogram: no open bucket at all.
  await tail.click();
  await page.getByRole("option", { name: "0%", exact: true }).click();
  await expect(page).toHaveURL(/tailPercent=0/);
  await expect(lastRange).toHaveText(/^\d+(\.\d+)?$/);

  // Back to the default 5%: the param leaves the URL.
  await tail.click();
  await page.getByRole("option", { name: "5%", exact: true }).click();
  await expect.poll(() => new URL(page.url()).searchParams.has("tailPercent")).toBe(false);
  await expect(lastRange).toHaveText(wellFormed);
}

/** The cycle-time long-tail journey: the default 5% open bucket, widened to 25%, switched off with 0, and back to the default. */
async function widenCycleTimeTail(page: Page): Promise<void> {
  // Cycle time is read per team, so the link carries this spec's OWN team (a bare link would start on the first team by name,
  // which other specs' teams can precede).
  await openReport(page, "/reports/cycle-time", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { name: "Elapsed days", exact: true })).toBeVisible();
  // Elapsed days are continuous (milliseconds), so the top values are never tied: the tail bucket is certain to exist at any share above 0.
  const lastRange = page
    .getByRole("table", { name: "Histogram, as a table — Elapsed days", exact: true })
    .getByRole("row")
    .last()
    .getByRole("cell")
    .first();

  // The default 5%: not in the URL, yet the last bucket is already the open "≥ X" tail, marked in words.
  const tail = page.getByRole("combobox", { name: "Long tail", exact: true });
  await expect(tail).toHaveValue("5%");
  expect(new URL(page.url()).searchParams.has("tailPercent")).toBe(false);
  await expect(lastRange).toHaveText(/^≥ \S+ \(long tail\)$/);
  const narrow = await lastRange.innerText();

  // Widening to 25% lowers the tail's start and puts the choice in the URL.
  await tail.click();
  await page.getByRole("option", { name: "25%", exact: true }).click();
  await expect(page).toHaveURL(/tailPercent=25/);
  await expect(tail).toHaveValue("25%");
  await expect(lastRange).toHaveText(/^≥ \S+ \(long tail\)$/);
  await expect(lastRange).not.toHaveText(narrow);

  // 0 restores the plain histogram: no open bucket at all.
  await tail.click();
  await page.getByRole("option", { name: "0%", exact: true }).click();
  await expect(page).toHaveURL(/tailPercent=0/);
  await expect(lastRange).not.toHaveText(/^≥/);

  // Back to the default 5%: the param leaves the URL.
  await tail.click();
  await page.getByRole("option", { name: "5%", exact: true }).click();
  await expect.poll(() => new URL(page.url()).searchParams.has("tailPercent")).toBe(false);
  await expect(lastRange).toHaveText(/^≥ /);
}

/**
 * Printing and the way back up, on a tall report (Cycle time): "Print / save as PDF" calls the browser's print (stubbed here, a
 * headless browser has no dialog); under print media the chrome and the filter controls are gone and the report's own header names
 * it, its filter and the generation time; further down the page the floating "Back to top" button appears and takes the user back,
 * with focus on the page heading.
 */
async function printReportAndScrollToTop(page: Page): Promise<void> {
  await page.addInitScript(() => {
    (window as unknown as { printCalls: number }).printCalls = 0;
    window.print = () => {
      (window as unknown as { printCalls: number }).printCalls += 1;
    };
  });
  await openReport(page, "/reports/cycle-time", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { name: "Elapsed days", exact: true })).toBeVisible();
  const printCalls = () => page.evaluate(() => (window as unknown as { printCalls: number }).printCalls);

  // On screen the print header is not there to see, and the button is.
  const printHeader = page.getByText(/^Flow · Cycle time$/);
  await expect(printHeader).toHaveCount(1);
  await expect(printHeader).toBeHidden();
  await page.getByRole("button", { name: "Print / save as PDF" }).click();
  expect(await printCalls()).toBe(1);

  // On paper: no navigation, no filter toggle, no print button — and a header with the report, its filter and the time.
  // Hidden, not absent: each is in the page on screen (a count of 1), so only the print rules can have taken it off the paper.
  const printedChrome = [
    page.getByRole("link", { name: "Teams", exact: true }),
    page.getByRole("button", { name: /^Filters/ }),
    page.getByRole("button", { name: "Print / save as PDF" }),
  ];
  for (const chrome of printedChrome) await expect(chrome).toHaveCount(1);
  await page.emulateMedia({ media: "print" });
  await expect(printHeader).toBeVisible();
  await expect(page.getByText(/^Filters: Period: .*Team: e2e-reports-team-/)).toBeVisible();
  await expect(page.getByText(/^Generated \d{4}-\d{2}-\d{2} \d{2}:\d{2} \(.+\)$/)).toBeVisible();
  for (const chrome of printedChrome) await expect(chrome).toBeHidden();
  await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();
  await page.emulateMedia({ media: "screen" });

  // At the top there is no way-back button; scrolled well down it appears, and one click returns, focusing the heading.
  const back = page.getByRole("button", { name: "Back to top", exact: true });
  await expect(back).toBeHidden();
  await page.keyboard.press("End");
  await expect(back).toBeVisible();
  await back.click();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeFocused();
  await expect(back).toBeHidden();
}

/**
 * The reported-time journey: the Estimation tab hop, both distributions, the domain and domain-view controls, a required team
 * (no "All teams") and a one-person pick (the spec's team has ONE stub person, so the two-person stacking is vitest's).
 */
async function readReportedTime(page: Page): Promise<void> {
  // Reached through the Estimation tabs: the route is `reported-time-ratio`, the tab "Reported time". The link carries the spec's OWN
  // team: reported time is read per team and a bare link would start on the first team by name, which other specs' teams can precede.
  await openReport(page, "/reports/epic-estimation-accuracy", `&teamId=${teamId}`);
  await page.getByRole("tab", { name: "Reported time", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/reports/reported-time-ratio\\?.*connectionId=${dataSourceId}`));
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 2, name: "Reported time", exact: true })).toBeVisible();

  // Two measures, two distributions, each with its OWN accounting (the partitions differ).
  await expect(page.getByRole("heading", { name: "Reported time ÷ cycle time", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Flow efficiency", exact: true })).toBeVisible();
  await expect(page.getByText("Median (p50)", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByText(/^Very short cycles give very large ratios/)).toBeVisible();
  await expect(page.getByRole("table", { name: /^Histogram, as a table — Reported time ÷ cycle time$/ })).toBeVisible();

  // The domain narrows what finished (the team's work is mostly FLO, so it may not shrink): the filter is in the URL.
  const all = await finishedInPeriod(page);
  await openFilters(page);
  await pickFilter(page, "Domain", "FLO");
  await expect(page).toHaveURL(/domain=FLO/);
  await expect.poll(() => finishedInPeriod(page)).toBeLessThanOrEqual(all);
  await clearFilter(page, "Domain");
  await expect.poll(() => finishedInPeriod(page)).toBe(all);

  // The domain view (delivered in / earned in) is a report-specific control that travels in the URL.
  await page.getByRole("radiogroup", { name: "Domain view" }).getByText("Earned in", { exact: true }).click();
  await expect(page).toHaveURL(/domainView=EPIC/);
  await expect(page.getByRole("heading", { name: "Flow efficiency", exact: true })).toBeVisible();

  // A team is required: the Team select shows it, cannot be cleared and offers no "All teams"; the team's members are the drill table.
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();

  // One person picked: the URL gains the account, Totals per person has one row, and the flow-efficiency histogram carries a
  // "1. <name>" column. A small stub sample (below minSampleSize) hides the distribution — then the min-sample note stands in
  // for the histogram and the Totals row shows the bare name — so those two assertions accept both outcomes.
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toHaveText(new RegExp(`^(1\\. )?${memberName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`));
  const personColumn = page
    .getByRole("table", { name: "Histogram, as a table — Flow efficiency", exact: true })
    .getByRole("columnheader", { name: `1. ${memberName}`, exact: true });
  const minSampleNote = page.getByRole("note").filter({ hasText: /^Only \d+ items? in this selection/ });
  await expect(personColumn.or(minSampleNote).first()).toBeVisible();
  expect(await finishedInPeriod(page), "one person's finished work is part of the team's").toBeLessThan(all);
}

/**
 * The estimated-backlog journey: a team's tiles and trend, a team that cannot be cleared (the page is never read for the whole unit), a domain's
 * empty pace, team replacing domain, no Member control (a pasted member is dropped), the staleness slider (No limit, then 7 days: shown plus hidden is the
 * unlimited count).
 */
async function readEstimatedBacklog(page: Page): Promise<void> {
  // The team's backlog: its board is mapped, so the pace of its closed sprints turns man-days into sprints. Read with no
  // staleness limit: the stub's history is dated, so the default 90-day window can hide every item on today's derive.
  await openReport(page, "/reports/backlog", `&teamId=${teamId}&touchedWithinDays=0`);
  await expect(page.getByRole("heading", { level: 2, name: "Estimated backlog", exact: true })).toBeVisible();
  // The card is "as of" the newest derived day inside the window — never later than the window's end.
  const asOf = /^Backlog on (\d{4}-\d{2}-\d{2})$/.exec(await page.getByRole("heading", { level: 3, name: /^Backlog on / }).innerText())?.[1] ?? "";
  expect(asOf, "the as-of day").toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(asOf <= dayOffset(7), "as-of inside the window").toBe(true);
  const tile = (label: string) => page.getByRole("group", { name: label, exact: true });
  const teamMd = (await tile("Backlog (MD)").locator("p").nth(1).innerText()).trim();
  const teamItems = (await tile("Items").locator("p").nth(1).innerText()).trim();
  expect(Number(teamMd)).toBeGreaterThan(0);
  expect(teamItems).toMatch(/^[1-9]\d*$/);
  await expect(tile("Backlog in sprints")).toContainText(/≈ [\d.]+ sprints? ahead/);
  await expect(tile("Backlog in sprints")).toContainText(/Recent pace: [\d.]+ MD delivered per sprint, the mean of \d+ closed sprints?/);

  // The trend: the chart, and its text alternative — the newest day first, equal to the tiles' as-of day and figures.
  await expect(page.getByRole("group", { name: "Chart: estimated backlog in man-days by day" })).toBeVisible();
  await page.getByRole("button", { name: "Show daily figures" }).click();
  const daily = page.getByRole("table", { name: "Backlog by day, as a table" });
  const newest = daily.getByRole("row").nth(1).getByRole("cell");
  await expect(newest.nth(0)).toHaveText(asOf);
  await expect(newest.nth(1)).toHaveText(teamMd);
  await expect(newest.nth(2)).toHaveText(teamItems);
  expect(await daily.getByRole("row").count(), "header + one row per day of the window").toBeGreaterThan(100);

  // The page is never read for the whole unit: the Team select has no clear button and no "All teams" option.
  await openFilters(page);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await page.getByRole("combobox", { name: "Team", exact: true }).click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // A domain has no velocity of its own: the sprints tile says why it is empty instead of inventing a pace.
  await pickFilter(page, "Domain", "FLO");
  await expect(page).toHaveURL(/domain=FLO/);
  await expect(tile("Backlog in sprints")).toContainText("this selection has no velocity of its own");
  await expect(tile("Backlog in sprints").locator("p").nth(1)).toHaveText("—");

  // There is no team × domain split: picking a team replaces the domain.
  await pickFilter(page, "Team", teamName);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page).not.toHaveURL(/domain=/);

  // The backlog is a team's: there is no Member control, and a member pasted into the link is read without it and dropped from the URL.
  await page.goto(`/reports/backlog?connectionId=${dataSourceId}&${WINDOW}&teamId=${teamId}&accountId=nobody`);
  await expect(page.getByRole("heading", { level: 2, name: "Estimated backlog", exact: true })).toBeVisible();
  await expect(page).not.toHaveURL(/accountId=/);
  await openFilters(page);
  await expect(page.getByRole("combobox", { name: "Team", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Member", exact: true })).toHaveCount(0);

  // The staleness slider starts on its default (90 days, the URL stays bare). "No limit" is the unfiltered backlog and says nothing
  // about hidden items; 7 days leaves some out, and what it shows plus what it hides is exactly the unlimited count.
  const slider = page.getByRole("slider", { name: "Last change within", exact: true });
  await expect(slider).toHaveAttribute("aria-valuetext", "90 days (default)");
  await expect(page).not.toHaveURL(/touchedWithinDays=/);
  await slider.focus();
  await page.keyboard.press("End");
  await expect(page).toHaveURL(/touchedWithinDays=0(&|$)/);
  await expect(slider).toHaveAttribute("aria-valuetext", "No limit");
  await expect(tile("Items").getByText(/hidden/)).toHaveCount(0);
  const itemsOff = Number((await tile("Items").locator("p").nth(1).innerText()).trim());
  expect(itemsOff, "the unlimited backlog").toBeGreaterThan(0);
  await page.keyboard.press("Home");
  await expect(page).toHaveURL(/touchedWithinDays=7(&|$)/);
  await expect(slider).toHaveAttribute("aria-valuetext", "7 days");
  // The hint names the window ("N items (X MD) hidden: no change in the last 7 days." or "Nothing hidden: … within the last 7 days.").
  const hint = tile("Items").getByText(/the last 7 days\./);
  await expect(hint).toBeVisible();
  const hidden = Number(/^(\d+) items? \(/.exec(await hint.innerText())?.[1] ?? "0");
  const items7 = Number((await tile("Items").locator("p").nth(1).innerText()).trim());
  expect(items7, "seven days never shows more than no limit").toBeLessThanOrEqual(itemsOff);
  expect(items7 + hidden, "shown plus hidden is the unlimited count").toBe(itemsOff);

  // The trend and the tiles agree at 7 days: the daily table's newest row is the tiles' as-of day.
  await page.getByRole("button", { name: "Show daily figures" }).click();
  const newest7 = page.getByRole("table", { name: "Backlog by day, as a table" }).getByRole("row").nth(1).getByRole("cell");
  await expect(newest7.nth(1)).toHaveText((await tile("Backlog (MD)").locator("p").nth(1).innerText()).trim());
  await expect(newest7.nth(2)).toHaveText(String(items7));
}

/**
 * Pick `option` in the Deep dive selection panel's searchable dropdown called `label` (a multiple choice stays open, so the list is
 * closed after). On a stack with several connections the options name theirs (` · <connection>`) and one sprint id or epic key can
 * be listed once per connection: of several matches the one naming `preferring` is picked, else the first.
 */
async function pickInPanel(page: Page, label: string, option: string | RegExp, preferring?: string): Promise<void> {
  const select = page.getByRole("combobox", { name: label, exact: true });
  await select.click();
  const matches = page.getByRole("option", { name: option, exact: typeof option === "string" });
  await matches.first().waitFor();
  const own = preferring === undefined ? matches : matches.filter({ hasText: preferring });
  await ((await own.count()) > 0 ? own : matches).first().click();
  await page.keyboard.press("Escape");
}

/** One computed style property of the first element `locator` finds — what the stylesheet really resolved to in this browser. */
async function computed(locator: Locator, property: string): Promise<string> {
  return locator.first().evaluate((element, name) => getComputedStyle(element).getPropertyValue(name), property);
}

/** A computed length in px, so "non-zero" is a number comparison rather than a string guess. */
async function computedPx(locator: Locator, property: string): Promise<number> {
  return Number.parseFloat(await computed(locator, property));
}

/** The Deep dive journey: pick the golden sprint's domain and sprint, drill an epic, a month and a cell, switch a layer, read the Burn-up tab, then an epic's window. */
async function diveIntoGoldenSprint(page: Page): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.getByRole("link", { name: "Deep dive", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Deep dive", exact: true })).toBeVisible();
  // Nothing is requested before a selection: the explainer of the two ways in stands in.
  await expect(page.getByRole("heading", { level: 3, name: "Pick something to dive into", exact: true })).toBeVisible();

  // Sprints of a domain: the golden sprint's domain, then the golden sprint (labelled with its dates; there is no Connection picker — the
  // connection follows from the pick, and where the stack holds several the options name theirs, so this spec's own is preferred).
  await expect(page.getByRole("combobox", { name: "Domain", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Connection", exact: true })).toHaveCount(0);
  await pickFilter(page, "Domain", "FLO");
  await pickInPanel(page, "Sprints", new RegExp(`^${GOLDEN.name} · \\d{4}-\\d{2}-\\d{2} → \\d{4}-\\d{2}-\\d{2}`), dataSourceName);
  await page.getByRole("button", { name: "Show", exact: true }).click();
  // The URL is the selection, in its canonical order.
  await expect(page).toHaveURL(new RegExp(`/reports/deep-dive\\?domain=FLO&sprintId=${GOLDEN.sprintId}(&connectionId=${dataSourceId})?$`));
  // Once shown, the Selection folds to a one-line summary and an "Edit selection" button; the form is gone until it is opened.
  const editSelection = page.getByRole("button", { name: "Edit selection", exact: true });
  await expect(editSelection).toHaveAttribute("aria-expanded", "false");
  await expect(editSelection).toBeFocused();
  // `visible: true`: the print-only report header (hidden on screen) spells the same filter out in words.
  await expect(page.getByText(new RegExp(`^.+ · Sprints: ${GOLDEN.name}( · |$)`)).filter({ visible: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Show", exact: true })).toHaveCount(0);

  // The matrix: all three layers drawn, the legend naming each, a done marker, and the golden epic's neighbours as rows.
  const grid = page.getByRole("grid", { name: "Plan, earned value and cost by epic and time" });
  await expect(grid).toBeVisible();
  const layers = page.getByRole("group", { name: "Layers shown", exact: true });
  for (const layer of ["Plan (PV)", "Approximate EV", "Cost (AC)"]) await expect(layers.getByRole("switch", { name: layer, exact: true })).toBeChecked();
  for (const layer of ["pv", "aev", "cost", "done"]) await expect(page.locator(`[data-layer="${layer}"]`).first()).toBeVisible();
  await expect(page.getByRole("group", { name: "Legend", exact: true })).toBeVisible();

  // An epic opens into its tasks: the toggle says so, and the tasks are rows under it.
  const epic = grid.getByRole("button", { name: /^FLO-36 /, expanded: false });
  await epic.click();
  await expect(grid.getByRole("button", { name: /^FLO-36 /, expanded: true })).toBeVisible();
  await expect(grid.getByRole("rowheader", { name: /^FLO-52\b/ })).toBeVisible();

  // A month opens into weeks; focus lands on the first week's header.
  await grid.getByRole("button", { name: /^Expand \d{4}-\d{2} into weeks$/ }).click();
  const weeks = grid.getByRole("button", { name: /^Expand \d{4}-W\d{2} into days$/ });
  await expect(weeks.first()).toBeFocused();
  await expect(grid.getByRole("button", { name: /^Collapse \d{4}-\d{2}$/ })).toHaveAttribute("aria-expanded", "true");

  // A cell holding logged time carries its author: the keyboard focus alone opens the tooltip with the figures.
  const costCell = grid.getByRole("gridcell", { name: /^FLO-36 .*Cost \(AC\): [\d.]+ MD \(.*Sample User \d+: [\d.]+ MD/ }).first();
  await costCell.focus();
  const tooltip = page.getByRole("tooltip");
  await expect(tooltip).toBeVisible();
  // Sections: a heading with its figure on the right, an author as a label span followed by its value span.
  await expect(tooltip).toContainText(/Plan \(PV\)\s*[\d.]+ MD/);
  await expect(tooltip).toContainText(/Cost \(AC\)\s*[\d.]+ MD/);
  const author = tooltip.getByText(/^Sample User \d+$/).first();
  await expect(author).toBeVisible();
  await expect(author.locator("xpath=following-sibling::span")).toHaveText(/^[\d.]+ MD$/);

  // What the stylesheet resolved to in a real browser (the unit tests cannot compute styles).
  // A focused cell and the controls in the sticky chrome scroll clear of the sticky column and header rows.
  expect(await computedPx(costCell, "scroll-margin-left"), "a cell clears the sticky item column").toBeGreaterThan(0);
  expect(await computedPx(costCell, "scroll-margin-top"), "a cell clears the sticky header rows").toBeGreaterThan(0);
  expect(await computedPx(grid.getByRole("button", { name: /^FLO-36 /, expanded: true }), "scroll-margin-top"), "a row toggle clears the header rows").toBeGreaterThan(0);
  expect(await computedPx(weeks, "scroll-margin-left"), "a column toggle clears the item column").toBeGreaterThan(0);
  // A bar, the done marker and the window outline each keep a halo in the surface colour so their edges meet the surface.
  expect(await computed(page.locator('[data-layer="pv"]'), "box-shadow"), "a bar's halo").not.toBe("none");
  expect(await computed(page.getByText("◆"), "text-shadow"), "the done marker's halo").not.toBe("none");
  expect(await computed(page.locator("[data-window]"), "box-shadow"), "the window outline's ring").not.toBe("none");
  // The open card scrolls inside itself when taller than the screen.
  expect(await computed(tooltip, "max-height"), "the tooltip is height-capped").not.toBe("none");
  expect(await computed(tooltip, "overflow")).toBe("auto");

  // A layer toggled off takes its bars (and, for Approximate EV, the done markers and the hollow "worked, nothing earned" marks) away;
  // the others stay.
  await layers.getByRole("switch", { name: "Approximate EV", exact: true }).click();
  await expect(page.locator('[data-layer="aev"]')).toHaveCount(0);
  await expect(page.locator('[data-layer="done"]')).toHaveCount(0);
  await expect(page.locator('[data-layer="unearned"]')).toHaveCount(0);
  await expect(page.locator('[data-layer="pv"]').first()).toBeVisible();

  // The Burn-up tab shows the same selection day by day: the tab is in the URL, the chart names its series, the numbers sit
  // behind the disclosure (the newest day first), and a hop back to the matrix drops the param and redraws the grid.
  await page.getByRole("tab", { name: "Burn-up", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`[?&]view=burnup(&|$)`));
  await expect(page.getByRole("heading", { level: 3, name: "Burn-up", exact: true })).toBeVisible();
  const chart = page.getByRole("group", { name: "Chart: cumulative plan, earned value, approximate EV and cost in man-days by day" });
  await expect(chart).toBeVisible();
  for (const series of ["Plan (PV)", "Earned value (EV)", "Approximate EV", "Cost (AC)"]) await expect(chart.getByText(series, { exact: true })).toBeVisible();
  const toggle = page.getByRole("button", { name: "Show daily figures", exact: true });
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await expect(page.getByRole("button", { name: "Hide daily figures", exact: true })).toHaveAttribute("aria-expanded", "true");
  const daily = page.getByRole("table", { name: "Cumulative figures per day", exact: true });
  await expect(daily).toBeVisible();
  const figure = /^-?\d+(\.\d+)?$/;
  // The newest day (the first row) always carries a plan figure; earned value, approximate EV and cost end where the data does, so the
  // first row that has them (an em dash otherwise) must hold numbers in all four columns.
  const rows = daily.getByRole("row");
  await expect(rows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  await expect(rows.nth(1).getByRole("cell").nth(1)).toHaveText(figure);
  const current = rows.filter({ has: page.getByRole("cell").nth(4), hasNot: page.getByRole("cell", { name: "—", exact: true }) }).first();
  for (const column of [1, 2, 3, 4]) await expect(current.getByRole("cell").nth(column)).toHaveText(figure);
  await page.getByRole("tab", { name: "Matrix", exact: true }).click();
  await expect(page).not.toHaveURL(/view=/);
  await expect(grid).toBeVisible();
  await expect(page.getByRole("heading", { level: 3, name: "Burn-up", exact: true })).toHaveCount(0);

  // Epics mode, one epic: the mode switch keeps the Domain, which narrows the epic list (the golden epic is FLO's); its planned window is
  // outlined across the columns it spans.
  await editSelection.click();
  await expect(editSelection).toHaveAttribute("aria-expanded", "true");
  await page.getByRole("radiogroup", { name: "How to select work" }).getByText("Epics", { exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Domain", exact: true })).toHaveValue("FLO");
  await pickInPanel(page, "Epics", new RegExp(`^${GOLDEN_EPIC.issueKey} `), dataSourceName);
  await page.getByRole("button", { name: "Show", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/reports/deep-dive\\?epicId=${GOLDEN_EPIC.issueKey}(&connectionId=${dataSourceId})?$`));
  await expect(grid.getByRole("button", { name: new RegExp(`^${GOLDEN_EPIC.issueKey} `), expanded: false })).toBeVisible();
  await expect(page.locator("[data-window]").first()).toBeVisible();
}

/** The report's old address (it was called Velocity) still opens it, keeping the filters. */
async function expectLegacyVelocityRedirect(page: Page): Promise<void> {
  await page.goto("/reports/velocity?lastSprints=3");
  await expect(page).toHaveURL(/\/reports\/commitments\?.*lastSprints=3/);
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
}

/** The collapsible filter bar journey: folded by default, the badge counts a narrowing, the choice is remembered per report. */
async function foldAndUnfoldFilters(page: Page): Promise<void> {
  await openGroup(page, "Delivery");
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  const toggle = page.getByRole("button", { name: /^Filters/ });
  const period = page.getByRole("combobox", { name: "Period", exact: true });

  // The bar starts folded: just the toggle, no badge, no controls — and the page already sits on a team (commitments never read the whole unit).
  await expect(page).toHaveURL(/teamId=\d+/);
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(toggle).toHaveAccessibleName("Filters");
  await expect(period).toHaveCount(0);

  // Unfolding shows the controls; a narrowing counts on the toggle, folded or not.
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(period).toBeVisible();
  // Commitments always has a team (it is required, so the bar offers no "all teams" to compare it with and the badge never counts it):
  // the narrowing that shows on the toggle here is the period.
  await period.click();
  await page.getByRole("option", { name: "Last 30 days", exact: true }).click();
  await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=/);
  await expect(toggle).toHaveAccessibleName("Filters 1");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(period).toHaveCount(0);
  await expect(toggle).toHaveAccessibleName("Filters 1");

  // The choice is remembered per report: a reload keeps it folded, an unfolded bar stays unfolded.
  await page.reload();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await page.reload();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(period).toBeVisible();

  // Another report keeps its own flag: Throughput is still folded.
  await page.getByRole("tab", { name: "Throughput", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /^Filters/ })).toHaveAttribute("aria-expanded", "false");
}

/**
 * The per-screen filter memory (client-side): the period and the person a user set on Commitments come back when they return through
 * the nav, with the same URL; a deep link is taken as written and does not overwrite what was remembered.
 */
async function rememberFilters(page: Page): Promise<void> {
  await openGroup(page, "Delivery");
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  await openFilters(page);
  // A team is required and the page starts on the first one by name, which may not be this spec's own.
  await pickFilter(page, "Team", teamName);
  await page.getByRole("combobox", { name: "Period", exact: true }).click();
  await page.getByRole("option", { name: "Last 30 days", exact: true }).click();
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=/);
  const search = new URL(page.url()).search;

  // Away through the nav and back through it: a bare address, rewritten to the filters the user left.
  const comeBack = async () => {
    await page.getByRole("link", { name: "Home", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Flow", exact: true })).toBeVisible();
    await page.getByRole("link", { name: "Delivery", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  };
  await comeBack();
  await expect.poll(() => new URL(page.url()).search).toBe(search);
  await openFilters(page);
  await expect(page.getByRole("combobox", { name: "Period", exact: true })).toHaveValue("Last 30 days");
  await expect(page.getByRole("button", { name: /^Filters/ })).toHaveAccessibleName("Filters 2");

  // A link that carries filters is taken as written — and does not replace the memory.
  await page.goto("/reports/commitments?lastSprints=3");
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/lastSprints=3/);
  await expect(page).not.toHaveURL(/from=/);
  await comeBack();
  await expect.poll(() => new URL(page.url()).search).toBe(search);
}

/** Delivery, narrowed to the golden sprint, then the Throughput tab (which carries the period and team over). */
async function openGoldenThroughput(page: Page): Promise<void> {
  await openGroup(page, "Delivery");
  await pickGoldenSprint(page);
  await page.getByRole("tab", { name: "Throughput", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
}

/** The cumulative throughput journey: the Chart view switch, the URL param, the running-total columns, and the param dropped on the next tab. */
async function switchThroughputToCumulative(page: Page): Promise<void> {
  await openGoldenThroughput(page);
  await expect(page.getByRole("group", { name: "Chart: delivered scope by period", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "Chart: delivered scope by sprint", exact: true })).toBeVisible();

  // Per period is the default and leaves the URL alone; Cumulative draws running totals and writes the choice.
  const view = page.getByRole("radiogroup", { name: "Chart view", exact: true });
  await expect(view.getByRole("radio", { name: "Per period", exact: true })).toBeChecked();
  await expect(page).not.toHaveURL(/view=/);
  await view.getByText("Cumulative", { exact: true }).click();
  await expect(page).toHaveURL(/view=cumulative/);
  await expect(page.getByRole("group", { name: "Chart: delivered scope by period, cumulative", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "Chart: delivered scope by sprint, cumulative", exact: true })).toBeVisible();

  // Every number of the areas is in a table: the last week's running total is the period's total.
  const summary = await page.getByText(/^Delivered in the period: [\d.]+ MD/).textContent();
  const periodMd = /: ([\d.]+) MD/.exec(summary ?? "")?.[1] ?? "";
  const bucketTable = page.getByRole("table", { name: "Delivered per period, as a table" });
  await expect(bucketTable.getByRole("columnheader", { name: "Cumulative (MD)", exact: true })).toBeVisible();
  await expect(bucketTable.getByRole("row").last().getByRole("cell").nth(3)).toHaveText(periodMd);
  // One sprint is in view, so its running total is its own delivered MD.
  await expect(goldenRow(page).getByRole("cell").nth(5)).toHaveText(md(GOLDEN.deliveredMd));

  // Back to per period drops the param; the choice follows no report tab (it is no filter).
  await view.getByText("Per period", { exact: true }).click();
  await expect(page).not.toHaveURL(/view=/);
  await expect(page.getByRole("group", { name: "Chart: delivered scope by period", exact: true })).toBeVisible();
  await view.getByText("Cumulative", { exact: true }).click();
  await page.getByRole("tab", { name: "Commitments", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  await expect(page).not.toHaveURL(/view=/);
}

/** WIP at USER level: one stub person picked on the team's roster (see the scenario in `scenarios/reports.md`). */
async function expectOnePersonsWip(page: Page): Promise<void> {
  await openReport(page, "/reports/wip", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 2, name: "WIP", exact: true })).toBeVisible();
  await openFilters(page);

  // Picking the roster's stub person re-reads WIP at USER level: the request names the account, the answer is a
  // non-empty daily series with that one person in every point's byUser (computed at query time, inside the team).
  const answered = page.waitForResponse((r) => {
    const url = new URL(r.url());
    return r.request().method() === "GET" && url.pathname === "/api/v1/reports/wip" && url.searchParams.has("accountId") && r.ok();
  });
  await pickFilter(page, "Member", memberName);
  const body = await (await answered).json();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  expect(body.meta.level).toBe("USER");
  expect(body.series.length, "the person's days are listed").toBeGreaterThan(0);
  expect(body.series[0].byUser).toHaveLength(1);
  expect(body.series[0].byUser[0].label).toBe(memberName);

  // The page says whose tasks it counts, keeps its chart and band table, and offers no per-person view for ONE person.
  await expect(page.getByText(/Counts the tasks the picked people hold at the end of each day/)).toBeVisible();
  await expect(page.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeVisible();
  const bands = page.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
  await expect(bands.getByRole("cell", { name: "In progress", exact: true })).toBeVisible();
  await expect(page.getByRole("radio", { name: "By person", exact: true })).toHaveCount(0);
}

/** Aging WIP's chart: the team's board columns on the workflow axis, one dot per task, statuses without the team. */
async function readAgingChart(page: Page): Promise<void> {
  // The FLO board is mapped to this spec's team, so the x axis is that board's columns (the API says so: kind COLUMN).
  const aging = page.waitForResponse((r) => r.url().includes("/api/v1/reports/aging-wip") && r.url().includes(`teamId=${teamId}`) && r.ok());
  await openReport(page, "/reports/aging-wip", `&teamId=${teamId}`);
  const report = (await (await aging).json()) as { positions: { label: string; kind: string }[]; items: { itemKind: string }[] };
  expect(report.positions.length, "the team has work in progress").toBeGreaterThan(0);
  expect(report.positions.every((position) => position.kind === "COLUMN")).toBe(true);

  await expect(page.getByRole("heading", { level: 3, name: "Aging chart", exact: true })).toBeVisible();
  const chart = page.getByRole("group", { name: /^Chart: age in working days of each item in progress/ });
  await expect(chart.getByText("Workflow position", { exact: true })).toBeVisible();
  await expect(chart.getByText("Age (working days)", { exact: true })).toBeVisible();
  for (const position of report.positions) await expect(chart.getByText(position.label, { exact: true })).toBeVisible();

  // One dot per task; the table below lists the same work (epics are behind the page's Tasks | Epics switch).
  const tasks = report.items.filter((item) => item.itemKind === "TASK").length;
  expect(tasks).toBeGreaterThan(0);
  await expect(chart.locator("[data-item]")).toHaveCount(tasks);
  const openWork = page.getByRole("table", { name: "Open work, oldest first" });
  await expect(openWork.getByRole("row")).toHaveCount(tasks + 1);

  // The switch shows the other kind (it exists wherever epics are offered, so only assert it when epics are listed).
  const epics = report.items.length - tasks;
  if (epics > 0) {
    // Mantine's radio inputs are 0x0: click the visible label, scoped to the group; the radio itself only carries the checked state.
    const kind = page.getByRole("radiogroup", { name: "Items shown: tasks or epics" });
    await expect(kind.getByRole("radio", { name: "Tasks", exact: true })).toBeChecked();
    await kind.getByText("Epics", { exact: true }).click();
    await expect(page).toHaveURL(/[?&]kind=epics\b/);
    await expect(kind.getByRole("radio", { name: "Epics", exact: true })).toBeChecked();
    await expect(openWork.getByRole("row")).toHaveCount(epics + 1);
    await kind.getByText("Tasks", { exact: true }).click();
    await expect(page).not.toHaveURL(/[?&]kind=/);
    await expect(openWork.getByRole("row")).toHaveCount(tasks + 1);
  } else {
    // The stub's open work holds no epic, so the switch is not exercised here (the vitest page tests cover it).
    test.info().annotations.push({ type: "note", description: "Aging WIP: the stub lists no in-progress epic; the Tasks | Epics switch was not exercised." });
  }

  // The page is never read for the whole unit: a link without a team is given one, and the Team select cannot be cleared.
  await page.goto(`/reports/aging-wip?connectionId=${dataSourceId}&${WINDOW}`);
  await expect(page).toHaveURL(/[?&]teamId=\d+/);
  // (No chart is asserted: the default team may be another spec's empty one.)
  await openFilters(page);
  await expect(page.getByRole("combobox", { name: "Team", exact: true })).not.toHaveValue("");
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
}

async function readHomeOverview(page: Page): Promise<void> {
  await login(page, reader.email, reader.password);
  await showTeamsOnHome(page, [teamId]);
  await expect(page.getByRole("heading", { level: 2, name: "Flow", exact: true })).toBeVisible();

  // The freshness line, then the seven sections of every team, each with its title as the way into the full report.
  await expect(page.getByRole("group", { name: "How fresh the data is", exact: true })).toBeVisible();
  for (const title of HOME_SECTIONS) {
    const section = page.getByRole("region", { name: title, exact: true });
    await expect(section).toBeVisible();
    await expect(section.getByRole("link", { name: title, exact: true })).toBeVisible();
  }

  // Every chart has a visually hidden table of its numbers (screen readers read it, Tab reveals it): the spec's team is listed in each,
  // attached to the page but never shown.
  const sprintRow = homeTable(page, "Last sprint", "Each team's last closed sprint, as a table").getByRole("row", {
    name: new RegExp(`^${escapeRe(teamName)} FLO Sprint \\d+`),
  });
  await expect(sprintRow).toBeAttached();
  const unitWide = [
    ["How big the work is", "Median estimate (MD), as a table"],
    ["How big the work is", "Median cycle time (working days), as a table"],
    ["How big the work is", "Median logged cost (MD), as a table"],
    ["Epics in progress", "Epics in progress, as a table"],
    ["Epics in progress", "Epic age, p85 (working days), as a table"],
    ["Tasks in progress", "Tasks in progress, as a table"],
    ["Tasks in progress", "Task age, p95 (working days), as a table"],
    ["How much is delivered", "Cycle time, p50 (working days), as a table"],
  ] as const;
  for (const [section, table] of unitWide) {
    const hidden = homeTable(page, section, table);
    await expect(hidden, `${section}: ${table}`).toBeAttached();
    await expect(hidden.getByRole("row", { name: new RegExp(`^${escapeRe(teamName)}\\b`) }), `${section}: ${table}`).toBeAttached();
  }
  // The quality section has no charts: its one table of counts is visible, with a row for the spec's team.
  const findings = homeTable(page, "What is missing from the data", "Findings per team");
  await expect(findings).toBeVisible();
  await expect(findings.getByRole("row", { name: new RegExp(`^${escapeRe(teamName)}\\b`) })).toBeVisible();
  // The accuracy curves and the weekly delivery are one chart (and table) per team, named after it.
  await expect(homeTable(page, "How well estimates hold", `${teamName}, accuracy of estimates, as a table`)).toBeAttached();
  await expect(homeTable(page, "How much is delivered", `${teamName}, delivery per week, as a table`)).toBeAttached();

  // A section title never lands bare: the report opens with the window of the figures in the URL. The per-team sections also link
  // the team's name (its chart's title) with the team and the same window.
  const delivered = page.getByRole("region", { name: "How much is delivered", exact: true });
  const teamLink = (await delivered.getByRole("link", { name: teamName, exact: true }).getAttribute("href")) ?? "";
  expect(teamLink).toMatch(/^\/reports\/throughput\?/);
  expect(teamLink).toContain(`teamId=${teamId}`);
  expect(teamLink).toMatch(/from=\d{4}-\d{2}-\d{2}/);
  expect(teamLink).toMatch(/to=\d{4}-\d{2}-\d{2}/);
  await page.getByRole("region", { name: "How big the work is", exact: true }).getByRole("link", { name: "How big the work is", exact: true }).click();
  await expect(page).toHaveURL(/\/reports\/cycle-time\?.*from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/);
  await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();

  await page.getByRole("link", { name: "Home", exact: true }).click();
  await page.getByRole("region", { name: "Last sprint", exact: true }).getByRole("link", { name: "Last sprint", exact: true }).click();
  await expect(page).toHaveURL(/\/reports\/commitments\?.*lastSprints=1/);
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
}

/**
 * Home lists at most three teams: by default the first three by name for a viewer who manages none. A throwaway team named to sort
 * first is therefore in the default and not in the pick of the spec's own team; the pick is remembered across a reload and "Back to my
 * default teams" forgets it. Rows are read in the visible "Findings per team" table (one row per listed team).
 */
async function pickHomeTeams(page: Page): Promise<void> {
  const { api } = await apiAsAdmin();
  const created = await api.post("/api/v1/teams", { data: { name: uniqueText("0000-home") } });
  expect(created.status(), await created.text()).toBe(201);
  const { id: extraId, name: extraName } = (await created.json()) as { id: number; name: string };
  await api.dispose();
  try {
    await login(page, reader.email, reader.password);
    const row = (name: string) =>
      homeTable(page, "What is missing from the data", "Findings per team").getByRole("row", { name: new RegExp(`^${escapeRe(name)}\\b`) });
    const reset = page.getByRole("button", { name: "Back to my default teams", exact: true });
    const picker = page.getByRole("combobox", { name: "Teams shown", exact: true });
    const pills = page.locator(".mantine-Pill-root");
    const removeButton = (name: string) => pills.filter({ hasText: name }).locator(".mantine-Pill-remove");

    // The default: the throwaway team sorts first, so it is listed; nothing is remembered and there is nothing to go back to.
    await expect(row(extraName)).toBeVisible();
    await expect(reset).toHaveCount(0);

    // Pick only the spec's team: make room for it (a full picker adds nothing), add it, then drop every other team.
    await expect(picker).toBeVisible();
    if ((await pills.filter({ hasText: teamName }).count()) === 0) {
      await removeButton(extraName).click({ force: true });
      await picker.click();
      await page.getByRole("option", { name: teamName, exact: true }).click();
      await page.keyboard.press("Escape");
    }
    // Never remove the last pill: an empty picker means "the default" again.
    for (let left = await pills.count(); left > 1; left--) {
      await pills.filter({ hasNotText: teamName }).first().locator(".mantine-Pill-remove").click({ force: true });
      await expect(pills).toHaveCount(left - 1);
    }
    await expect(row(teamName)).toBeVisible();
    await expect(row(extraName)).toHaveCount(0);
    await expect(reset).toBeVisible();

    // The choice survives a reload.
    await page.reload();
    await expect(row(teamName)).toBeVisible();
    await expect(row(extraName)).toHaveCount(0);
    await expect(reset).toBeVisible();

    // Back to the default: the throwaway team is listed again and the button is gone.
    await reset.click();
    await expect(row(extraName)).toBeVisible();
    await expect(reset).toHaveCount(0);
  } finally {
    await deleteViaApi([`/api/v1/teams/${extraId}`]);
  }
}

async function readHomeKeyboard(page: Page): Promise<void> {
  await login(page, reader.email, reader.password);
  await showTeamsOnHome(page, [teamId]);
  const table = homeTable(page, "Last sprint", "Each team's last closed sprint, as a table");
  await expect(table).toBeAttached();
  // The revealed element is the table's wrapper: a 1px clipped box while hidden, the table's own width once something inside has focus.
  const box = table.locator("xpath=..");
  const width = async () => (await box.boundingBox())?.width ?? 0;
  const buttons = table.getByRole("button", { name: showFigure(`${teamName} · `) });
  await expect(buttons.first()).toBeAttached();
  await expect.poll(width).toBeLessThanOrEqual(2);

  // The roving tab stop: of every button of the table exactly one is tabbable, whichever it is.
  expect(await buttons.count()).toBeGreaterThan(1);
  await expect(table.locator('button[tabindex="0"]')).toHaveCount(1);
  await buttons.first().focus();
  await expect.poll(width).toBeGreaterThan(200);
  await expect(buttons.first()).toBeInViewport();

  // Arrow keys step along the row; Tab leaves the table (its other figures are never Tab stops): walking forward never lands inside
  // the table again, and reaches another Home table.
  await page.keyboard.press("ArrowRight");
  await expect(buttons.nth(1)).toBeFocused();
  const walked: Array<{ inSprintTable: boolean; table: string | null }> = [];
  for (let step = 0; step < 15; step++) {
    await page.keyboard.press("Tab");
    walked.push(
      await page.evaluate(() => {
        const label = document.activeElement?.closest("table")?.getAttribute("aria-label") ?? null;
        return { inSprintTable: label === "Each team's last closed sprint, as a table", table: label };
      }),
    );
  }
  expect(walked.some((stop) => stop.inSprintTable), "Tab never returns into the table it left").toBe(false);
  expect(walked.some((stop) => stop.table !== null), "Tab reaches the next hidden table").toBe(true);
  // ... and the table closes again once focus is gone.
  await expect.poll(width).toBeLessThanOrEqual(2);

  // The trend arrows (↑ ↓ →) are SVG text beside a bar: none may poke out of its chart (the bar's headroom is the chart's job).
  await page.getByRole("region", { name: "How much is delivered", exact: true }).scrollIntoViewIfNeeded();
  const chart = page.getByRole("group", { name: "Chart: Cycle time, p50 (working days)", exact: true });
  await expect(chart.locator("svg.recharts-surface")).toBeVisible();
  const surface = await chart.locator("svg.recharts-surface").boundingBox();
  expect(surface).not.toBeNull();
  const arrows = chart.locator("svg.recharts-surface text").filter({ hasText: /^[↑↓→]$/ });
  for (const arrow of await arrows.all()) {
    const at = await arrow.boundingBox();
    expect(at).not.toBeNull();
    expect(at!.x).toBeGreaterThanOrEqual(surface!.x);
    expect(at!.x + at!.width).toBeLessThanOrEqual(surface!.x + surface!.width + 0.5);
  }
}

test.describe("reports, read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync (~1,200 issues) plus the derivation that follows it: the two waits below
    // are each bounded (340s / 240s / 240s), so the hook gets their sum plus headroom.
    test.setTimeout(960_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId, name: dataSourceName } = await syncStubDataSourceViaApi(adminApi, "e2e-reports-ds"));
    teamName = uniqueText("e2e-reports-team");
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, teamName));
    // A stub person on the team's roster, so the cost matrix has a real author-team row to drill into.
    ({ membershipId, displayName: memberName } = await addStubMemberViaApi(adminApi, teamId));

    const name = uniqueText("e2e-reports-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/flow-users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
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
      userId === undefined ? null : `/api/v1/flow-users/${userId}`,
    ];
    for (const path of paths) {
      if (path === null) continue;
      const removed = await cleanup.delete(path);
      expect([204, 404], `${path} -> ${removed.status()}`).toContain(removed.status());
    }
    await cleanup.dispose();
    await api?.dispose();
  });

  test("a regular user reads the golden sprint's commitments", async ({ page }) => {
    await openGroup(page, "Delivery");
    await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
    await pickGoldenSprint(page);

    await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: initial and final scope by sprint", exact: true })).toBeVisible();
    // The team level adds the sprint's capacity and the load it puts on it (this team has no calendar or default figure, so the figures are the computed members × working days).
    const sprintTable = page.getByRole("table", { name: "Sprints and their figures" });
    for (const name of ["Capacity (MD)", "Load"]) await expect(sprintTable.getByRole("columnheader", { name, exact: true })).toBeVisible();

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

    // The same scope cut by epic: the segments add up to the figures above, and the biggest one drills to exactly its own items.
    await expect(page.getByRole("group", { name: "Chart: initial and final scope by sprint, stacked by epic", exact: true })).toBeVisible();
    await readEpicSplit(page, {
      heading: "Initial and final scope per sprint, by epic",
      table: "Initial and final scope per sprint and epic",
      headers: ["Initial (MD)", "Initial (items)", "Final (MD)", "Final (items)"],
      totals: [GOLDEN.committedMd, GOLDEN.committedItems, GOLDEN.finalMd, GOLDEN.finalItems],
      itemsColumn: 1,
      mdColumn: 0,
      bucket: "Committed",
    });
    await expectLegacyVelocityRedirect(page);
  });

  test("the user regroups throughput by month", async ({ page }) => {
    await openGoldenThroughput(page);
    // Switching tabs carries the period and team with it.
    await expect(page).toHaveURL(/\/reports\/throughput\?/);
    await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));

    // The sprint view: what was done inside the golden sprint while in it.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(3)).toHaveText(md(GOLDEN.deliveredMd));
    await expect(cells.nth(4)).toHaveText(String(GOLDEN.deliveredItems));

    // The same delivered work cut by epic (the sprint view only; the period view below is not split).
    await expect(page.getByRole("group", { name: "Chart: delivered scope by sprint, stacked by epic", exact: true })).toBeVisible();
    await readEpicSplit(page, {
      heading: "Delivered per sprint, by epic",
      table: "Delivered scope per sprint and epic",
      headers: ["Delivered (MD)", "Delivered (items)"],
      totals: [GOLDEN.deliveredMd, GOLDEN.deliveredItems],
      itemsColumn: 1,
      mdColumn: 0,
      bucket: "Delivered",
    });

    // The period view, by week (the default): a row per Monday across the sprint's window.
    await expect(page.getByText(/^Delivered in the period: [\d.]+ MD \(\d+ items?\)$/)).toBeVisible();
    const bucketTable = page.getByRole("table", { name: "Delivered per period, as a table" });
    await expect(bucketTable.getByRole("columnheader", { name: "Week starting" })).toBeVisible();
    const bucketRows = bucketTable.getByRole("row");
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
    expect(await bucketRows.count(), "header + at least two weekly buckets").toBeGreaterThan(2);

    // Regroup by month: the URL, the table's column and its rows (one month covers the window) follow.
    await openFilters(page);
    await page.getByRole("radiogroup", { name: "Group by" }).getByText("Month", { exact: true }).click();
    await expect(page).toHaveURL(/bucket=MONTH/);
    await expect(bucketTable.getByRole("columnheader", { name: "Month", exact: true })).toBeVisible();
    await expect(bucketRows).toHaveCount(2);
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}$/);
    await expect(page.getByRole("group", { name: "Chart: delivered scope by period" })).toBeVisible();
  });

  test("a user switches throughput to the cumulative view", ({ page }) => switchThroughputToCumulative(page));

  test("a user folds and unfolds the report filters and the choice is remembered", async ({ page }) => {
    await foldAndUnfoldFilters(page);
  });

  test("a user's filters are remembered when they come back", ({ page }) => rememberFilters(page));

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

    await sliceSprintConsistency(page);
  });

  test("the user reads cycle time", async ({ page }) => {
    await readCycleTime(page);
  });

  test("a user widens the cycle time histogram's long tail", ({ page }) => widenCycleTimeTail(page));

  test("the user prints a report and jumps back to the top", ({ page }) => printReportAndScrollToTop(page));

  test("the user reads task estimation accuracy", ({ page }) => readTaskAccuracy(page));

  test("the user reads the distribution of task estimates", ({ page }) => readTaskEstimates(page));

  test("the user reads the last three sprints and drills to a member", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickTeamAndPeriod(page, "Last 3 sprints");
    await expect(page).toHaveURL(/lastSprints=3/);
    await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

    // The team's three newest sprints, one row each (in the sprint table: the by-epic table repeats a sprint per epic).
    const sprintRows = page.getByRole("table", { name: "Sprints and their figures" }).getByRole("row", { name: /FLO Sprint \d+/ });
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

    // By stage (the default): the six stages (WAITING and BLOCKED included, A30, A36), as the chart's text alternative.
    const bands = page.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
    for (const stage of ["Not started", "In progress", "Waiting", "Blocked", "Done", "Unmapped status"]) {
      await expect(bands.getByRole("cell", { name: stage, exact: true })).toBeVisible();
    }

    // By status: the URL, the caption and the bands all follow — the workflow's own statuses.
    await openFilters(page);
    await page.getByRole("radiogroup", { name: "Count by" }).getByText("Status", { exact: true }).click();
    await expect(page).toHaveURL(/by=STATUS/);
    await expect(page.getByText("By status, ordered by stage and then by name.")).toBeVisible();
    for (const status of ["To Do", "In Progress", "Done"]) {
      await expect(bands.getByRole("cell", { name: status, exact: true })).toBeVisible();
    }
    await expect(bands.getByRole("cell", { name: "Not started", exact: true })).toHaveCount(0);
    await expect(page.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeVisible();

    // WIP is read for one team or one domain, never the whole unit: no "All teams", no clear button.
    const team = page.getByRole("combobox", { name: "Team", exact: true });
    await expect(team).toHaveValue(teamName);
    await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
    await team.click();
    await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
    await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
    await page.keyboard.press("Escape");
  });

  test("the user reads one person's work in progress inside the team", async ({ page }) => {
    await expectOnePersonsWip(page);
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

    // Blocked time: both distributions, the "never blocked" line and the most-blocked table.
    await page.getByRole("tab", { name: "Blocked time", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Blocked time", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Blocked working days", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Blocked share of cycle", exact: true })).toBeVisible();
    await expect(page.getByText(/^\d+ of \d+ items? (was|were) never blocked\./)).toBeVisible();
    await expect(percentilesOrNotice(page).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: "Most blocked items", exact: true })).toBeVisible();
  });

  test("the user reads the aging chart with the team's board columns on its workflow axis", async ({ page }) => {
    await readAgingChart(page);
  });

  test("the user drills from a domain into the golden epic's progress", async ({ page }) => {
    await drillIntoGoldenEpic(page);
  });

  test("the user reads a domain's progress over the period and its weekly SPI and CPI trend", ({ page }) => readDomainPeriodView(page));

  test("the user reads the data-quality findings", async ({ page }) => {
    await readDataQualityFindings(page);
  });

  test("the user reads the cost matrix of a team and sees capacity only as its manager", async ({ page }) => {
    await readCostMatrix(page);
  });

  test("the user reads the overview of the picked teams on Home and follows a section into its report", ({ page }) => readHomeOverview(page));

  test("the user picks which teams Home shows and the choice survives a reload", ({ page }) => pickHomeTeams(page));

  // The hidden tables are the keyboard route into the drill, so their reveal is pinned in a real browser (the unit tests cannot see
  // `:focus-within` or the CSS): hidden until a figure inside takes focus, ONE Tab stop per table, and the trend arrows inside their chart.
  test("the keyboard reveals a Home table on focus, one Tab stop per table, and the trend arrows stay inside their charts", ({ page }) => readHomeKeyboard(page));

  test("the user reads epic estimation accuracy against each epic's own estimate", async ({ page }) => {
    await readEpicAccuracy(page);
  });

  test("the user reads how estimates were adjusted", async ({ page }) => {
    await readEstimateAdjustments(page);
  });

  test("the user reads reported time beside flow efficiency", async ({ page }) => {
    await readReportedTime(page);
  });

  test("the user reads the estimated backlog and what it means in sprints", async ({ page }) => {
    await readEstimatedBacklog(page);
  });

  test("the user dives into the golden sprint and drills an epic to its tasks", async ({ page }) => {
    await diveIntoGoldenSprint(page);
  });

  test("the populated flow, epic and cost pages have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    expect(
      await populatedViolations(page, [
        { path: "/reports/wip", extra: `&teamId=${teamId}`, settled: (p) => p.getByRole("table", { name: "Latest day, average and peak per band, as a table" }) },
        { path: "/reports/epic-progress", extra: `&epicId=${GOLDEN_EPIC.issueKey}`, settled: (p) => p.getByRole("heading", { level: 3, name: "Plan", exact: true }) },
        { path: "/reports/cost-matrix", extra: `&teamId=${teamId}`, settled: (p) => p.getByRole("table", { name: /^Man-days logged in the period/ }) },
      ]),
    ).toEqual({});
  });

  // Home is scanned once its sections loaded (one request) and its first chart is drawn, and once more with a hidden table revealed
  // by focus (the revealed look is its own surface); the drift table's "reconstructed" badge is the orange light badge — a finding
  // of this very scan.
  test("the populated Home overview and data-quality page have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    // Home first: the session lands there (pinned to the spec's team, whatever else the stack holds).
    await showTeamsOnHome(page, [teamId]);
    for (const title of HOME_SECTIONS) {
      await expect(page.getByRole("region", { name: title, exact: true })).toBeVisible();
    }
    await expect(page.getByRole("group", { name: /^Chart: capacity, initial scope/ }).locator("svg.recharts-surface")).toBeVisible();
    const home = await axeViolations(page);
    const sprintTable = homeTable(page, "Last sprint", "Each team's last closed sprint, as a table");
    await sprintTable.getByRole("button", { name: showFigure(`${teamName} · `) }).first().focus();
    await expect.poll(async () => (await sprintTable.locator("xpath=..").boundingBox())?.width ?? 0).toBeGreaterThan(200);
    const revealed = await axeViolations(page);
    const rest = await populatedViolations(page, [{ path: "/reports/data-quality", extra: `&teamId=${teamId}`, settled: (p) => p.getByRole("group", { name: "Findings" }) }]);
    expect({ ...(home.length > 0 ? { "/": home } : {}), ...(revealed.length > 0 ? { "/ (a table revealed)": revealed } : {}), ...rest }).toEqual({});
  });
});
