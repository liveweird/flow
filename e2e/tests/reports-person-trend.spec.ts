// Person trend (report 20, `.claude/docs/reports.md` "Report 20"): one person's Compare-people figures over rolling 90-day windows, across every
// team, read the way D12 says everyone reads reports — by a NON-ADMIN user. The admin side is the shared fixture (global setup's
// synced-and-derived, paused Jira-stub connection and the team its FLO board is mapped to), which this spec only READS; it owns a throwaway
// reader, created through the API before the block and deleted after it (plus, in the team-picker test, one throwaway team with a stub person
// on its roster, removed inside that test). The person is the stub member that finished the most tasks in the window, found through the
// report's own API; the oracle for the page is that same API response (the table of a chart must equal it window by window), never a recomputation.
import type { APIRequestContext } from "@playwright/test";
import {
  STUB_MEMBER_CANDIDATES,
  addStubMemberViaApi,
  apiAsAdmin,
  axeViolations,
  deleteViaApi,
  expect,
  login,
  sharedFixtureReady,
  stubDayOffset,
  test,
  uniqueText,
} from "./helpers";

/** A year-long window ending a week after the stub's reference date: the stub's work is finished in autumn 2025. */
const YEAR_WINDOW = `from=${stubDayOffset(-365)}&to=${stubDayOffset(7)}`;

interface TrendBody {
  person: { accountId: string; label: string };
  windows: { end: string; from: string; coverage: "FULL" | "PARTIAL" | "NONE" }[];
  rows: { key: string; points: { end: string; value?: number; n: number; hidden: boolean }[] }[];
}

let api: APIRequestContext | undefined;
let dataSourceId = 0;
let userId: number | undefined;
let reader = { email: "", password: "" };
/** The person followed: the stub member with the most finished tasks in the window, and the trend the API answers for them. */
let person = { accountId: "", displayName: "" };
let trend: TrendBody | undefined;

const deepLink = () => `/reports/person-trend?connectionId=${dataSourceId}&${YEAR_WINDOW}&accountId=${person.accountId}`;
const escapeRe = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const COVERAGE_WORDS = { FULL: "complete", PARTIAL: "partial", NONE: "no data derived" } as const;

async function trendOf(adminApi: APIRequestContext, accountId: string): Promise<TrendBody> {
  const res = await adminApi.get(`/api/v1/reports/person-trend?connectionId=${dataSourceId}&${YEAR_WINDOW}&accountId=${accountId}`);
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await res.json()) as TrendBody;
}

/** The busiest stub member: the most finished tasks summed over the windows, read from the report itself. */
async function busiestPerson(adminApi: APIRequestContext): Promise<{ person: { accountId: string; displayName: string }; trend: TrendBody }> {
  let best: { person: { accountId: string; displayName: string }; trend: TrendBody; finished: number } | undefined;
  for (const candidate of STUB_MEMBER_CANDIDATES) {
    const body = await trendOf(adminApi, candidate.accountId);
    const finished = (body.rows.find((row) => row.key === "deliveredItems")?.points ?? []).reduce((sum, point) => sum + (point.value ?? 0), 0);
    if (best === undefined || finished > best.finished) best = { person: candidate, trend: body, finished };
  }
  if (best === undefined) throw new Error("no stub member candidates");
  return best;
}

test.describe("person trend, read by a regular user", () => {
  test.beforeAll(async () => {
    // The shared fixture's SYNC plus the derivation that follows it, each wait bounded (340s / 240s) — a no-op once another spec prepared it.
    test.setTimeout(720_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    const name = uniqueText("e2e-trend-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/flow-users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
    expect(created.status(), await created.text()).toBe(201);
    userId = (await created.json()).id as number;

    ({ dataSourceId } = await sharedFixtureReady(adminApi));
    ({ person, trend } = await busiestPerson(adminApi));
  });

  test.afterAll(async () => {
    // A FRESH admin session (the beforeAll token may have expired across the bounded waits). Only the reader is this spec's to delete.
    const { api: cleanup } = await apiAsAdmin();
    if (userId !== undefined) {
      const removed = await cleanup.delete(`/api/v1/flow-users/${userId}`);
      expect([204, 404], `/api/v1/flow-users/${userId} -> ${removed.status()}`).toContain(removed.status());
    }
    await cleanup.dispose();
    await api?.dispose();
  });

  test("a regular user follows one person: a chart per figure with its cue, the numbers behind a chart equal the API window by window, a clean axe scan", async ({ page }) => {
    await login(page, reader.email, reader.password);
    // A bare visit is the picker and an empty state, on a year written into the URL; nothing is requested until a person is picked.
    await page.goto("/reports/person-trend");
    await expect(page.getByRole("heading", { level: 2, name: "Person trend", exact: true })).toBeVisible();
    await expect(page.getByText("Pick a person to see how their figures changed.")).toBeVisible();
    await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}$/);

    // The deep link of the spec's connection and window: the person rides in the URL.
    await page.goto(deepLink());
    await expect(page.getByRole("heading", { level: 3, name: "Cycle time, working days", exact: true })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`accountId=${person.accountId}`));
    expect(page.url()).not.toContain("teamId");
    await expect(page.getByRole("combobox", { name: "Person", exact: true })).toHaveValue(person.displayName);

    // One card per figure (the two cycle-time percentiles share one) and the work mix, each chart drawn.
    const titles = page.getByRole("heading", { level: 3 });
    await expect(titles).toHaveCount(14);
    await expect(titles.last()).toHaveText("Work mix");
    await expect(page.getByRole("group", { name: "Delivered (items)", exact: true }).locator(".recharts-surface").first()).toBeVisible();

    // Every chart says where it is going: an arrow and a word with the two window ends, or why there is no mark.
    const cues = page.locator('[data-testid^="cue-"]');
    await expect(cues).toHaveCount(14);
    for (const text of await cues.allInnerTexts()) {
      if (text.includes("No mark:")) {
        expect(text).toContain("No mark: fewer than two full windows with a figure in this range.");
      } else {
        expect(text).toMatch(/first window ending \d{4}-\d{2}-\d{2}, last ending \d{4}-\d{2}-\d{2}/);
        // The mark, when there is one, is an arrow and a WORD ("Too few items to call" where a side is under the minimum sample).
        if (/[↑↓→]/.test(text)) expect(["Better", "Worse", "No change", "Too few items to call"].some((word) => text.includes(` ${word} · first`))).toBe(true);
      }
    }

    // The numbers behind Delivered (items) are the API's, window by window: a dash for a window with nothing derived, the coverage in words.
    const card = page.getByRole("heading", { level: 3, name: "Delivered (items)", exact: true }).locator("xpath=ancestor::*[contains(@class,'mantine-Paper-root')][1]");
    await card.getByRole("button", { name: "Show the numbers" }).click();
    const table = card.getByRole("table", { name: "Delivered (items): figures by window", exact: true });
    await expect(table).toBeVisible();
    const delivered = trend?.rows.find((row) => row.key === "deliveredItems");
    expect(delivered, "the API answers a deliveredItems row").toBeDefined();
    const body = table.locator("tbody tr");
    await expect(body).toHaveCount(trend?.windows.length ?? -1);
    const shown = await body.evaluateAll((rows) => rows.map((row) => Array.from(row.querySelectorAll("td")).map((cell) => cell.textContent ?? "")));
    const expected = (trend?.windows ?? []).map((window, index) => {
      const point = delivered?.points[index];
      return [window.end, window.coverage === "NONE" ? "—" : String(point?.value ?? "—"), String(point?.n ?? 0), COVERAGE_WORDS[window.coverage]];
    });
    expect(shown).toEqual(expected);

    // The pointer is parked off the page first: the click left it over the disclosure button, whose theme-wide subtle hover tint is a transient
    // state this scan is not about (the settled page is).
    await page.mouse.move(0, 0);
    expect(await axeViolations(page)).toEqual([]);
  });

  test("a figure of a chart's numbers opens the items behind that point: the drawer is headed by the row and the window, Escape closes it and focus returns to the figure, and a click on the chart's own dot opens the same list", async ({ page }) => {
    // The oracle is the API's own answer: the busiest person's latest window of a non-empty "Delivered (items)" (its figure is a button in the table).
    const delivered = trend?.rows.find((row) => row.key === "deliveredItems");
    const index = (trend?.windows ?? []).map((window, i) => (window.coverage !== "NONE" && (delivered?.points[i]?.value ?? 0) > 0 ? i : -1)).filter((i) => i >= 0).pop();
    expect(index, "the busiest person finished something in a window with data").toBeDefined();
    const point = delivered?.points[index ?? 0];
    const end = trend?.windows[index ?? 0]?.end ?? "";
    const from = trend?.windows[index ?? 0]?.from ?? "";
    const figure = String(point?.value);

    await login(page, reader.email, reader.password);
    await page.goto(deepLink());
    const card = page.getByRole("heading", { level: 3, name: "Delivered (items)", exact: true }).locator("xpath=ancestor::*[contains(@class,'mantine-Paper-root')][1]");
    await card.getByRole("button", { name: "Show the numbers" }).click();
    const button = card.getByRole("button", { name: new RegExp(`^${escapeRe(figure)} — ${escapeRe(end)}: show the items of Delivered \\(items\\)$`) });

    await button.focus();
    await page.keyboard.press("Enter");
    const drawer = page.getByRole("dialog", { name: `Delivered (items) · ${from} – ${end}`, exact: true });
    await expect(drawer).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`drill=point%3AdeliveredItems%3A${end}`));
    // The server's own line equals the point's count (singular for one), and the table lists at most the cap.
    await expect(drawer.getByText(new RegExp(`^${escapeRe(figure)} items? ·`))).toBeVisible();
    await expect(drawer.getByText("The data changed since the chart was drawn.")).toHaveCount(0);
    await expect(drawer.getByRole("table", { name: "Items behind the bar" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);
    await expect(page).not.toHaveURL(/drill=/);
    await expect(button).toBeFocused();

    // The pointer route: a click on the chart's OWN dot of that window (the real recharts line, not the table) opens the same list. A gap draws no dot,
    // so the circles are the windows that have a figure (not NONE, not withheld, a value), in window order; the pointer is moved onto the dot first
    // so recharts makes it the active one, whose click handler is the other half of what the page wires.
    const drawn = (trend?.windows ?? []).flatMap((window, i) => {
      const cell = delivered?.points[i];
      return window.coverage !== "NONE" && cell !== undefined && !cell.hidden && cell.value !== undefined ? [i] : [];
    });
    const dot = page.getByRole("group", { name: "Delivered (items)", exact: true }).locator(".recharts-line-dots circle").nth(drawn.indexOf(index ?? 0));
    // Centred, not merely "in view": at the viewport's edge the dot can sit under the floating scroll-to-top button, which would take the click.
    await dot.evaluate((el) => el.scrollIntoView({ block: "center", inline: "center" }));
    const box = await dot.boundingBox();
    expect(box, "the dot of the chosen window is drawn").not.toBeNull();
    await page.mouse.move((box?.x ?? 0) + (box?.width ?? 0) / 2, (box?.y ?? 0) + (box?.height ?? 0) / 2);
    await page.mouse.down();
    await page.mouse.up();
    await expect(drawer).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`drill=point%3AdeliveredItems%3A${end}`));
    await expect(drawer.getByText(new RegExp(`^${escapeRe(figure)} items? ·`))).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);
    await expect(page).not.toHaveURL(/drill=/);
  });

  test("a regular user picks the person through a team: its current members are listed, the pick rides in the URL and survives a reload with the team", async ({ page }) => {
    // The shared fixture's team has no roster and is never mutated, so this test brings its own team with ONE stub person on it (the roster
    // membership goes before the team: a person belongs to one team at a time, globally).
    const adminApi = api;
    if (adminApi === undefined) throw new Error("the admin session of beforeAll is missing");
    const teamName = uniqueText("e2e-trend-team");
    const created = await adminApi.post("/api/v1/teams", { data: { name: teamName } });
    expect(created.status(), await created.text()).toBe(201);
    const teamId = ((await created.json()) as { id: number }).id;
    const cleanup = [`/api/v1/teams/${teamId}`];
    try {
      const member = await addStubMemberViaApi(adminApi, teamId);
      cleanup.unshift(`/api/v1/teams/${teamId}/jira-memberships/${member.membershipId}`);

      await login(page, reader.email, reader.password);
      await page.goto("/reports/person-trend");
      await expect(page.getByRole("combobox", { name: "Team", exact: true })).toHaveValue("");

      await page.getByRole("combobox", { name: "Team", exact: true }).click();
      await page.getByRole("option", { name: teamName, exact: true }).click();
      // Opening the person list shows the team's current members straight away (no typing): here exactly the one.
      await page.getByRole("combobox", { name: "Person", exact: true }).click();
      await expect(page.getByRole("option")).toHaveCount(1);
      await page.getByRole("option", { name: member.displayName, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`accountId=${member.accountId}$`));
      expect(page.url()).not.toContain("teamId");
      await expect(page.getByRole("combobox", { name: "Person", exact: true })).toHaveValue(member.displayName);
      await expect(page.getByRole("heading", { level: 3, name: "Cycle time, working days", exact: true })).toBeVisible();

      // The team is a picker aid remembered per viewer, and the person and the year are the screen's own memory: all three survive a reload.
      await page.reload();
      await expect(page.getByRole("combobox", { name: "Team", exact: true })).toHaveValue(teamName);
      await expect(page.getByRole("combobox", { name: "Person", exact: true })).toHaveValue(member.displayName);
      await expect(page).toHaveURL(new RegExp(`from=\\d{4}-\\d{2}-\\d{2}&to=\\d{4}-\\d{2}-\\d{2}&accountId=${member.accountId}$`));

      // Clearing the person returns to the empty state and leaves the team chosen.
      await page.getByLabel("Clear Person").click();
      await expect(page.getByText("Pick a person to see how their figures changed.")).toBeVisible();
      await expect(page.getByRole("combobox", { name: "Team", exact: true })).toHaveValue(teamName);
    } finally {
      await deleteViaApi(cleanup);
    }
  });
});
