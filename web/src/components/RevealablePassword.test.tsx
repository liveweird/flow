import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import RevealablePassword from "./RevealablePassword";
import { renderWithProviders } from "../test/render";

const PASSWORD = "s3cret-Pass_0";

describe("RevealablePassword", () => {
  test("masks the password by default", () => {
    renderWithProviders(<RevealablePassword password={PASSWORD} copyLabel="Copy password" />);
    expect(screen.getByText("*".repeat(PASSWORD.length))).toBeInTheDocument();
    expect(screen.queryByText(PASSWORD)).not.toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "Show password" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
  });

  test("the eye toggle reveals the plaintext and hides it again", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RevealablePassword password={PASSWORD} copyLabel="Copy password" />);

    await user.click(screen.getByRole("button", { name: "Show password" }));
    expect(screen.getByText(PASSWORD)).toBeInTheDocument();
    const hide = screen.getByRole("button", { name: "Hide password" });
    expect(hide).toHaveAttribute("aria-pressed", "true");

    await user.click(hide);
    expect(screen.queryByText(PASSWORD)).not.toBeInTheDocument();
    expect(screen.getByText("*".repeat(PASSWORD.length))).toBeInTheDocument();
  });

  test("offers the copy button under the passed copyLabel", () => {
    renderWithProviders(<RevealablePassword password={PASSWORD} copyLabel="Copy password" />);
    expect(screen.getByRole("button", { name: "Copy password" })).toBeInTheDocument();
  });
});
