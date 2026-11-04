import { describe, expect, test } from "vitest";
import GitLabKeyLink from "./GitLabKeyLink";
import { renderWithProviders, screen } from "../test/render";

const ONE = [{ connectionId: 7, name: "Stub", baseUrl: "http://gitlab-stub:8080", commitFilesPending: 0, mrDetailsPending: 0 }];
const TWO = [...ONE, { connectionId: 9, name: "Other", baseUrl: "http://gitlab-two:8080", commitFilesPending: 0, mrDetailsPending: 0 }];

describe("GitLabKeyLink", () => {
  test("a merge request key links to its page in a new tab, with rel noopener noreferrer and a hidden 'opens in GitLab'", () => {
    renderWithProviders(<GitLabKeyLink issueKey="grp/app!12" sources={ONE} />);
    const link = screen.getByRole("link", { name: "grp/app!12 (opens in GitLab)" });
    expect(link).toHaveAttribute("href", "http://gitlab-stub:8080/grp/app/-/merge_requests/12");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("a commit key links to the commit", () => {
    renderWithProviders(<GitLabKeyLink issueKey="grp/app@3f9a1c2b4d5e" sources={ONE} />);
    expect(screen.getByRole("link")).toHaveAttribute("href", "http://gitlab-stub:8080/grp/app/-/commit/3f9a1c2b4d5e");
  });

  test("with two different GitLab hosts, or a key that is no GitLab key, the key is plain text", () => {
    const { unmount } = renderWithProviders(<GitLabKeyLink issueKey="grp/app!12" sources={TWO} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("grp/app!12")).toBeInTheDocument();
    unmount();
    renderWithProviders(<GitLabKeyLink issueKey="FLO-1" sources={ONE} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("FLO-1")).toBeInTheDocument();
  });
});
