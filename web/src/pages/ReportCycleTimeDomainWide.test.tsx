import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCycleTime from "./ReportCycleTime";
import { jsonResponse } from "../test/http";
import { openReportFilters } from "../test/reportFilters";
import { CYCLE_TIME, FILTERS, histogramItems } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the charts are inert probes.
vi.mock("@mantine/charts", () => ({
  BarChart: () => <div data-testid="bar-chart" />,
  LineChart: () => <div data-testid="line-chart" />,
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PREFIX = "/api/v1/reports/cycle-time?";
const ITEMS_PREFIX = "/api/v1/reports/cycle-time/items?";
const SHARED_TEAM_KEY = "flow.viewSettings.reports.teamId";

function serve(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS_PREFIX)) return Promise.resolve(jsonResponse(200, histogramItems(1)));
    if (url.startsWith(REPORT_PREFIX)) return Promise.resolve(jsonResponse(200, CYCLE_TIME));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/cycle-time"
        element={
          <>
            <ReportCycleTime />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const urls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(REPORT_PREFIX));
const where = () => screen.getByTestId("where");
const figures = () => screen.findByRole("heading", { name: "Working days" });
/** The report requests that name neither a team nor a domain: a read of the whole unit, which must never be sent. */
const wholeUnit = (mockFetch: FetchMock) => urls(mockFetch).filter((url) => !/[?&](teamId|domain)=/.test(url));

describe("ReportCycleTime page - a domain read across the whole unit", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("a team is clearable only once a domain is set; clearing it reads the domain alone and leaves the shared team as it was", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    renderPage("/reports/cycle-time?teamId=1");
    await figures();
    await openReportFilters(user);
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=1&domain=FLO$/));

    await user.click(screen.getByLabelText("Clear Team"));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?domain=FLO$/));
    await waitFor(() => expect(urls(mockFetch).at(-1)).toBe(`${REPORT_PREFIX}domain=FLO`));
    await figures();
    expect(screen.getByRole("combobox", { name: "Team" })).toHaveAttribute("placeholder", "No team: a domain is picked");
    expect(localStorage.getItem(SHARED_TEAM_KEY)).toBe("2");
    expect(wholeUnit(mockFetch)).toEqual([]);
  });

  test("a ?domain= link is read as it is, and no team is swapped in on a later render", async () => {
    serve(mockFetch);
    renderPage("/reports/cycle-time?domain=FLO");
    await figures();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(where()).toHaveTextContent(/^\?domain=FLO$/);
    expect(urls(mockFetch)).toEqual([`${REPORT_PREFIX}domain=FLO`]);
  });

  test("clearing the domain brings the remembered team back, never a read of the whole unit", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    renderPage("/reports/cycle-time?domain=FLO");
    await figures();
    await openReportFilters(user);
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=2$/));
    await waitFor(() => expect(urls(mockFetch).at(-1)).toBe(`${REPORT_PREFIX}teamId=2`));
    expect(wholeUnit(mockFetch)).toEqual([]);
  });

  test("a pasted member beside a domain with no team is not read, because no control would show it; beside a team it is", async () => {
    serve(mockFetch);
    const domainOnly = renderPage("/reports/cycle-time?domain=FLO&accountId=acc-ann");
    await figures();
    expect(urls(mockFetch)).toEqual([`${REPORT_PREFIX}domain=FLO`]);
    domainOnly.unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    renderPage("/reports/cycle-time?teamId=1&domain=FLO&accountId=acc-ann");
    await figures();
    expect(urls(mockFetch).at(-1)).toContain("accountId=acc-ann");
  });
});
