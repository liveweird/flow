import { describe, expect, test } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import LegacyVelocityRedirect from "./LegacyVelocityRedirect";
import { renderWithProviders, screen } from "../test/render";

function Where() {
  const { pathname, search, hash } = useLocation();
  return <output data-testid="where">{pathname + search + hash}</output>;
}

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/velocity" element={<LegacyVelocityRedirect />} />
      <Route path="/reports/commitments" element={<Where />} />
    </Routes>,
    { route },
  );
}

describe("LegacyVelocityRedirect", () => {
  test("the old Report 1 address lands on Commitments, keeping the filters", () => {
    renderAt("/reports/velocity?teamId=7&lastSprints=6");
    expect(screen.getByTestId("where").textContent).toBe("/reports/commitments?teamId=7&lastSprints=6");
  });

  test("a bare old address and a hash survive too", () => {
    const { unmount } = renderAt("/reports/velocity");
    expect(screen.getByTestId("where").textContent).toBe("/reports/commitments");
    unmount();
    renderAt("/reports/velocity?teamId=7#chart");
    expect(screen.getByTestId("where").textContent).toBe("/reports/commitments?teamId=7#chart");
  });
});
