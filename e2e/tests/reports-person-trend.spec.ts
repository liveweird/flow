// Person trend (report 20, `.claude/docs/reports.md` "Report 20"): one person's Compare-people figures over rolling 90-day windows, across every
// team, read the way D12 says everyone reads reports — by a NON-ADMIN user. The admin side is the shared fixture (global setup's
// synced-and-derived, paused Jira-stub connection and the team its FLO board is mapped to), which this spec only READS; it owns a throwaway
// reader, created through the API before the block and deleted after it (plus, in the team-picker and team-reference tests, one throwaway team each with stub
// people on its roster, removed inside that test). The person is the stub member that finished the most tasks in the window, found through the
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
  windows: { end: string; from: string; coverage: "FULL" | "PARTIAL" | "NONE"; team?: { id: number; name: string } }[];
  rows: { key: string; points: { end: string; value?: number; n: number; hidden: boolean; reference?: { value?: number; peers: number } }[] }[];
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
/** A figure as the page writes a count (`formatMd`): at most two decimals, no trailing zeros. */
const figure = (value: number) => String(Math.round(value * 100) / 100);
/** The team-reference test's team: the followed person and this many peers. */
const PEERS_WANTED = 4;
/** The stub person metrics-config's spec puts on a team of its own (`SAMPLE_PERSON` there): kept off this spec's team too. */
const METRICS_CONFIG_PERSON = "5f8a1b2c3d4e5f6a7b8c9d01";
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
    await expect(titles).toHaveCount(17);
    await expect(titles.last()).toHaveText("Work mix");
    await expect(page.getByRole("group", { name: "Delivered (items)", exact: true }).locator(".recharts-surface").first()).toBeVisible();

    // Every chart says where it is going: an arrow and a word with the two window ends, or why there is no mark.
    const cues = page.locator('[data-testid^="cue-"]');
    await expect(cues).toHaveCount(17);
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
    // Columns are found by their header, not their position: another spec may put this person on a team meanwhile, which adds the Team and
    // team-median columns (the fourth test is about those).
    const headers = await table.getByRole("columnheader").allInnerTexts();
    const column = (name: string) => headers.indexOf(name);
    const cellsShown = await body.evaluateAll((rows) => rows.map((row) => Array.from(row.querySelectorAll("td")).map((cell) => cell.textContent ?? "")));
    const shown = cellsShown.map((cells) => ["Window ending", "Delivered (items)", "n", "Window"].map((name) => cells[column(name)]));
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

  test("a person on a team is read against the team median: the Team and team-median columns equal the API window by window, and the dotted line's legend entry appears exactly when the API has a median", async ({ page }) => {
    // The stub world has no roster, so this test brings its own team. A membership is global per person and other specs (4 workers) put the
    // `STUB_MEMBER_CANDIDATES` on teams of their own, so the followed person and PEERS_WANTED peers come from stub accounts OUTSIDE that list (and outside the
    // person metrics-config seeds), the most active first (the report's own finished-task count over the year). A 409 (someone else holds the account) moves on
    // to the next. Whether a window has 3 active peers is then up to the data, so the oracle is the API's own answer, not a fixed expectation.
    const adminApi = api;
    if (adminApi === undefined) throw new Error("the admin session of beforeAll is missing");
    const teamName = uniqueText("e2e-trend-ref-team");
    const created = await adminApi.post("/api/v1/teams", { data: { name: teamName } });
    expect(created.status(), await created.text()).toBe(201);
    const teamId = ((await created.json()) as { id: number }).id;
    const cleanup = [`/api/v1/teams/${teamId}`];
    try {
      const taken = new Set([...STUB_MEMBER_CANDIDATES.map((candidate) => candidate.accountId), METRICS_CONFIG_PERSON]);
      const listed = await adminApi.get("/api/v1/jira-users?pageSize=100");
      expect(listed.ok(), await listed.text()).toBeTruthy();
      const pool = ((await listed.json()) as { items: { accountId: string }[] }).items.filter((user) => !taken.has(user.accountId));
      const ranked: { accountId: string; finished: number }[] = [];
      for (const user of pool) {
        const points = (await trendOf(adminApi, user.accountId)).rows.find((row) => row.key === "deliveredItems")?.points ?? [];
        ranked.push({ accountId: user.accountId, finished: points.reduce((sum, point) => sum + (point.value ?? 0), 0) });
      }
      ranked.sort((a, b) => b.finished - a.finished);
      const members: string[] = [];
      for (const candidate of ranked.filter((entry) => entry.finished > 0)) {
        if (members.length === PEERS_WANTED + 1) break;
        const added = await adminApi.post(`/api/v1/teams/${teamId}/jira-memberships`, {
          data: { accountId: candidate.accountId, validFrom: Date.UTC(2025, 0, 1), validTo: null },
        });
        if (added.status() === 409) continue;
        expect(added.status(), await added.text()).toBe(201);
        cleanup.unshift(`/api/v1/teams/${teamId}/jira-memberships/${((await added.json()) as { id: number }).id}`);
        members.push(candidate.accountId);
      }
      test.skip(members.length < 4, `only ${members.length} stub accounts outside the other specs' pool are free and active: a team needs the person and 3 peers`);
      const followed = members[0];

      const body = await trendOf(adminApi, followed);
      const delivered = body.rows.find((row) => row.key === "deliveredItems");
      expect(delivered, "the API answers a deliveredItems row").toBeDefined();
      // The membership starts on 2025-01-01 and is open-ended: every window ending after it names the throwaway team.
      expect(body.windows.filter((window) => window.team?.name === teamName).length, "some window ends inside the membership").toBeGreaterThan(0);
      expect(body.windows.every((window) => window.team === undefined || window.team.name === teamName)).toBe(true);
      // A median is drawn where a window with data has a value (the client drops a window with nothing derived); the hint appears with ANY row's median.
      const hasMedian = (row: TrendBody["rows"][number] | undefined) => (row?.points ?? []).some((point, i) => body.windows[i]?.coverage !== "NONE" && point.reference?.value !== undefined);
      const anyMedian = body.rows.some(hasMedian);

      await login(page, reader.email, reader.password);
      await page.goto(`/reports/person-trend?connectionId=${dataSourceId}&${YEAR_WINDOW}&accountId=${followed}`);
      await expect(page.getByRole("heading", { level: 3, name: "Cycle time, working days", exact: true })).toBeVisible();
      await expect(page.getByText(/The dotted gray line is the team median: the median of the person's team-mates who were active in that window/)).toHaveCount(anyMedian ? 1 : 0);
      const chart = page.getByRole("group", { name: "Delivered (items)", exact: true });
      await expect(chart.locator(".recharts-surface").first()).toBeVisible();
      await expect(chart.getByText("Team median", { exact: true })).toHaveCount(hasMedian(delivered) ? 1 : 0);

      const card = page.getByRole("heading", { level: 3, name: "Delivered (items)", exact: true }).locator("xpath=ancestor::*[contains(@class,'mantine-Paper-root')][1]");
      await card.getByRole("button", { name: "Show the numbers" }).click();
      const table = card.getByRole("table", { name: "Delivered (items): figures by window", exact: true });
      await expect(table.getByRole("columnheader")).toHaveText(["Window ending", "Team", "Delivered (items)", "Team median (peers)", "n", "Window"]);
      const shown = await table.locator("tbody tr").evaluateAll((rows) => rows.map((row) => Array.from(row.querySelectorAll("td")).map((cell) => cell.textContent ?? "")));
      const expected = body.windows.map((window, index) => {
        const reference = delivered?.points[index]?.reference;
        const shownMedian = reference?.value === undefined ? "—" : figure(reference.value);
        const median = window.coverage === "NONE" || reference === undefined ? "—" : `${shownMedian} (${reference.peers})`;
        return [window.end, window.team?.name ?? "—", median];
      });
      // The Team and team-median columns, window by window (the figure, sample and coverage columns are the first test's).
      expect(shown.map((cells) => [cells[0], cells[1], cells[3]])).toEqual(expected);
    } finally {
      await deleteViaApi(cleanup);
    }
  });
});
