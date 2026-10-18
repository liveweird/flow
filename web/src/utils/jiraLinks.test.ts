import { describe, expect, test } from "vitest";
import { jiraBrowseUrl, resolveJiraSite } from "./jiraLinks";

const STUB = { id: 1, name: "Stub", siteUrl: "https://stub.atlassian.net" };
const OTHER = { id: 2, name: "Other", siteUrl: "https://other.atlassian.net" };

describe("resolveJiraSite", () => {
  test("a connection id picks that connection's site; an unknown id or a blank site is unknown", () => {
    expect(resolveJiraSite([STUB, OTHER], 2)).toBe("https://other.atlassian.net");
    expect(resolveJiraSite([STUB, OTHER], 3)).toBeUndefined();
    expect(resolveJiraSite([{ ...STUB, siteUrl: "" }], 1)).toBeUndefined();
  });

  test("without a connection id the site is known only when every connection with a site shares it", () => {
    expect(resolveJiraSite([STUB])).toBe("https://stub.atlassian.net");
    expect(resolveJiraSite([STUB, { ...OTHER, siteUrl: "https://stub.atlassian.net/" }])).toBe("https://stub.atlassian.net");
    expect(resolveJiraSite([STUB, { id: 9, name: "No site", siteUrl: "" }])).toBe("https://stub.atlassian.net");
    expect(resolveJiraSite([STUB, OTHER])).toBeUndefined();
    expect(resolveJiraSite([])).toBeUndefined();
    expect(resolveJiraSite(undefined, 1)).toBeUndefined();
  });
});

describe("jiraBrowseUrl", () => {
  test("is {siteUrl}/browse/{KEY}, without a doubled slash, with the key encoded", () => {
    expect(jiraBrowseUrl("https://stub.atlassian.net", "FLO-1")).toBe("https://stub.atlassian.net/browse/FLO-1");
    expect(jiraBrowseUrl("https://stub.atlassian.net///", "FLO-1")).toBe("https://stub.atlassian.net/browse/FLO-1");
    expect(jiraBrowseUrl("https://stub.atlassian.net", "A B/C?d#e")).toBe("https://stub.atlassian.net/browse/A%20B%2FC%3Fd%23e");
  });

  test("only an https origin ever becomes a link", () => {
    expect(jiraBrowseUrl("javascript:alert(1)//", "FLO-1")).toBeUndefined();
    expect(jiraBrowseUrl("http://stub.atlassian.net", "FLO-1")).toBeUndefined();
    expect(jiraBrowseUrl("stub.atlassian.net", "FLO-1")).toBeUndefined();
    expect(resolveJiraSite([{ ...STUB, siteUrl: "http://stub.atlassian.net" }], 1)).toBeUndefined();
  });

  test("is undefined while the site is unknown", () => {
    expect(jiraBrowseUrl(undefined, "FLO-1")).toBeUndefined();
    expect(jiraBrowseUrl("  ", "FLO-1")).toBeUndefined();
  });
});
