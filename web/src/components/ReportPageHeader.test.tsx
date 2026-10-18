import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act } from "@testing-library/react";
import ReportPageHeader from "./ReportPageHeader";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

const summary = () => document.querySelector<HTMLElement>('[data-print="only"]')!;

describe("ReportPageHeader", () => {
  const originalPrint = window.print;

  beforeEach(() => {
    window.print = vi.fn();
  });

  afterEach(() => {
    window.print = originalPrint;
    vi.useRealTimers();
  });

  test("the Print / save as PDF button opens the print dialog", async () => {
    renderWithProviders(<ReportPageHeader title="Cycle time" />);
    expect(screen.getByRole("heading", { level: 2, name: "Cycle time" })).toBeInTheDocument();
    const button = screen.getByRole("button", { name: "Print / save as PDF" });
    expect(button).toHaveAttribute("data-print", "hide");
    await userEvent.setup().click(button);
    expect(window.print).toHaveBeenCalledTimes(1);
  });

  test("the print header names the report, the filter in words and the generation time in the configured zone", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.UTC(2026, 9, 18, 22, 30));
    renderWithProviders(
      <ReportPageHeader
        title="Cycle time"
        filter={{ from: "2026-07-01", to: "2026-09-30", teamId: 1, accountIds: ["acc-ann", "acc-bob"], domain: "FLO" }}
        filters={FILTERS}
        controls={{ domain: true, multiMember: true }}
      />,
    );
    expect(summary()).toHaveTextContent("Flow · Cycle time");
    expect(summary()).toHaveTextContent(
      "Filters: Period: 2026-07-01 → 2026-09-30 · Team: Alpha · Member: Ann Author, Bob Builder · Domain: Flow",
    );
    // 22:30Z is 00:30 the next day in Warsaw (CEST).
    expect(summary()).toHaveTextContent("Generated 2026-10-19 00:30 (Europe/Warsaw)");
  });

  test("an unfiltered report says its default period and the whole unit; a noPeriod report has no period", () => {
    const { unmount } = renderWithProviders(<ReportPageHeader title="Cycle time" filter={{}} filters={FILTERS} controls={{}} />);
    expect(summary()).toHaveTextContent(/Period: Last 90 days \(\d{4}-\d\d-\d\d → \d{4}-\d\d-\d\d\) · Team: All teams/);
    unmount();
    renderWithProviders(<ReportPageHeader title="Aging WIP" filter={{ teamId: 2 }} filters={FILTERS} controls={{ noPeriod: true }} />);
    expect(summary()).toHaveTextContent("Filters: Team: Beta");
    expect(summary()).not.toHaveTextContent("Period");
  });

  test("lines override the filter (the Deep dive's selection) and no filters means no filter line", () => {
    const { unmount } = renderWithProviders(<ReportPageHeader title="Deep dive" lines={[{ label: "Sprints", value: "Alpha 1" }]} />);
    expect(summary()).toHaveTextContent("Filters: Sprints: Alpha 1");
    unmount();
    renderWithProviders(<ReportPageHeader title="Deep dive" />);
    expect(summary()).not.toHaveTextContent("Filters");
    expect(summary()).toHaveTextContent("Generated");
  });

  test("the long-tail share and a page's own state ride along after the filter", () => {
    renderWithProviders(
      <ReportPageHeader
        title="Throughput"
        filter={{ teamId: 1, tailPercent: 10 }}
        filters={FILTERS}
        controls={{ noPeriod: true }}
        tail
        extraLines={[{ label: "Chart view", value: "Cumulative" }]}
      />,
    );
    expect(summary()).toHaveTextContent("Filters: Team: Alpha · Long tail: 10% · Chart view: Cumulative");
  });

  test("the stamp is taken when printing starts", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.UTC(2026, 0, 10, 8, 0));
    renderWithProviders(<ReportPageHeader title="Cycle time" filters={FILTERS} />);
    expect(summary()).toHaveTextContent("Generated 2026-01-10 09:00");
    vi.setSystemTime(Date.UTC(2026, 0, 10, 8, 45));
    act(() => {
      window.dispatchEvent(new Event("beforeprint"));
    });
    expect(summary()).toHaveTextContent("Generated 2026-01-10 09:45");
  });
});
