import { describe, expect, test } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import LegacyUsersRedirect from "./LegacyUsersRedirect";
import { renderWithProviders, screen } from "../test/render";

function Where() {
  const { pathname, search, hash } = useLocation();
  return <output data-testid="where">{pathname + search + hash}</output>;
}

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/users/*" element={<LegacyUsersRedirect />} />
      <Route path="/flow-users/*" element={<Where />} />
    </Routes>,
    { route },
  );
}

describe("LegacyUsersRedirect", () => {
  test.each([
    ["/users", "/flow-users"],
    ["/users/new", "/flow-users/new"],
    ["/users/7/edit", "/flow-users/7/edit"],
    ["/users/7/features", "/flow-users/7/features"],
    ["/users?name=ann#top", "/flow-users?name=ann#top"],
    // The router matches case-insensitively; the tail must survive too.
    ["/Users/7/edit", "/flow-users/7/edit"],
  ])("%s lands on %s", (from, to) => {
    renderAt(from);
    expect(screen.getByTestId("where").textContent).toBe(to);
  });
});
