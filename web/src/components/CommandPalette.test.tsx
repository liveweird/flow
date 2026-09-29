import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen } from "../test/render";
import CommandPalette from "./CommandPalette";
import { palette } from "../utils/commandPalette";

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function renderPalette() {
  return renderWithProviders(
    <>
      <CommandPalette />
      <Routes>
        <Route path="*" element={<Probe />} />
      </Routes>
    </>,
  );
}

describe("CommandPalette", () => {
  beforeEach(() => {
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    palette.close();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the header trigger opens the palette listing the session's pages", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getAllByRole("button", { name: "Search and jump to…" })[0]);
    expect(await screen.findByRole("button", { name: /Home/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Change password/ })).toBeInTheDocument();
    // Every report is its own palette entry, not only the sidebar's one Delivery leaf.
    for (const report of [
      "Delivery",
      "Velocity",
      "Throughput",
      "Sprint consistency",
      "Cycle time",
      "Estimation",
      "Task accuracy",
      "Epic accuracy",
      "Adjustments",
      "Reported time",
    ]) {
      expect(screen.getByRole("button", { name: new RegExp(`^${report}$`) })).toBeInTheDocument();
    }
    // A regular session never sees the admin pages.
    expect(screen.queryByRole("button", { name: /^Users$/ })).not.toBeInTheDocument();
  });

  test("an admin session's palette includes the Administration pages, and a pick navigates", async () => {
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Users/ }));
    expect(await screen.findByText("at /users")).toBeInTheDocument();
  });

  test("typing narrows the list to pages matching the folded query", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByRole("textbox"), "team");
    expect(await screen.findByRole("button", { name: /Teams/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Change password/ })).not.toBeInTheDocument();
  });
});
