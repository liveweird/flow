import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { Menu } from "@mantine/core";
import RowActionsMenu from "./RowActionsMenu";
import { renderWithProviders, screen } from "../test/render";

describe("RowActionsMenu", () => {
  test("the kebab trigger carries the full accessible name and the menu wiring the e2e helper reads", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RowActionsMenu label="Operations for acquirer">
        <Menu.Item>Edit</Menu.Item>
        <Menu.Item color="red">Delete</Menu.Item>
      </RowActionsMenu>,
    );
    const trigger = screen.getByRole("button", { name: "Operations for acquirer" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveAttribute("aria-controls");
    expect(await screen.findByRole("menuitem", { name: "Edit" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Delete" })).toBeInTheDocument();
  });

  test("a loading trigger is disabled", () => {
    renderWithProviders(
      <RowActionsMenu label="Operations for x" loading>
        <Menu.Item>Edit</Menu.Item>
      </RowActionsMenu>,
    );
    expect(screen.getByRole("button", { name: "Operations for x" })).toHaveAttribute("data-loading");
  });
});
