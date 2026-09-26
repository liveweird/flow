import { afterEach, describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import Changelog from "./Changelog";
import { CHANGELOG } from "../changelog/entries";
import { APP_VERSION } from "../changelog/version";
import i18n from "../i18n";
import { renderWithProviders } from "../test/render";

const STORAGE_KEY = "flow.changelog";

describe("Changelog page", () => {
  afterEach(async () => {
    await i18n.changeLanguage("en");
  });

  test("renders one timeline entry per release with version and date", () => {
    renderWithProviders(<Changelog />);
    for (const entry of CHANGELOG) {
      expect(screen.getByText(`v${entry.version}`)).toBeInTheDocument();
      expect(screen.getAllByText(entry.date).length).toBeGreaterThanOrEqual(1);
    }
  });

  test("shows the English bodies by default", () => {
    renderWithProviders(<Changelog />);
    expect(screen.getByRole("heading", { level: 2, name: "Changelog" })).toBeInTheDocument();
    // Plain-text run from the one entry (markdown splits formatted text into its own node).
    expect(
      screen.getByText(
        "First release: the Flow foundation — sign-in with email MFA, users, teams, feature flags, English and Polish, light and dark themes.",
      ),
    ).toBeInTheDocument();
  });

  test("shows the Polish bodies when the UI language is Polish", async () => {
    await i18n.changeLanguage("pl");
    renderWithProviders(<Changelog />);
    expect(screen.getByRole("heading", { level: 2, name: "Historia zmian" })).toBeInTheDocument();
    expect(
      screen.getByText(
        "Pierwsze wydanie: fundament Flow — logowanie z MFA e-mailem, użytkownicy/czki, zespoły, flagi funkcji, język angielski i polski, motyw jasny i ciemny.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(
        "First release: the Flow foundation — sign-in with email MFA, users, teams, feature flags, English and Polish, light and dark themes.",
      ),
    ).not.toBeInTheDocument();
  });

  test("marks the current version as seen on mount", () => {
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    renderWithProviders(<Changelog />);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ seenVersion: APP_VERSION });
  });

  test("tolerates corrupt stored state and still marks seen", () => {
    localStorage.setItem(STORAGE_KEY, "{not valid json");
    renderWithProviders(<Changelog />);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ seenVersion: APP_VERSION });
  });
});
