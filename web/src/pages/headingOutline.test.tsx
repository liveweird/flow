import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import App from "../App";
import appSource from "../App.tsx?raw";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

// The heading-outline guard for EVERY page the router registers (web/CLAUDE.md "One heading outline
// everywhere"): the PageHeader title is the h2, sections h3, inner blocks h4. Rendered through the
// real <App/> routes under an ADMIN session with an empty-everything API, so it pins the structure
// every page keeps whatever it loads (the per-page tests pin the loaded outlines).

/** Route patterns → a concrete path the page renders at. */
function concretePath(pattern: string): string {
  return pattern.replace(":id", "1");
}

const ROUTE_PATTERNS = [...appSource.matchAll(/<Route\s+(?:index|path="([^"]+)")/g)]
  .map((m) => m[1] ?? "")
  .filter((p) => p !== "*");

// Pages outside the shell (the auth cards) render signed OUT; the rest need a session.
const SIGNED_OUT = new Set(["/login", "/reset-password"]);

function emptyApi() {
  return vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (/\/api\/v1\/(users|teams|data-sources)(\?|$)/.test(url)) {
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
}

describe("heading outline of every registered page", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", emptyApi());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("the route list was read from App.tsx", () => {
    expect(ROUTE_PATTERNS.length).toBeGreaterThanOrEqual(34);
  });

  test.each(ROUTE_PATTERNS.map((p) => [p === "" ? "/" : `/${p.replace(/^\//, "")}`] as const))(
    "%s starts at h2 and never skips a level",
    async (pattern) => {
      if (!SIGNED_OUT.has(pattern)) {
        localStorage.setItem("flow.auth.token", "fake-token");
        localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem("flow.auth.userId", "1");
      }
      renderWithProviders(<App />, { route: concretePath(pattern) });
      // Settled: a heading exists and nothing is still loading, so late sections are in the outline too.
      await waitFor(() => {
        expect(screen.getAllByRole("heading").length).toBeGreaterThan(0);
        expect(document.querySelector('[aria-busy="true"], .mantine-Loader-root')).toBeNull();
      });
      const outline = headingOutline();
      expect(outline[0][0], JSON.stringify(outline)).toBe(2);
      outline.forEach(([level], i) => {
        if (i > 0) expect(level, `${outline[i][1]} in ${JSON.stringify(outline)}`).toBeLessThanOrEqual(outline[i - 1][0] + 1);
      });
    },
  );
});
