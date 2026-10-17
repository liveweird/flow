import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import ReportThroughput from "./ReportThroughput";
import { jsonResponse } from "../test/http";
import { FILTERS, THROUGHPUT_TEAM } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props.
vi.mock("@mantine/charts", () => {
  const probe = (kind: string) => () => <div data-testid="chart" data-kind={kind} />;
  return { BarChart: probe("bar"), AreaChart: probe("area"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;

function Shell() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <button type="button" onClick={() => navigate("/")}>
        away
      </button>
      <button type="button" onClick={() => navigate("/reports/throughput")}>
        back via nav
      </button>
      <div data-testid="where">{location.pathname + location.search}</div>
    </>
  );
}

function renderPage(route = "/reports/throughput") {
  return renderWithProviders(
    <>
      <Shell />
      <Routes>
        <Route path="/reports/throughput" element={<ReportThroughput />} />
        <Route path="*" element={<p>elsewhere</p>} />
      </Routes>
    </>,
    { route },
  );
}

const where = () => screen.getByTestId("where").textContent;
const requests = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/throughput"));

async function pick(user: ReturnType<typeof userEvent.setup>, combobox: string, option: string | RegExp) {
  await user.click(screen.getByRole("combobox", { name: combobox }));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("ReportThroughput remembers the filters a user set", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.startsWith("/api/v1/reports/throughput?")) return Promise.resolve(jsonResponse(200, THROUGHPUT_TEAM));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  test("the period, team, slice and chart view come back on a bare visit, and the request and the URL say so", async () => {
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1");
    await screen.findByRole("group", { name: "Chart: delivered scope by period" });
    await openReportFilters(user);
    await pick(user, "Period", "Last 3 sprints");
    await pick(user, "Team", "Beta");
    await pick(user, "Activity type", "Bug");
    await user.click(within(screen.getByRole("radiogroup", { name: "Chart view" })).getByText("Cumulative"));
    await waitFor(() => expect(where()).toBe("/reports/throughput?lastSprints=3&teamId=2&activityType=Bug&view=cumulative"));

    await user.click(screen.getByRole("button", { name: "away" }));
    expect(await screen.findByText("elsewhere")).toBeInTheDocument();
    mockFetch.mockClear();
    await user.click(screen.getByRole("button", { name: "back via nav" }));

    await waitFor(() => expect(where()).toBe("/reports/throughput?lastSprints=3&teamId=2&activityType=Bug&view=cumulative"));
    await screen.findByRole("group", { name: "Chart: delivered scope by period, cumulative" });
    await waitFor(() => expect(requests(mockFetch)).toEqual(["/api/v1/reports/throughput?lastSprints=3&teamId=2&activityType=Bug"]));
  });

  test("a deep link is taken as written and does not overwrite the memory", async () => {
    localStorage.setItem("flow.viewSettings.reports.throughput.filter", JSON.stringify("lastSprints=6&teamId=2"));
    renderPage("/reports/throughput?teamId=1&lastSprints=3");
    await screen.findByRole("group", { name: "Chart: delivered scope by period" });
    expect(where()).toBe("/reports/throughput?teamId=1&lastSprints=3");
    expect(JSON.parse(localStorage.getItem("flow.viewSettings.reports.throughput.filter") ?? "null")).toBe("lastSprints=6&teamId=2");
  });

  test("a remembered team that was deleted is dropped and the page starts on the required team", async () => {
    localStorage.setItem("flow.viewSettings.reports.throughput.filter", JSON.stringify("lastSprints=6&teamId=99&accountId=acc-ann"));
    renderPage();
    await waitFor(() => expect(where()).toBe("/reports/throughput?lastSprints=6&teamId=1"));
    await waitFor(() => expect(requests(mockFetch)).toEqual(["/api/v1/reports/throughput?lastSprints=6&teamId=1"]));
  });

  describe("a period chosen through a preset is re-resolved against today", () => {
    afterEach(() => vi.useRealTimers());

    test("days later the preset is today's range and shows as selected, not as a stale Custom range", async () => {
      const user = userEvent.setup();
      vi.useFakeTimers({ toFake: ["Date"], now: new Date("2026-03-10T12:00:00Z") });
      renderPage("/reports/throughput?teamId=1");
      await screen.findByRole("group", { name: "Chart: delivered scope by period" });
      await openReportFilters(user);
      await pick(user, "Period", "Last 30 days");
      await waitFor(() => expect(where()).toBe("/reports/throughput?from=2026-02-09&to=2026-03-10&teamId=1"));
      expect(JSON.parse(localStorage.getItem("flow.viewSettings.reports.throughput.filter") ?? "null")).toBe(
        "from=2026-02-09&to=2026-03-10&teamId=1&preset=last30",
      );

      await user.click(screen.getByRole("button", { name: "away" }));
      await screen.findByText("elsewhere");
      vi.setSystemTime(new Date("2026-03-15T12:00:00Z"));
      await user.click(screen.getByRole("button", { name: "back via nav" }));
      // Five days later: the same preset, resolved today — and the reserved key never reaches the URL.
      await waitFor(() => expect(where()).toBe("/reports/throughput?from=2026-02-14&to=2026-03-15&teamId=1"));
      await openReportFilters(user);
      expect((screen.getByRole("combobox", { name: "Period" }) as HTMLInputElement).value).toBe("Last 30 days");
    });

    test("a custom range comes back verbatim", async () => {
      vi.useFakeTimers({ toFake: ["Date"], now: new Date("2026-03-15T12:00:00Z") });
      localStorage.setItem("flow.viewSettings.reports.throughput.filter", JSON.stringify("from=2026-01-05&to=2026-01-20&teamId=2"));
      renderPage();
      await waitFor(() => expect(where()).toBe("/reports/throughput?from=2026-01-05&to=2026-01-20&teamId=2"));
    });
  });
});
