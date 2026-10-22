import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import FormErrorAlert from "./FormErrorAlert";

describe("FormErrorAlert", () => {
  test("is a red alert carrying the already-mapped message", () => {
    renderWithProviders(<FormErrorAlert title="Could not save">Name is taken</FormErrorAlert>);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Could not save");
    expect(alert).toHaveTextContent("Name is taken");
  });

  test("passes the close button through", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <FormErrorAlert onClose={onClose} withCloseButton closeButtonLabel="Dismiss">
        Failed
      </FormErrorAlert>,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "Dismiss" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
