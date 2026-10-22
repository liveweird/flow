import { describe, expect, test } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import LegacyCostMatrixRedirect from "./LegacyCostMatrixRedirect";
import { renderWithProviders, screen } from "../test/render";

function Where() {
  const { pathname, search, hash } = useLocation();
  return <output data-testid="where">{pathname + search + hash}</output>;
}

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/cost-matrix/*" element={<LegacyCostMatrixRedirect />} />
      <Route path="/reports/costs/*" element={<Where />} />
    </Routes>,
    { route },
  );
}

describe("LegacyCostMatrixRedirect", () => {
  test.each([
    ["/reports/cost-matrix", "/reports/costs"],
    ["/reports/cost-matrix/", "/reports/costs"],
    ["/reports/cost-matrix?teamId=2&split=SPRINT#top", "/reports/costs?teamId=2&split=SPRINT#top"],
    ["/reports/cost-matrix?teamId=2&drill=cost%3AWORK_CATEGORY%3ARun", "/reports/costs?teamId=2&drill=cost%3AWORK_CATEGORY%3ARun"],
    // The router matches case-insensitively; a tail must survive too.
    ["/reports/Cost-Matrix", "/reports/costs"],
    ["/reports/COST-MATRIX/extra?x=1", "/reports/costs/extra?x=1"],
  ])("%s lands on %s", (from, to) => {
    renderAt(from);
    expect(screen.getByTestId("where").textContent).toBe(to);
  });
});
