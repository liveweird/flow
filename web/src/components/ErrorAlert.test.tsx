import { describe, expect, test } from "vitest";
import { ApiError } from "../api/http";
import ErrorAlert from "./ErrorAlert";
import { renderWithProviders, screen } from "../test/render";

describe("ErrorAlert", () => {
  test("maps an API failure to its status text, never the raw error message", () => {
    renderWithProviders(<ErrorAlert error={new ApiError(503, null)} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Load failed (503)");
    expect(alert).not.toHaveTextContent("API 503");
  });

  test("shows the title when there is one", () => {
    renderWithProviders(<ErrorAlert error={new TypeError("Failed to fetch")} title="Could not load" />);
    expect(screen.getByRole("alert")).toHaveTextContent("Could not load");
    expect(screen.getByRole("alert")).not.toHaveTextContent("Failed to fetch");
  });
});
