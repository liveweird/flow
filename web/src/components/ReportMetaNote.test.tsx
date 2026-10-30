import { describe, expect, test } from "vitest";
import ReportMetaNote from "./ReportMetaNote";
import i18n from "../i18n";
import { COMMITMENTS_UNIT } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

const META = COMMITMENTS_UNIT.meta;

describe("ReportMetaNote", () => {
  test("says when the numbers were derived and nothing about a horizon while it is off", () => {
    renderWithProviders(<ReportMetaNote meta={META} timeZone="Europe/Warsaw" />);
    expect(screen.getByText(/^Derived /)).toBeInTheDocument();
    expect(screen.queryByText(/history horizon/)).not.toBeInTheDocument();
  });

  test("names the first day the reports show and the horizon in months", () => {
    renderWithProviders(<ReportMetaNote meta={{ ...META, historyHorizon: { months: 24, from: "2024-10-30" } }} timeZone="Europe/Warsaw" />);
    expect(screen.getByText("Reports show work since 2024-10-30 (history horizon: 24 months)")).toBeInTheDocument();
  });

  test("the singular month, and the Polish plural forms", async () => {
    const { rerender } = renderWithProviders(
      <ReportMetaNote meta={{ ...META, historyHorizon: { months: 1, from: "2026-09-30" } }} timeZone="UTC" />,
    );
    expect(screen.getByText(/\(history horizon: 1 month\)/)).toBeInTheDocument();
    await i18n.changeLanguage("pl");
    try {
      rerender(<ReportMetaNote meta={{ ...META, historyHorizon: { months: 24, from: "2024-10-30" } }} timeZone="UTC" />);
      expect(screen.getByText("Raporty pokazują pracę od 2024-10-30 (horyzont historii: 24 miesiące)")).toBeInTheDocument();
      rerender(<ReportMetaNote meta={{ ...META, historyHorizon: { months: 6, from: "2026-04-30" } }} timeZone="UTC" />);
      expect(screen.getByText(/horyzont historii: 6 miesięcy\)/)).toBeInTheDocument();
    } finally {
      await i18n.changeLanguage("en");
    }
  });
});
