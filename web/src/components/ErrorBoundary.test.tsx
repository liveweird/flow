import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import ErrorBoundary, { RouteErrorBoundary } from "./ErrorBoundary";

function Bomb(): never {
  throw new Error("boom");
}

describe("ErrorBoundary", () => {
  test("a render crash swaps to the fallback and the reload button reloads", async () => {
    // React logs the caught error — keep the test output quiet.
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const reload = vi.fn();
    vi.stubGlobal("location", { ...window.location, reload });
    try {
      renderWithProviders(
        <ErrorBoundary>
          <Bomb />
        </ErrorBoundary>,
      );
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
      await userEvent.setup().click(screen.getByRole("button", { name: "Reload" }));
      expect(reload).toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
      spy.mockRestore();
    }
  });

  test("renders children while nothing crashes", () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <div>content</div>
      </RouteErrorBoundary>,
    );
    expect(screen.getByText("content")).toBeInTheDocument();
  });
});
