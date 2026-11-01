// Code activity (report 21, 1.40.0; `.claude/docs/reports.md` "Report 21"): a team's merge-request flow over a synced GitLab-stub source and its members'
// volumes beside it, read the way D12 says everyone reads reports - by a NON-ADMIN user. Owns: its uniquely named GitLab connection (`e2e-code-*`), created and
// synced through the API, a throwaway team with ONE linked stub person on its roster, a throwaway reader, and the identity decisions of the stub host
// (`gitlab-stub:8080`, `public.code_identity_links`) - written only by this spec and `code-identities` (the same lock, so never together), cleared at its start and
// its end. It reads the shared Jira fixture through `sharedFixtureReady` (that is what puts the stub's 30 people in `norm.people`, which a roster membership needs)
// and never mutates it. The oracle for the page is the report's own API answer (the member's commits, the merged count, the weekly bars, the unlinked count).
import {
  apiAsAdmin,
  axeViolations,
  clearGitLabDecisions,
  deleteViaApi,
  expect,
  login,
  REPORTS_EXCLUSIVE_LOCKS,
  sharedFixtureReady,
  sweepLeftoverGitLabSources,
  syncGitLabStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

// Never beside `data-sources.spec.ts` or `code-identities.spec.ts` (helpers.ts, `REPORTS_EXCLUSIVE_LOCKS`): all put an ACTIVE GitLab source with the same groups on the stub host.
test.describe.configure({ lock: REPORTS_EXCLUSIVE_LOCKS.dataSources });

const OWN_PREFIX = "e2e-code";

/** Covers the stub's whole commit window (`sample-data/gitlab/expected.json` `generator.commitWindow`: 2026-03-05 .. 2026-08-31), whatever the zone and the stack's clock. */
const WINDOW = "from=2026-03-01&to=2026-09-30";

interface CodeActivityBody {
  flow: { merged: number; weekly: { bucketStart: string; merged: number }[] };
  sources: { name: string }[];
  quality: { unlinkedIdentities: number; commitsLinesUnknown: number; mrsSizeUnknown: number; mrsDetailsNotRead: number; eventsUndated: number };
  groups: { accountId?: string; label?: string; commits: number; mrsMerged: number }[];
}

/** A person's Jira account id in the stub (`5f8a1b2c3d4e5f6a7b8c9d<nn>`, `sample-data/jira/expected.json` `users.list[].jiraUserNumber`). */
const accountOf = (jiraNumber: number) => `5f8a1b2c3d4e5f6a7b8c9d${String(jiraNumber).padStart(2, "0")}`;

/**
 * The stub people the proposals link to a Jira person (`sample-data/gitlab/expected.json` `users.jiraAlignment.matches`), the ones no other spec draws a roster from
 * first (`STUB_MEMBER_CANDIDATES`, metrics-config's person 1): a membership is global per person, so a 409 moves on to the next.
 */
const LINKED_PEOPLE = [9, 3, 5, 13, 4, 1];

test("a regular user reads a team's code activity: the member's commits, the merge-request flow and the weekly bars equal the API, the unlinked identities are told, a clean axe scan", async ({ page }) => {
  // The shared fixture's SYNC and DERIVE (a no-op once another spec prepared it) plus this spec's own GitLab SYNC, each wait bounded.
  test.setTimeout(900_000);
  const { api } = await apiAsAdmin();
  let dataSourceId: number | null = null;
  let userId: number | null = null;
  const cleanup: string[] = [];
  try {
    await sweepLeftoverGitLabSources();
    await sharedFixtureReady(api);
    const source = await syncGitLabStubDataSourceViaApi(api, OWN_PREFIX);
    dataSourceId = source.id;
    await clearGitLabDecisions(dataSourceId);
    // The admin's decision: every proposal at once (6 Jira people and the bot), which is what links the stub people to their GitLab identities.
    const confirmed = await api.post(`/api/v1/data-sources/${dataSourceId}/code-identities/confirm-proposals`);
    expect(confirmed.ok(), await confirmed.text()).toBeTruthy();
    expect(((await confirmed.json()) as { linked: number }).linked).toBe(6);

    const readerName = uniqueText("e2e-code-reader");
    const reader = { email: `${readerName.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const createdUser = await api.post("/api/v1/flow-users", { data: { name: readerName, email: reader.email, password: reader.password, roles: [] } });
    expect(createdUser.status(), await createdUser.text()).toBe(201);
    userId = ((await createdUser.json()) as { id: number }).id;

    const createdTeam = await api.post("/api/v1/teams", { data: { name: uniqueText("e2e-code-team") } });
    expect(createdTeam.status(), await createdTeam.text()).toBe(201);
    const teamId = ((await createdTeam.json()) as { id: number }).id;
    cleanup.push(`/api/v1/teams/${teamId}`);
    // One LINKED person on the roster (the membership goes before the team): the first of the linked people not held by another team.
    let member: { membershipId: number; accountId: string; displayName: string } | undefined;
    for (const number of LINKED_PEOPLE) {
      const added = await api.post(`/api/v1/teams/${teamId}/jira-memberships`, { data: { accountId: accountOf(number), validFrom: Date.UTC(2025, 0, 1), validTo: null } });
      if (added.status() === 409) continue;
      expect(added.status(), await added.text()).toBe(201);
      member = { membershipId: ((await added.json()) as { id: number }).id, accountId: accountOf(number), displayName: `Sample User ${number}` };
      break;
    }
    if (member === undefined) throw new Error("every linked stub person already belongs to another team - clear the leftover memberships");
    cleanup.unshift(`/api/v1/teams/${teamId}/jira-memberships/${member.membershipId}`);

    // The oracle: the report's own answer for the team over the window, as the admin reads it.
    const answer = await api.get(`/api/v1/reports/code-activity?teamId=${teamId}&${WINDOW}`);
    expect(answer.ok(), await answer.text()).toBeTruthy();
    const report = (await answer.json()) as CodeActivityBody;
    const row = report.groups.find((group) => group.accountId === member?.accountId);
    expect(row, "the linked member has a row").toBeDefined();
    expect(row?.commits ?? 0, "the stub person committed inside the window").toBeGreaterThan(0);
    expect(report.sources.map((entry) => entry.name)).toEqual([source.name]);

    await login(page, reader.email, reader.password);
    await page.goto(`/reports/code-activity?teamId=${teamId}&${WINDOW}`);
    await expect(page.getByRole("heading", { level: 2, name: "Code activity", exact: true })).toBeVisible();
    for (const title of ["Merge request flow", "Merged per week", "Open merge requests now", "Activity by member"]) {
      await expect(page.getByRole("heading", { level: 3, name: title, exact: true })).toBeVisible();
    }
    // The URL keeps the team and the period as written, with no Jira slice or connection.
    await expect(page).toHaveURL(new RegExp(`teamId=${teamId}&from=2026-03-01&to=2026-09-30`));

    // The flow: the merged count equals the API's, the five distributions are there (a shown one or its withheld note), and the weekly bars sum to the same count.
    await expect(page.getByRole("group", { name: "Merged", exact: true }).getByText(String(report.flow.merged), { exact: true })).toBeVisible();
    for (const title of ["Cycle time, working days", "Cycle time, elapsed days", "Time to first review, working days", "Merge request size, lines", "Merge request size, files"]) {
      await expect(page.getByRole("heading", { level: 5, name: title, exact: true })).toBeVisible();
    }
    const weekly = page.getByRole("table", { name: "Merged per week, as a table", exact: true });
    if (report.flow.merged > 0) {
      await expect(page.getByRole("group", { name: "Chart: merge requests merged per week", exact: true }).locator(".recharts-surface").first()).toBeVisible();
      const shown = await weekly.locator("tbody tr").evaluateAll((rows) => rows.map((tr) => Array.from(tr.querySelectorAll("td")).map((cell) => cell.textContent ?? "")));
      expect(shown).toEqual(report.flow.weekly.map((week) => [week.bucketStart, String(week.merged)]));
    }

    // The member's row: the commits equal the API's, the neutral caption is there, and no cell of the table is a button (no ranking, no sort).
    const table = page.getByRole("table", { name: "Activity by member", exact: true });
    const headers = await table.getByRole("columnheader").allInnerTexts();
    const memberRow = table.getByRole("row").filter({ hasText: member.displayName });
    await expect(memberRow).toHaveCount(1);
    const cells = await memberRow.getByRole("cell").allInnerTexts();
    expect(cells[headers.indexOf("Commits")]).toBe(String(row?.commits));
    expect(cells[headers.indexOf("Merged")]).toBe(String(row?.mrsMerged));
    await expect(page.getByText(/Context, not performance: these are volumes of work, in no order of merit/)).toBeVisible();
    await expect(table.getByRole("button")).toHaveCount(0);

    // What is not attributed yet is told, to a regular user without a link to the admin's page: the stub's unmapped authors are undecided identities.
    // The note is raised by the page's own predicate: undecided identities OR any count of rows left out of a figure.
    const { quality } = report;
    const noted = quality.unlinkedIdentities > 0 || quality.commitsLinesUnknown > 0 || quality.mrsSizeUnknown > 0 || quality.mrsDetailsNotRead > 0 || quality.eventsUndated > 0;
    await expect(page.getByText("Still to attribute, or not yet known", { exact: true })).toHaveCount(noted ? 1 : 0);
    if (report.quality.unlinkedIdentities > 0) {
      await expect(page.getByText(`GitLab identities with activity in this period that no one has linked to a person yet: ${report.quality.unlinkedIdentities}.`)).toBeVisible();
    }
    await expect(page.getByRole("link", { name: /^Open the identities of/ })).toHaveCount(0);
    await expect(page.getByText(new RegExp(`^${source.name}: last synced`))).toBeVisible();

    // The pointer is parked off the page first: only the settled page is scanned.
    await page.mouse.move(0, 0);
    expect(await axeViolations(page)).toEqual([]);
  } finally {
    // Memberships and the team first, then the reader, then the decisions (keyed by host, they would outlive the connection) and the connection.
    try {
      await deleteViaApi(cleanup);
      if (userId !== null) await deleteViaApi([`/api/v1/flow-users/${userId}`]);
    } finally {
      if (dataSourceId !== null) {
        await clearGitLabDecisions(dataSourceId).catch(() => undefined);
        await deleteViaApi([`/api/v1/data-sources/${dataSourceId}`]);
      }
      await api.dispose();
    }
  }
});
