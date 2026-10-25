import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen } from "../test/render";
import { GENERATED_AT, historyFixture, MB, seriesFixture } from "../test/telemetryFixtures";
import type { TFunction } from "i18next";
import type { TelemetrySeries } from "../api/telemetry";
import { memoryColumns } from "../utils/telemetryTrend";
import TelemetryTrend from "./TelemetryTrend";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: { series: { label: string }[] }) => (
    <div data-testid="line-chart" data-labels={props.series.map((s) => s.label).join("|")} />
  ),
}));

function stubHistory(answer: (url: string) => Response) {
  const fetchMock = vi.fn((url: string) => Promise.resolve(answer(url)));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const memoryOf = (series: readonly TelemetrySeries[], t: TFunction) => memoryColumns(series, t, new Map());
const memory = [seriesFixture("mem.cgroup_working_set", [100 * MB, 200 * MB])];

describe("TelemetryTrend", () => {
  beforeEach(() => {
    localStorage.setItem("flow.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("asks for its group and range, draws the plotted lines and keeps every column's numbers in a table behind a disclosure", async () => {
    const fetchMock = stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", memory)));
    const user = userEvent.setup();
    renderWithProviders(<TelemetryTrend group="memory" range="24h" columns={memoryOf} label="Memory" />);
    expect((await screen.findByTestId("line-chart")).getAttribute("data-labels")).toBe("Working set (mean)");
    expect(screen.getByRole("group", { name: "Trend: Memory (UTC)" })).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe("/api/v1/telemetry/history?group=memory&range=24h");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show the figures" }));
    const table = screen.getByRole("table", { name: "Trend figures: Memory" });
    expect(table).toHaveTextContent("100 MB");
    expect(table).toHaveTextContent("300 MB");
    expect(table).toHaveTextContent("Time (UTC)");
    expect(table).toHaveTextContent("2026-10-24 10:00");
    // A full-range history says nothing about where it starts.
    expect(screen.queryByText(/History starts/)).not.toBeInTheDocument();
  });

  test("says when the history is shorter than the range, and when more lines matched than are listed", async () => {
    stubHistory(() =>
      jsonResponse(200, historyFixture("memory", "90d", memory, { historyFrom: GENERATED_AT - 3_600_000, from: GENERATED_AT - 90 * 86_400_000, truncated: true })),
    );
    renderWithProviders(<TelemetryTrend group="memory" range="90d" columns={memoryOf} label="Memory" />);
    expect(await screen.findByText("History starts 2026-10-24.")).toBeInTheDocument();
    expect(screen.getByText("More lines matched than are listed.")).toBeInTheDocument();
  });

  test("names the data sources the history left out, by name when the current answer has it and by id otherwise", async () => {
    stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", memory, { truncated: true, omittedConnectionIds: [5, 12] })));
    renderWithProviders(<TelemetryTrend group="memory" range="24h" columns={memoryOf} label="Memory" connections={[{ id: 5, name: "FLO" }]} />);
    expect(await screen.findByText(/Left out of the chart: FLO, #12\./)).toBeInTheDocument();
    // The generic truncation note is for the cases that dropped no data source.
    expect(screen.queryByText("More lines matched than are listed.")).not.toBeInTheDocument();
  });

  test("an inactive trend (its section collapsed) asks for nothing", async () => {
    const fetchMock = stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", memory)));
    renderWithProviders(<TelemetryTrend group="memory" range="24h" columns={memoryOf} label="Memory" active={false} />);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test("an answer without points is an empty state, not a blank chart", async () => {
    stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", [])));
    renderWithProviders(<TelemetryTrend group="memory" range="24h" columns={memoryOf} label="Memory" />);
    expect(await screen.findByText("No history for this range yet.")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("a failed load renders inline", async () => {
    stubHistory(() => jsonResponse(403, { title: "Forbidden" }));
    renderWithProviders(<TelemetryTrend group="memory" range="24h" columns={memoryOf} label="Memory" />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
