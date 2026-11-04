import { describe, expect, test } from "vitest";
import TaskCodeItemKey from "./TaskCodeItemKey";
import type { ReportDrillItem } from "../api/reportDrill";
import { renderWithProviders, screen } from "../test/render";

const SOURCES = [{ connectionId: 7, name: "Stub", baseUrl: "http://gitlab-stub:8080", commitFilesPending: 0, mrDetailsPending: 0 }];

function item(linkedKeys: string[] | null, measuredKey: string | null | undefined): ReportDrillItem {
  return {
    issueKey: "FLO-12",
    summary: "A task",
    itemKind: "TASK",
    accountId: null,
    assignee: null,
    teamId: 1,
    anchorAt: 0,
    figure: 3,
    linkedKeys,
    measuredKey,
  };
}

describe("TaskCodeItemKey", () => {
  test("with several merge requests the measured one carries the 'last merge' mark, and only that one", () => {
    renderWithProviders(<TaskCodeItemKey item={item(["grp/app!2", "grp/app!10"], "grp/app!2")} sources={SOURCES} />);
    const marks = screen.getAllByText("last merge");
    expect(marks).toHaveLength(1);
    // The mark sits in the same element as the measured key's link, not the other's.
    const link = screen.getByRole("link", { name: "grp/app!2 (opens in GitLab)" });
    expect(link.parentElement).toContainElement(marks[0]);
    expect(screen.getByRole("link", { name: "grp/app!10 (opens in GitLab)" }).parentElement).not.toContainElement(marks[0]);
  });

  test("a single merge request is not marked", () => {
    renderWithProviders(<TaskCodeItemKey item={item(["grp/app!2"], "grp/app!2")} sources={SOURCES} />);
    expect(screen.queryByText("last merge")).not.toBeInTheDocument();
  });

  test("no measuredKey (null or absent), or one that is not listed, marks nothing", () => {
    const { unmount } = renderWithProviders(<TaskCodeItemKey item={item(["grp/app!2", "grp/app!10"], null)} sources={SOURCES} />);
    expect(screen.queryByText("last merge")).not.toBeInTheDocument();
    unmount();
    const second = renderWithProviders(<TaskCodeItemKey item={item(["grp/app!2", "grp/app!10"], undefined)} sources={SOURCES} />);
    expect(screen.queryByText("last merge")).not.toBeInTheDocument();
    second.unmount();
    renderWithProviders(<TaskCodeItemKey item={item(["grp/app!2", "grp/app!10"], "grp/app!99")} sources={SOURCES} />);
    expect(screen.queryByText("last merge")).not.toBeInTheDocument();
  });

  test("the same key listed twice (a mirror on two hosts) marks exactly the first", () => {
    renderWithProviders(<TaskCodeItemKey item={item(["grp/app!5", "grp/app!5"], "grp/app!5")} sources={SOURCES} />);
    const marks = screen.getAllByText("last merge");
    expect(marks).toHaveLength(1);
    const keys = screen.getAllByText("grp/app!5", { exact: false });
    expect(keys[0].closest("span")?.parentElement).toContainElement(marks[0]);
  });
});
