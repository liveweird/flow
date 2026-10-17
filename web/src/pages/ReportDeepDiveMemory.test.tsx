import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";
import ReportDeepDive from "./ReportDeepDive";

// recharts draws nothing under happy-dom.
vi.mock("@mantine/charts", () => ({ LineChart: () => <div data-testid="line-chart" /> }));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PATH = "/api/v1/reports/deep-dive";
const STORED = "flow.viewSettings.reports.deepDive.filter";

const REPORT = deepDiveReport({
  ...MONTH_CROSSING,
  mode: "EPICS",
  epics: [deepDiveEpic("FLO-1", { summary: "Onboarding" })],
  tasks: [deepDiveTask("FLO-11", { summary: "Sign-up form", epicKey: "FLO-1", planBasisMd: 2, planSource: "EARLIEST", totals: { pvMd: 2, evMd: 0, costMd: 0 } })],
});

function Shell() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <button type="button" onClick={() => navigate("/")}>
        away
      </button>
      <button type="button" onClick={() => navigate("/reports/deep-dive")}>
        back via nav
      </button>
      <output data-testid="where">{location.pathname + location.search}</output>
    </>
  );
}

function renderPage(route = "/reports/deep-dive") {
  return renderWithProviders(
    <>
      <Shell />
      <Routes>
        <Route path="/reports/deep-dive" element={<ReportDeepDive />} />
        <Route path="*" element={<p>elsewhere</p>} />
      </Routes>
    </>,
    { route },
  );
}

const where = () => screen.getByTestId("where").textContent;
const reportCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(`${REPORT_PATH}?`));
const remembered = () => JSON.parse(localStorage.getItem(STORED) ?? "null") as string | null;

describe("ReportDeepDive remembers the last shown selection", () => {
  let mockFetch: FetchMock;

  function serve(report: () => Response = () => jsonResponse(200, REPORT)) {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.startsWith(`${REPORT_PATH}?`)) return Promise.resolve(report());
      if (url.startsWith(`${REPORT_PATH}/epics?`)) {
        const items = [{ id: 1, connectionId: 1, key: "FLO-1", summary: "Onboarding", domain: "FLO" }];
        return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 100, total: 1 }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    serve();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  test("what Show displayed comes back on a bare visit: the matrix is requested and the URL says so", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("radio", { name: "Epics" }));
    await user.click(screen.getByRole("combobox", { name: "Epics" }));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(remembered()).toBe("epicId=FLO-1");

    await user.click(screen.getByRole("button", { name: "away" }));
    expect(await screen.findByText("elsewhere")).toBeInTheDocument();
    mockFetch.mockClear();
    await user.click(screen.getByRole("button", { name: "back via nav" }));
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    await waitFor(() => expect(where()).toBe("/reports/deep-dive?epicId=FLO-1"));
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
  });

  test("a link with a selection wins and does not overwrite the memory", async () => {
    localStorage.setItem(STORED, JSON.stringify("epicId=FLO-9"));
    renderPage("/reports/deep-dive?epicId=FLO-1");
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
    expect(remembered()).toBe("epicId=FLO-9");
  });

  test("the retired issueId in a stored value is dropped by the same parser a link goes through", async () => {
    localStorage.setItem(STORED, JSON.stringify("epicId=FLO-1&issueId=FLO-11"));
    renderPage();
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    await waitFor(() => expect(where()).toBe("/reports/deep-dive?epicId=FLO-1"));
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
    expect(screen.queryByText(/tasks view no longer exists/)).not.toBeInTheDocument();
  });

  test("a stored domain that is gone from the reference data leaves nothing to restore", async () => {
    localStorage.setItem(STORED, JSON.stringify("domain=GONE&sprintId=41"));
    renderPage();
    // The selection panel only mounts once the reference data is in — the moment a restore would have happened.
    expect(await screen.findByRole("button", { name: "Show" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Pick something to dive into" })).toBeInTheDocument();
    expect(where()).toBe("/reports/deep-dive");
    expect(reportCalls(mockFetch)).toEqual([]);
  });

  test("a stored selection the server refuses (an epic that is gone) is forgotten, with no error shown", async () => {
    serve(() => jsonResponse(400, { title: "Bad Request", status: 400, detail: "Unknown epic FLO-9." }));
    localStorage.setItem(STORED, JSON.stringify("epicId=FLO-9"));
    renderPage();
    await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-9`]));
    await waitFor(() => expect(remembered()).toBe(""));
    await waitFor(() => expect(where()).toBe("/reports/deep-dive"));
    expect(await screen.findByRole("heading", { level: 3, name: "Pick something to dive into" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("the same refusal for a selection from a link is shown, not forgotten", async () => {
    serve(() => jsonResponse(400, { title: "Bad Request", status: 400, detail: "Unknown epic FLO-9." }));
    localStorage.setItem(STORED, JSON.stringify("epicId=FLO-1"));
    renderPage("/reports/deep-dive?epicId=FLO-9");
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Unknown epic FLO-9."));
    expect(where()).toBe("/reports/deep-dive?epicId=FLO-9");
    expect(remembered()).toBe("epicId=FLO-1");
  });

  test("the open tab is remembered with the selection", async () => {
    localStorage.setItem(STORED, JSON.stringify("epicId=FLO-1&view=burnup"));
    renderPage();
    await waitFor(() => expect(where()).toBe("/reports/deep-dive?epicId=FLO-1&view=burnup"));
    expect(await screen.findByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "true");
  });
});
