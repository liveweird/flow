import { afterEach, describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import Home from "./Home";
import { renderWithProviders } from "../test/render";

const ROLES_KEY = "flow.auth.roles";

describe("Home page", () => {
  afterEach(() => {
    localStorage.clear();
  });

  test("a regular user sees the plain empty state, with no data-sources link", () => {
    localStorage.setItem(ROLES_KEY, "[]");
    renderWithProviders(<Home />);
    expect(screen.getByText("No data source connected yet")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Add a data source" })).not.toBeInTheDocument();
  });

  test("an admin gets a link to /data-sources under the empty state", () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    renderWithProviders(<Home />);
    expect(screen.getByText("No data source connected yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add a data source" })).toHaveAttribute("href", "/data-sources");
  });
});
