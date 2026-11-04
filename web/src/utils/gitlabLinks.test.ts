import { describe, expect, test } from "vitest";
import { gitlabItemUrl, resolveGitLabBase } from "./gitlabLinks";

describe("resolveGitLabBase", () => {
  test("one source is its base URL without a trailing slash; two sources on the same GitLab are that one", () => {
    expect(resolveGitLabBase([{ baseUrl: "https://git.example.test/" }])).toBe("https://git.example.test");
    expect(resolveGitLabBase([{ baseUrl: "http://gitlab-stub:8080" }, { baseUrl: "http://gitlab-stub:8080/" }])).toBe("http://gitlab-stub:8080");
  });

  test("a GitLab under a relative URL root keeps it, and a query, fragment or credentials never travel", () => {
    expect(resolveGitLabBase([{ baseUrl: "https://example.test/gitlab/?x=1#top" }])).toBe("https://example.test/gitlab");
    expect(resolveGitLabBase([{ baseUrl: "https://user:secret@example.test/" }])).toBeUndefined();
  });

  test("two different GitLab hosts are ambiguous, and so is no source at all", () => {
    expect(resolveGitLabBase([{ baseUrl: "https://a.example.test" }, { baseUrl: "https://b.example.test" }])).toBeUndefined();
    expect(resolveGitLabBase([])).toBeUndefined();
  });

  test.each(["javascript:alert(1)", "ftp://git.example.test", "not a url", ""])("%j is never a base to link from", (baseUrl) => {
    expect(resolveGitLabBase([{ baseUrl }])).toBeUndefined();
  });
});

describe("gitlabItemUrl", () => {
  const base = "https://git.example.test/gl";

  test("a merge request key is the merge request's page and a commit key the commit's", () => {
    expect(gitlabItemUrl(base, "grp/sub/app!12")).toBe("https://git.example.test/gl/grp/sub/app/-/merge_requests/12");
    expect(gitlabItemUrl(base, "grp/app@3f9a1c2b4d5e")).toBe("https://git.example.test/gl/grp/app/-/commit/3f9a1c2b4d5e");
  });

  test("each path segment is encoded, and a key that is neither shape has no link", () => {
    expect(gitlabItemUrl(base, "my.group/app_1!3")).toBe("https://git.example.test/gl/my.group/app_1/-/merge_requests/3");
    for (const key of ["FLO-1", "grp/app!x", "grp/app@XYZ", "grp/<b>!1", "!1", "grp/app!1/../..", "grp app!1", "#12!3", "#12@3f9a1c2b"]) {
      expect(gitlabItemUrl(base, key), key).toBeUndefined();
    }
  });

  test("a path that walks (a leading or inner `..` or `.` segment) has no link", () => {
    for (const key of ["../x!1", "grp/../x!1", "./x!1", "grp/./x@3f9a1c2b", "../../etc@abcd"]) {
      expect(gitlabItemUrl(base, key), key).toBeUndefined();
    }
  });

  test("an unknown base has no link", () => {
    expect(gitlabItemUrl(undefined, "grp/app!12")).toBeUndefined();
  });
});
