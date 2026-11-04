import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import CodeItemKey from "./CodeItemKey";
import type { ReportDrillItem } from "../api/reportDrill";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

const SOURCES = [{ connectionId: 7, name: "Stub", baseUrl: "http://gitlab-stub:8080", commitFilesPending: 0, mrDetailsPending: 0 }];

const MR: ReportDrillItem = {
  issueKey: "grp/app!12",
  summary: "FLO-3: add the thing",
  itemKind: "MERGE_REQUEST",
  accountId: null,
  assignee: null,
  teamId: 1,
  anchorAt: 1,
  figure: 2,
  linkedKeys: ["FLO-3", "PLT-14"],
};

function withJiraSite(): QueryClient {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["reports", "filters"], {
    ...FILTERS,
    connections: [{ id: 1, name: "Stub", siteUrl: "https://stub.atlassian.net/" }],
  });
  return queryClient;
}

describe("CodeItemKey", () => {
  test("a merge request shows its GitLab key, then each Jira key it names as a link to Jira in a new tab", () => {
    renderWithProviders(<CodeItemKey item={MR} sources={SOURCES} />, { queryClient: withJiraSite() });
    const links = screen.getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "http://gitlab-stub:8080/grp/app/-/merge_requests/12",
      "https://stub.atlassian.net/browse/FLO-3",
      "https://stub.atlassian.net/browse/PLT-14",
    ]);
    expect(screen.getByRole("link", { name: "FLO-3 (opens in Jira)" })).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getByRole("link", { name: "PLT-14 (opens in Jira)" })).toHaveAttribute("target", "_blank");
  });

  test("the tab order is the reading order: the GitLab key, then the Jira keys", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CodeItemKey item={MR} sources={SOURCES} />, { queryClient: withJiraSite() });
    await user.tab();
    expect(screen.getByRole("link", { name: "grp/app!12 (opens in GitLab)" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("link", { name: "FLO-3 (opens in Jira)" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("link", { name: "PLT-14 (opens in Jira)" })).toHaveFocus();
  });

  test("no Jira link for a null or empty linkedKeys: a commit, or a merge request that names nothing", () => {
    const { unmount } = renderWithProviders(<CodeItemKey item={{ ...MR, linkedKeys: null }} sources={SOURCES} />, {
      queryClient: withJiraSite(),
    });
    expect(screen.getAllByRole("link")).toHaveLength(1);
    unmount();
    const { unmount: second } = renderWithProviders(<CodeItemKey item={{ ...MR, linkedKeys: [] }} sources={SOURCES} />, {
      queryClient: withJiraSite(),
    });
    expect(screen.getAllByRole("link")).toHaveLength(1);
    second();
    renderWithProviders(<CodeItemKey item={{ ...MR, issueKey: "grp/app@3f9a1c2b4d5e", itemKind: "COMMIT", linkedKeys: undefined }} sources={SOURCES} />, {
      queryClient: withJiraSite(),
    });
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  test("without a known Jira site the keys are plain text, not links", () => {
    renderWithProviders(<CodeItemKey item={MR} sources={SOURCES} />);
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.getByText("FLO-3")).toBeInTheDocument();
    expect(screen.getByText("PLT-14")).toBeInTheDocument();
  });
});
