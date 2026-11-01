import { describe, expect, test } from "vitest";
import { screen, within } from "@testing-library/react";
import GitLabProfileSections from "./GitLabProfileSections";
import { headingOutline } from "../test/headings";
import { renderWithProviders } from "../test/render";
import type { GitLabDataProfile } from "../api/dataSources";

const GITLAB: GitLabDataProfile = {
  projects: { live: 4, movedOut: 0, deleted: 0, archived: 1 },
  commits: {
    count: 281,
    merges: 19,
    earliestCommittedAt: null,
    latestCommittedAt: null,
    filesPending: 0,
    filesUnavailable: 0,
    filesCapped: 1,
    commitsWithCounts: 280,
  },
  mergeRequests: {
    byState: { locked: 2, opened: 16, merged: 34, closed: 8, weird: 1 },
    withDiffStats: 59,
    withDiffStatsPercent: 98.3,
    detailsPending: 0,
    detailsUnreadable: 0,
    withIssueKeyCandidates: 26,
    issueKeyCandidates: 28,
  },
  events: { byKind: { COMMENT: 269 } },
  identities: {
    users: 8,
    bots: 1,
    usersDeleted: 0,
    usersWithEmail: 8,
    usersWithEmailPercent: 100,
    ghosts: 0,
    emailOnly: 1,
    commitsUnmatched: 3,
  },
};

function rowLabels(table: string): string[] {
  return within(screen.getByRole("table", { name: table }))
    .getAllByRole("row")
    .map((row) => row.querySelector("th")?.textContent ?? "");
}

describe("GitLabProfileSections", () => {
  test("renders five named tables under h3 headings", () => {
    renderWithProviders(<GitLabProfileSections gitlab={GITLAB} />);
    expect(headingOutline()).toEqual([
      [3, "Projects"],
      [3, "Commits"],
      [3, "Merge requests"],
      [3, "Review events"],
      [3, "Identities"],
    ]);
    expect(screen.getAllByRole("table").every((table) => table.getAttribute("aria-label"))).toBe(true);
  });

  test("lists the merge request states in the fixed order, unknown ones last and verbatim", () => {
    renderWithProviders(<GitLabProfileSections gitlab={GITLAB} />);
    expect(rowLabels("Merge requests").slice(0, 5)).toEqual(["Merged", "Opened", "Closed", "Locked", "weird"]);
    expect(screen.getByText("59 (98.3%)")).toBeInTheDocument();
  });

  test("counts the issue-key candidates, saying they are not yet matched to Jira tasks", () => {
    renderWithProviders(<GitLabProfileSections gitlab={GITLAB} />);
    const mrs = within(screen.getByRole("table", { name: "Merge requests" }));
    expect(mrs.getByText("With Jira key candidates (not yet matched to tasks)").closest("tr")).toHaveTextContent("26");
    expect(mrs.getByText("Jira key candidates (not yet matched to tasks)").closest("tr")).toHaveTextContent("28");
  });

  test("zero-fills the review event kinds in the fixed order and shows a dash for no commit time", () => {
    renderWithProviders(<GitLabProfileSections gitlab={GITLAB} />);
    expect(rowLabels("Review events")).toEqual([
      "Comments",
      "Diff comments",
      "Approvals",
      "Review requests",
      "Reviews",
      "Changes requested",
    ]);
    const events = within(screen.getByRole("table", { name: "Review events" }));
    expect(events.getByText("Comments").closest("tr")).toHaveTextContent("269");
    expect(events.getByText("Reviews").closest("tr")).toHaveTextContent("0");
    const commits = within(screen.getByRole("table", { name: "Commits" }));
    expect(commits.getByText("Earliest commit").closest("tr")).toHaveTextContent("—");
    expect(commits.getByText("Latest commit").closest("tr")).toHaveTextContent("—");
  });
});
