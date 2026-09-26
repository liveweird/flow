import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import VersionStamp from "./VersionStamp";
import { APP_VERSION } from "../changelog/version";
import { renderWithProviders } from "../test/render";

describe("VersionStamp", () => {
  test("renders version, commit, and time as plain text without a link", () => {
    renderWithProviders(<VersionStamp />);
    const stamp = screen.getByTitle("Build version");
    expect(stamp.textContent).toMatch(/^v\S+ · \S+ · \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    expect(stamp.textContent!.startsWith(`v${APP_VERSION} · `)).toBe(true);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  test("`compact` shows only the version and moves the full stamp into the title", () => {
    renderWithProviders(<VersionStamp to="/changelog" compact />);
    const stamp = screen.getByRole("link");
    expect(stamp.textContent).toBe(`v${APP_VERSION}`);
    expect(stamp.getAttribute("title")).toMatch(new RegExp(`^v${APP_VERSION.replace(/\./g, "\\.")} · \\S+ · \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$`));
    expect(screen.queryByTitle("Build version")).not.toBeInTheDocument();
  });

  test("renders as a router link when `to` is set", () => {
    renderWithProviders(<VersionStamp to="/changelog" />);
    const stamp = screen.getByTitle("Build version");
    expect(stamp).toHaveAttribute("href", "/changelog");
  });
});
