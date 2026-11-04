// The 1.38.0 GitLab identity mapping: an admin opens the Identities page of a synced GitLab-stub source, confirms every proposal at once
// (6 Jira people and the bot), ignores the service account, links the commit author with no GitLab account to a Jira person by hand and
// removes that decision again. Owns: its uniquely named GitLab connection (`e2e-identities-*`) and the identity decisions of the stub host
// (`gitlab-stub:8080`, `public.code_identity_links`) - written only by this spec and `reports-code-activity` (they hold the same lock, so never together), cleared at its start and its end. It reads the
// shared Jira fixture through `sharedFixtureReady` (that is what puts the stub's 30 people in `norm.people`) and never mutates it. It
// holds the data-sources lock: that spec's GitLab test creates a source on the same stub host with the same groups, and the server refuses
// the overlap of two active sources with a 409 (a side effect: it also runs apart from `reports`, which holds every lock).
import {
  apiAsAdmin,
  clearGitLabDecisions,
  deleteViaApi,
  expect,
  login,
  REPORTS_EXCLUSIVE_LOCKS,
  rowOperation,
  sharedFixtureReady,
  sweepLeftoverGitLabSources,
  syncGitLabStubDataSourceViaApi,
  test,
} from "./helpers";

// Never beside `data-sources.spec.ts` (helpers.ts, `REPORTS_EXCLUSIVE_LOCKS`): both put a GitLab source with the same groups on the stub.
test.describe.configure({ lock: REPORTS_EXCLUSIVE_LOCKS.dataSources });

const OWN_PREFIX = "e2e-identities";

// GitLab usernames of the six proposals (`sample-data/gitlab/expected.json` `users.jiraAlignment.matches`) and the Jira stub's number
// for each: the page prints the Jira person's display name, "Sample User <n>".
const PROPOSALS: [username: string, jiraNumber: number][] = [
  ["sample.dev1", 1],
  ["sample.dev4", 4],
  ["sample.dev3", 3],
  ["sample.dev5", 5],
  ["sample.dev9", 9],
  ["sample.dev13", 13],
];
const BOT_USERNAME = "project_3_bot_4f1a9c2e7b";

test("admin reviews the GitLab stub's identities: confirms the proposals, ignores the service account, links the unmatched author and removes a decision", async ({ page }) => {
  test.setTimeout(600_000);
  const { api } = await apiAsAdmin();
  let dataSourceId: number | null = null;
  try {
    await sweepLeftoverGitLabSources();
    await sharedFixtureReady(api);
    dataSourceId = (await syncGitLabStubDataSourceViaApi(api, OWN_PREFIX)).id;
    await clearGitLabDecisions(dataSourceId);

    await login(page);
    const tab = (name: string) => page.getByRole("tab", { name, exact: true });
    const table = (name: string) => page.getByRole("table", { name, exact: true });
    const rowOf = (name: string, text: string) =>
      table(name).getByRole("row").filter({ has: page.getByText(text, { exact: true }) });
    // A toast is a role="alert" notification (a tab or a cell may carry the same word, e.g. "Linked").
    const toast = (message: string) => page.getByRole("alert").filter({ hasText: message });

    // Details page -> Identities: the Proposed tab is the default.
    await page.goto(`/data-sources/${dataSourceId}`);
    await page.getByRole("link", { name: "Identities", exact: true }).click();
    await expect(page).toHaveURL(/\/data-sources\/\d+\/identities$/);
    await expect(page.getByRole("heading", { level: 2, name: "Identities" })).toBeVisible();
    await expect(tab("Proposed")).toHaveAttribute("aria-selected", "true");

    // Seven proposals: the six users whose address matches a Jira person (one only through its confirmed secondary address) and the bot.
    await expect(table("Proposed").locator("tbody tr")).toHaveCount(7);
    for (const [username, jiraNumber] of PROPOSALS) {
      await expect(rowOf("Proposed", username).getByText(`Sample User ${jiraNumber}`, { exact: true })).toBeVisible();
    }
    await expect(rowOf("Proposed", "sample.dev3")).toContainText("sample.dev3@mail.example");
    await expect(rowOf("Proposed", BOT_USERNAME).getByText("Bot", { exact: true }).first()).toBeVisible();

    // Confirm all: asks first, then writes 6 links and 1 bot decision.
    await page.getByRole("button", { name: "Confirm all proposals (7)" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Confirm all proposals", exact: true }).click();
    await expect(toast("6 linked, 1 marked as bots")).toBeVisible();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByText("No proposals — every matching address is decided.")).toBeVisible();

    await tab("Linked").click();
    await expect(table("Linked").locator("tbody tr")).toHaveCount(6);
    await tab("Ignored & bots").click();
    await expect(table("Ignored & bots").locator("tbody tr")).toHaveCount(1);
    await expect(rowOf("Ignored & bots", BOT_USERNAME)).toBeVisible();

    // Unmapped: the admin service account (no Jira match) and the commit author with no GitLab account, whose address is shown in full.
    await tab("Unmapped").click();
    await expect(table("Unmapped").locator("tbody tr")).toHaveCount(2);
    await expect(rowOf("Unmapped", "flow-sync")).toBeVisible();
    await expect(table("Unmapped").getByRole("row").filter({ hasText: "Sample Contractor" })).toContainText("contractor.sample@mail.example");

    // Ignore the service account.
    await rowOperation(page, "Flow Sync (admin)", "Ignore");
    await expect(toast("Ignored")).toBeVisible();
    await expect(table("Unmapped").locator("tbody tr")).toHaveCount(1);
    await tab("Ignored & bots").click();
    await expect(table("Ignored & bots").locator("tbody tr")).toHaveCount(2);

    // Link the contractor to a Jira person from the whole site directory.
    await tab("Unmapped").click();
    await expect(table("Unmapped").locator("tbody tr")).toHaveCount(1);
    await rowOperation(page, "Sample Contractor", "Link to a Jira person…");
    const linkDialog = page.getByRole("dialog");
    const person = linkDialog.getByRole("combobox", { name: "Jira person" });
    await person.click();
    await person.pressSequentially("Sample User 2");
    // The listbox is portalled outside the dialog, so the option is found on the page.
    await page.getByRole("option", { name: /^Sample User 2 \(/ }).click();
    await linkDialog.getByRole("button", { name: "Save", exact: true }).click();
    await expect(toast("Linked")).toBeVisible();
    await expect(linkDialog).toHaveCount(0);

    await tab("Linked").click();
    await expect(table("Linked").locator("tbody tr")).toHaveCount(7);
    await expect(table("Linked").getByRole("row").filter({ hasText: "Sample Contractor" }).getByText("Sample User 2", { exact: true })).toBeVisible();

    // Remove that decision again: the contractor is unmapped once more.
    await rowOperation(page, "Sample Contractor", "Remove decision");
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();
    await expect(toast("Decision removed")).toBeVisible();
    await expect(table("Linked").locator("tbody tr")).toHaveCount(6);
    await tab("Unmapped").click();
    await expect(table("Unmapped").locator("tbody tr")).toHaveCount(1);
    await expect(table("Unmapped").getByRole("row").filter({ hasText: "Sample Contractor" })).toBeVisible();
  } finally {
    if (dataSourceId !== null) {
      // Decisions first (they are keyed by host and would outlive the connection), then the connection.
      await clearGitLabDecisions(dataSourceId).catch(() => undefined);
      await deleteViaApi([`/api/v1/data-sources/${dataSourceId}`]);
    }
    await api.dispose();
  }
});
