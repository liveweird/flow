import { describe, expect, test } from "vitest";
import CodeMembersTable from "./CodeMembersTable";
import { ANN, BOB, CODE_ACTIVITY, CODE_ACTIVITY_USERS } from "../test/codeActivityFixtures";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, within } from "../test/render";

describe("CodeMembersTable", () => {
  test("keeps the order it is given, whatever the volumes: no ranking by commits", () => {
    const busyLast = { ...CODE_ACTIVITY, groups: [BOB, ANN] };
    renderWithProviders(<CodeMembersTable report={busyLast} filters={FILTERS} />);
    const names = within(screen.getByRole("table", { name: "Activity by member" }))
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getAllByRole("cell")[0].textContent);
    expect(names).toEqual(["Bob Builder", "Ann Author"]);
  });

  test("a member's lines note says how many commits could not be counted, only when there are some", () => {
    renderWithProviders(<CodeMembersTable report={CODE_ACTIVITY} filters={FILTERS} />);
    expect(screen.getAllByText("(commits not counted: 2)")).toHaveLength(1);
  });

  test("names link to the member's own view at the team level and are plain text for picked members", () => {
    const { unmount } = renderWithProviders(<CodeMembersTable report={CODE_ACTIVITY} filters={FILTERS} />, { route: "/reports/code-activity?teamId=1" });
    expect(screen.getAllByRole("link")).toHaveLength(2);
    unmount();
    renderWithProviders(<CodeMembersTable report={CODE_ACTIVITY_USERS} filters={FILTERS} />, { route: "/reports/code-activity?teamId=1&accountId=acc-ann" });
    expect(screen.queryAllByRole("link")).toHaveLength(0);
    expect(screen.getByText("Ann Author")).toBeInTheDocument();
  });
});
