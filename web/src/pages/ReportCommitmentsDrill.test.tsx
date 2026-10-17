import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCommitments from "./ReportCommitments";
import { jsonResponse } from "../test/http";
import { COMMITMENTS_TEAM, COMMITMENTS_USERS, FILTERS, SPRINT_ITEMS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";
import { openReportFilters } from "../test/reportFilters";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props; every series gets a button
// that fires the `onDoubleClick` recharts would forward to its rectangles, with the first row as the payload.
vi.mock("@mantine/charts", () => {
  type BarProps = { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  const probe = (testId: string) => (props: { data: { label: string }[]; series: { name: string }[]; barProps?: unknown }) => (
    <div>
      <div data-testid={testId} data-labels={props.data.map((r) => r.label).join("|")} data-series={props.series.map((s) => s.name).join(",")} />
      {typeof props.barProps === "function" &&
        props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`dbl-${series.name}`}
            onClick={() => (props.barProps as (s: unknown) => BarProps)(series).onDoubleClick?.({ payload: props.data[0] }, 0, {})}
          />
        ))}
    </div>
  );
  return { CompositeChart: probe("composite-chart"), BarChart: probe("bar-chart"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;

function serve(mockFetch: FetchMock, items: (url: string) => Response = () => jsonResponse(200, SPRINT_ITEMS)) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/sprint-consistency/items?")) return Promise.resolve(items(url));
    if (url.startsWith("/api/v1/reports/commitments?")) {
      const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
      return Promise.resolve(jsonResponse(200, accounts > 1 ? COMMITMENTS_USERS : COMMITMENTS_TEAM));
    }
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
        path="/reports/commitments"
        element={
          <>
            <ReportCommitments />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const itemsCalls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/sprint-consistency/items?"));
const search = () => screen.getByTestId("where").textContent;
const dialog = (name: string) => screen.findByRole("dialog", { name });

describe("ReportCommitments page — drilling into a sprint's bar", () => {
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

  test("a sprint row's Initial and Final figures are buttons, MD and items alike; the committed one opens the sprint's drawer with the server's bar line and the URL", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1&lastSprints=6");
    expect(await screen.findByText("Double-click a bar to list the items behind it — or click a figure in the table.")).toBeInTheDocument();
    // Two figures (MD, items) stand behind each bar; the column in the name tells them apart, and the name starts with the visible figure.
    const committed = await screen.findByRole("button", { name: showItems("Alpha 2 · Committed", "Initial (MD)") });
    expect(committed).toHaveAccessibleName("20 — Initial (MD): show the items of Alpha 2 · Committed");
    expect(screen.getByRole("button", { name: showItems("Alpha 2 · Committed", "Initial (items)") })).toHaveAccessibleName(
      "8 — Initial (items): show the items of Alpha 2 · Committed",
    );
    expect(screen.getByRole("button", { name: showItems("Alpha 2 · Final", "Final (MD)") })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: showItems("Alpha 2 · Final", "Final (items)") })).toBeInTheDocument();
    // The Items column is gone: the figures are the way in.
    expect(screen.queryByRole("columnheader", { name: "Items" })).not.toBeInTheDocument();
    const button = committed;
    await user.click(button);

    const drawer = await dialog("Alpha 2 · Committed");
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
    // The same filter, its period narrowed to the ONE sprint, plus the bucket.
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=COMMITTED"]);
    expect(search()).toBe("?teamId=1&lastSprints=6&drill=sprint%3A1%3A12%3ACOMMITTED");
    const table = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Issue",
      "Summary",
      "Assignee",
      "Sprint completed",
      "Estimate (MD)",
    ]);
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[0].textContent).toBe("FLO-1Task 1Ann Author2026-09-105");
    expect(rows[7].textContent).toBe("FLO-8Task 8Bob Builder2026-09-10—");
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1&lastSprints=6");
    expect(button).toHaveFocus();
  });

  test("a chart double-click on the initial bar lists the committed items, on the final bar the final ones", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await user.click(await screen.findByTestId("dbl-initialMd"));
    await dialog("Alpha 1 · Committed");
    // The first plotted row is the earliest sprint (completion order).
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=11&teamId=1&bucket=COMMITTED"]);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByTestId("dbl-finalMd"));
    await dialog("Alpha 1 · Final");
    expect(itemsCalls(mockFetch).at(-1)).toBe("/api/v1/reports/sprint-consistency/items?sprintId=11&teamId=1&bucket=FINAL");
  });

  test("a stale count shows the changed-data note", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...SPRINT_ITEMS, bar: { ...SPRINT_ITEMS.bar, count: 9 } }));
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · Committed", "Initial (MD)") }));
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("with people picked a segment double-click narrows to that person, the share table has its own buttons and the select moves between people", async () => {
    const bob = { ...SPRINT_ITEMS, bar: { count: 3, sumMd: 8, from: null, to: null }, items: SPRINT_ITEMS.items.slice(0, 3), total: 3 };
    serve(mockFetch, (url) => jsonResponse(200, url.includes("segmentAccountId=acc-bob") ? bob : SPRINT_ITEMS));
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1&accountId=acc-ann&accountId=acc-bob");
    await user.click(await screen.findByTestId("dbl-c1"));

    const drawer = await dialog("Alpha 2 · Committed");
    expect(await within(drawer).findByText("3 items · 8 MD")).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([
      "/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&accountId=acc-ann&accountId=acc-bob&bucket=COMMITTED&segmentAccountId=acc-bob",
    ]);
    // Bob's committed share in the table is 3 items, the list says 3: no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(search()).toContain("drill=sprint%3A1%3A12%3ACOMMITTED%3Bs%3Dacc-bob");
    const select = within(drawer).getByRole("combobox", { name: "Show the items of" });
    expect(select).toHaveValue("2. Bob Builder");
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "Everyone" }));
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: showItems("Alpha 2 · 1. Ann Author · Final", "Final (MD)") }));
    await dialog("Alpha 2 · Final");
    expect(itemsCalls(mockFetch).at(-1)).toBe(
      "/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&accountId=acc-ann&accountId=acc-bob&bucket=FINAL&segmentAccountId=acc-ann",
    );
  });

  test("while a filter change shows the previous data the Initial and Final figures are plain text, not buttons", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("teamId=2")) return deferred;
      return Promise.resolve(jsonResponse(200, COMMITMENTS_TEAM));
    });
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await screen.findByRole("button", { name: showItems("Alpha 2 · Committed", "Initial (MD)") });
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /show the items of/ })).not.toBeInTheDocument());
    expect(screen.getAllByText("20").length).toBeGreaterThan(0);
    release?.(jsonResponse(200, COMMITMENTS_TEAM));
    await waitFor(() => expect(screen.getAllByRole("button", { name: /show the items of/ }).length).toBeGreaterThan(0));
  });

  test("a link carrying drill= opens the drawer", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1&drill=sprint:1:12:FINAL");
    const drawer = await dialog("Alpha 2 · Final");
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
  });

  test("a garbled drill= opens nothing", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1&drill=sprint:1:12:EVERYTHING");
    await screen.findByTestId("composite-chart");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);
  });

  test("a failed drill request is told inside the drawer, and the chart stays", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · Final", "Final (MD)") }));
    const drawer = await dialog("Alpha 2 · Final");
    expect(await within(drawer).findByRole("alert")).toBeInTheDocument();
    expect(await screen.findByTestId("composite-chart")).toBeInTheDocument();
  });
});
