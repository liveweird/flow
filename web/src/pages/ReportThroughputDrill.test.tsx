import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import ReportThroughput from "./ReportThroughput";
import { jsonResponse } from "../test/http";
import {
  FILTERS,
  THROUGHPUT_ITEMS,
  THROUGHPUT_MONTHS,
  THROUGHPUT_TEAM,
  THROUGHPUT_USER,
  THROUGHPUT_USERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props.
vi.mock("@mantine/charts", () => {
  type BarProps = (series: { name: string }) => { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  const probe = (kind: string) => (props: { data: { label: string }[]; series: { name: string }[]; type?: string; barProps?: BarProps }) => (
    <div>
      <div
        data-testid="chart"
        data-kind={kind}
        data-type={props.type ?? ""}
        data-labels={props.data.map((r) => r.label).join("|")}
        data-series={props.series.map((s) => s.name).join(",")}
        data-values={JSON.stringify(props.data.map((r) => (r as unknown as { md: number }).md))}
      />
      {typeof props.barProps === "function" &&
        props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`dbl-${series.name}`}
            onClick={() => props.barProps?.(series).onDoubleClick?.({ payload: props.data[0] }, 0, {})}
          />
        ))}
    </div>
  );
  return { BarChart: probe("bar"), AreaChart: probe("area"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;

const TEAM_MONTHS = { ...THROUGHPUT_TEAM, byBucket: THROUGHPUT_MONTHS.byBucket };

function reportFor(url: string) {
  const params = new URL(url, "http://x").searchParams;
  const accounts = params.getAll("accountId").length;
  if (accounts > 1) return THROUGHPUT_USERS;
  if (accounts === 1) return THROUGHPUT_USER;
  return params.get("bucket") === "MONTH" ? TEAM_MONTHS : THROUGHPUT_TEAM;
}

interface Serve {
  filters?: () => Response;
  report?: (url: string) => Response;
  items?: (url: string) => Response;
}

function serve(mockFetch: FetchMock, overrides: Serve = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(overrides.filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/throughput/items?")) return Promise.resolve(overrides.items?.(url) ?? jsonResponse(200, THROUGHPUT_ITEMS));
    if (url.startsWith("/api/v1/reports/throughput?")) return Promise.resolve(overrides.report?.(url) ?? jsonResponse(200, reportFor(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

/** The browser's Back button, for the history assertions. */
function BrowserBack() {
  const navigate = useNavigate();
  return <button onClick={() => navigate(-1)}>browser back</button>;
}

function renderPage(route = "/reports/throughput") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/throughput"
        element={
          <>
            <ReportThroughput />
            <Where />
            <BrowserBack />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/throughput"));

const NAMES = {
  period: "Chart: delivered scope by period",
  sprint: "Chart: delivered scope by sprint",
};
/** The probe inside a named chart group (waits for the lazy chunk). */
async function chartIn(name: string): Promise<HTMLElement> {
  return within(await screen.findByRole("group", { name })).getByTestId("chart");
}
const search = () => screen.getByTestId("where").textContent;

describe("ReportThroughput page — drilling into a bar", () => {
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

  describe("the drawer", () => {
    const itemsCalls = () => calls(mockFetch).filter((url) => url.startsWith("/api/v1/reports/throughput/items?"));
    const dialog = (name: string) => screen.findByRole("dialog", { name });

    test("a week's Delivered figure opens the drawer: the same filter plus the week, the server's bar line, the URL; Back restores the chart", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      // One hint under each of the two charts (period and sprint).
      expect(screen.getAllByText("Double-click a bar to list the items behind it — or click a figure in the table.")).toHaveLength(2);
      const button = screen.getByRole("button", { name: showItems("2026-09-07", "Delivered (MD)") });
      await user.click(button);

      const drawer = await dialog("Delivered · 2026-09-07");
      // The title is the dialog's heading, the bucket its dimmed subtitle; the only button of the pane's chrome is the X.
      expect(within(drawer).getByRole("heading", { level: 2 })).toHaveTextContent("Delivered · 2026-09-07");
      expect(within(drawer).queryByRole("button", { name: "Back to the chart" })).not.toBeInTheDocument();
      expect(await within(drawer).findByText("5 items · 12.5 MD")).toBeInTheDocument();
      expect(itemsCalls()).toEqual(["/api/v1/reports/throughput/items?teamId=1&bucketStart=2026-09-07"]);
      expect(search()).toBe("?teamId=1&drill=bucket%3A2026-09-07");
      const table = within(drawer).getByRole("table", { name: "Items behind the bar" });
      expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Issue", "Summary", "Assignee", "Done", "Delivered (MD)"]);
      const rows = within(table).getAllByRole("row").slice(1);
      expect(rows[0].textContent).toBe("FLO-1Build the login pageAnn Author2026-09-095");
      expect(rows[3].textContent).toBe("FLO-4Tidy the readmeUnassigned2026-09-091.5");
      expect(rows[4].textContent).toBe("FLO-5Spike the importerBob Builder2026-09-09—");
      expect(rows[2].textContent).toContain("—");
      expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();

      await user.click(within(drawer).getByRole("button", { name: "Close" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(search()).toBe("?teamId=1");
      expect(button).toHaveFocus();
      // The chart and its filter never moved: nothing was refetched.
      expect(calls(mockFetch).filter((url) => !url.includes("/items?"))).toEqual(["/api/v1/reports/throughput?teamId=1"]);
    });

    test("Escape and the browser's Back both close it, and the X is named", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      await user.click(screen.getByRole("button", { name: showItems("2026-09-21", "Delivered (MD)") }));
      const drawer = await dialog("Delivered · 2026-09-21");
      expect(within(drawer).getByRole("button", { name: "Close" })).toBeInTheDocument();
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(search()).toBe("?teamId=1");

      await user.click(screen.getByRole("button", { name: showItems("2026-09-21", "Delivered (MD)") }));
      await dialog("Delivered · 2026-09-21");
      await user.click(screen.getByRole("button", { name: "browser back" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(search()).toBe("?teamId=1");
    });

    test("a chart double-click opens the same drawer for that bar's week", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      const group = await screen.findByRole("group", { name: NAMES.period });
      await user.click(within(group).getByTestId("dbl-md"));
      await dialog("Delivered · 2026-09-07");
      expect(itemsCalls()).toEqual(["/api/v1/reports/throughput/items?teamId=1&bucketStart=2026-09-07"]);
    });

    test("a stale count shows the changed-data note", async () => {
      serve(mockFetch, { items: () => jsonResponse(200, { ...THROUGHPUT_ITEMS, bar: { ...THROUGHPUT_ITEMS.bar, count: 6 } }) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      await user.click(screen.getByRole("button", { name: showItems("2026-09-07", "Delivered (MD)") }));
      expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
    });

    test("with people picked a segment double-click narrows to that person and the drawer's select moves between them", async () => {
      const bob = {
        ...THROUGHPUT_ITEMS,
        bar: { count: 1, sumMd: 3, from: null, to: null },
        items: [THROUGHPUT_ITEMS.items[1]],
        total: 1,
      };
      serve(mockFetch, { items: (url) => jsonResponse(200, url.includes("segmentAccountId=acc-bob") ? bob : THROUGHPUT_ITEMS) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
      const group = await screen.findByRole("group", { name: "Chart: delivered scope by period, stacked by person" });
      await user.click(within(group).getByTestId("dbl-p1"));

      const drawer = await dialog("Delivered · 2026-09-07");
      expect(await within(drawer).findByText("1 item · 3 MD")).toBeInTheDocument();
      expect(itemsCalls()).toEqual([
        "/api/v1/reports/throughput/items?teamId=1&accountId=acc-ann&accountId=acc-bob&bucketStart=2026-09-07&segmentAccountId=acc-bob",
      ]);
      // Bob's share in the chart is 1 item, the list says 1: no note.
      expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
      expect(search()).toContain("drill=bucket%3A2026-09-07%3Bs%3Dacc-bob");
      const select = within(drawer).getByRole("combobox", { name: "Show the items of" });
      expect(select).toHaveValue("2. Bob Builder");

      await user.click(select);
      await user.click(await screen.findByRole("option", { name: "Everyone" }));
      expect(await within(drawer).findByText("5 items · 12.5 MD")).toBeInTheDocument();
      expect(search()).toMatch(/drill=bucket%3A2026-09-07$/);
      // The chart says 4 items for the week, the list 5: the data changed.
      expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
    });

    test("a person's figures in the share table open that person's share, and a whole bar has none for a person", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await chartIn("Chart: delivered scope by period, stacked by person");
      await user.click(screen.getByRole("button", { name: showItems("2026-09-21 · 2. Bob Builder", "Delivered (MD)") }));
      await dialog("Delivered · 2026-09-21");
      expect(itemsCalls()).toEqual([
        "/api/v1/reports/throughput/items?teamId=1&accountId=acc-ann&accountId=acc-bob&bucketStart=2026-09-21&segmentAccountId=acc-bob",
      ]);
    });

    test("the cumulative view keeps the rows' Delivered figures as buttons (the areas have no bars to double-click)", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&view=cumulative");
      const group = await screen.findByRole("group", { name: "Chart: delivered scope by period, cumulative" });
      expect(within(group).queryByTestId("dbl-md")).not.toBeInTheDocument();
      expect(screen.getAllByText("Click a figure in the table to list the items behind it.")).toHaveLength(2);
      // Delivered (MD) and Delivered (items) of a week are buttons; the running totals are not.
      const buttons = [
        screen.getByRole("button", { name: showItems("2026-09-21", "Delivered (MD)") }),
        screen.getByRole("button", { name: showItems("2026-09-21", "Delivered (items)") }),
      ];
      expect(buttons.map((button) => button.textContent)).toEqual(["15.5", "6"]);
      // The name starts with the visible figure (WCAG 2.5.3) and says which column it is.
      expect(buttons[0]).toHaveAccessibleName("15.5 — Delivered (MD): show the items of 2026-09-21");
      expect(buttons[1]).toHaveAccessibleName("6 — Delivered (items): show the items of 2026-09-21");
      // A week that delivered nothing has nothing to list: its zeros are plain text.
      expect(screen.queryByRole("button", { name: showItems("2026-09-14") })).not.toBeInTheDocument();
      await user.click(buttons[1]);
      await dialog("Delivered · 2026-09-21");
      expect(search()).toBe("?teamId=1&view=cumulative&drill=bucket%3A2026-09-21");
    });

    test("a link carrying drill= opens the drawer once the report is there, and the X closes it without leaving the page", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&drill=bucket:2026-09-07");
      const drawer = await dialog("Delivered · 2026-09-07");
      expect(await within(drawer).findByText("5 items · 12.5 MD")).toBeInTheDocument();
      // The drill is no filter: the report request never carries it.
      expect(calls(mockFetch).filter((url) => !url.includes("/items?"))).toEqual(["/api/v1/reports/throughput?teamId=1"]);
      await user.click(within(drawer).getByRole("button", { name: "Close" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(search()).toBe("?teamId=1");
    });

    test("a garbled drill= is ignored", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1&drill=nonsense");
      await chartIn(NAMES.period);
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(itemsCalls()).toEqual([]);
    });

    test("changing the filter closes an open drawer", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      await user.click(screen.getByRole("button", { name: showItems("2026-09-07", "Delivered (MD)") }));
      await dialog("Delivered · 2026-09-07");
      await openReportFilters(user);
      await user.click(screen.getByText("Month"));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(search()).toBe("?teamId=1&bucket=MONTH");
    });

    test("while a filter change shows the previous data the Delivered figures are plain text, not buttons", async () => {
      let release: ((response: Response) => void) | undefined;
      const deferred = new Promise<Response>((resolve) => {
        release = resolve;
      });
      serve(mockFetch, { report: (url) => (url.includes("bucket=MONTH") ? (deferred as unknown as Response) : jsonResponse(200, reportFor(url))) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      expect(screen.getByRole("button", { name: showItems("2026-09-07", "Delivered (MD)") })).toBeInTheDocument();
      await openReportFilters(user);
      await user.click(screen.getByText("Month"));
      await waitFor(() => expect(screen.queryByRole("button", { name: /show the items of/ })).not.toBeInTheDocument());
      // The kept rows still show their numbers, as text.
      expect(screen.getByText("12.5")).toBeInTheDocument();
      release?.(jsonResponse(200, reportFor("/api/v1/reports/throughput?teamId=1&bucket=MONTH")));
      await waitFor(() => expect(screen.getAllByRole("button", { name: /show the items of/ }).length).toBeGreaterThan(0));
    });

    test("a failed drill request is told inside the drawer, and the chart stays", async () => {
      serve(mockFetch, { items: () => jsonResponse(500, { title: "Internal Server Error", status: 500 }) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      await user.click(screen.getByRole("button", { name: showItems("2026-09-07", "Delivered (MD)") }));
      const drawer = await dialog("Delivered · 2026-09-07");
      expect(await within(drawer).findByRole("alert")).toBeInTheDocument();
      expect(screen.getByRole("group", { name: NAMES.period })).toBeInTheDocument();
    });
  });

  describe("the sprint chart", () => {
    const itemsCalls = () => calls(mockFetch).filter((url) => url.startsWith("/api/v1/reports/throughput/items?"));
    const dialog = (name: string) => screen.findByRole("dialog", { name });
    const FOUR = { ...THROUGHPUT_ITEMS, bar: { ...THROUGHPUT_ITEMS.bar, count: 4 }, items: THROUGHPUT_ITEMS.items.slice(0, 4), total: 4 };

    test("a sprint row's Delivered figure lists what that sprint delivered: the period narrowed to the one sprint, no bucketStart", async () => {
      serve(mockFetch, { items: () => jsonResponse(200, FOUR) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&lastSprints=6");
      await chartIn(NAMES.sprint);
      const button = screen.getByRole("button", { name: showItems("Alpha 1 · Delivered", "Delivered (MD)") });
      await user.click(button);

      const drawer = await dialog("Delivered · Alpha 1");
      expect(await within(drawer).findByText("4 items · 12.5 MD")).toBeInTheDocument();
      expect(itemsCalls()).toEqual(["/api/v1/reports/throughput/items?sprintId=11&teamId=1"]);
      expect(search()).toBe("?teamId=1&lastSprints=6&drill=sprint%3A1%3A11%3ADELIVERED");
      // Alpha 1 delivered 4 items in the chart, the list says 4: no note. The date column is the sprint's completion.
      expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
      const table = within(drawer).getByRole("table", { name: "Items behind the bar" });
      expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
        "Issue",
        "Summary",
        "Assignee",
        "Sprint completed",
        "Estimate (MD)",
      ]);

      await user.click(within(drawer).getByRole("button", { name: "Close" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(search()).toBe("?teamId=1&lastSprints=6");
      expect(button).toHaveFocus();
    });

    test("a double-click on the sprint chart's bar opens the same drawer, flagged as changed when the chart's count differs", async () => {
      serve(mockFetch, { items: () => jsonResponse(200, THROUGHPUT_ITEMS) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      const group = await chartIn(NAMES.sprint).then(() => screen.getByRole("group", { name: NAMES.sprint }));
      await user.click(within(group).getByTestId("dbl-md"));
      const drawer = await dialog("Delivered · Alpha 1");
      // The sprint's chart says 4 items, the list 5: the data changed.
      expect(await within(drawer).findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
      expect(itemsCalls()).toEqual(["/api/v1/reports/throughput/items?sprintId=11&teamId=1"]);
      // The sprint card is its own focus anchor, apart from the period card's.
      expect(document.querySelector('[data-drill-anchor="throughput-sprint"]')).toBeInTheDocument();
    });

    test("with people picked, a sprint segment and the sprint share table narrow to that person", async () => {
      serve(mockFetch, { items: () => jsonResponse(200, FOUR) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
      const group = await chartIn("Chart: delivered scope by sprint, stacked by person").then(() =>
        screen.getByRole("group", { name: "Chart: delivered scope by sprint, stacked by person" }),
      );
      await user.click(within(group).getByTestId("dbl-p1"));
      const drawer = await dialog("Delivered · Alpha 1");
      expect(search()).toContain("drill=sprint%3A1%3A11%3ADELIVERED%3Bs%3Dacc-bob");
      expect(itemsCalls()).toEqual([
        "/api/v1/reports/throughput/items?sprintId=11&teamId=1&accountId=acc-ann&accountId=acc-bob&segmentAccountId=acc-bob",
      ]);
      expect(within(drawer).getByRole("combobox", { name: "Show the items of" })).toHaveValue("2. Bob Builder");
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

      await user.click(screen.getByRole("button", { name: showItems("Alpha 1 · 1. Ann Author", "Delivered (MD)") }));
      await dialog("Delivered · Alpha 1");
      expect(itemsCalls().at(-1)).toBe(
        "/api/v1/reports/throughput/items?sprintId=11&teamId=1&accountId=acc-ann&accountId=acc-bob&segmentAccountId=acc-ann",
      );
    });

    test("the cumulative view keeps the sprint rows' Delivered figures as buttons", async () => {
      serve(mockFetch, { items: () => jsonResponse(200, FOUR) });
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&view=cumulative");
      await chartIn("Chart: delivered scope by sprint, cumulative");
      await user.click(screen.getByRole("button", { name: showItems("Alpha 2 · Delivered", "Delivered (MD)") }));
      await dialog("Delivered · Alpha 2");
      expect(itemsCalls()).toEqual(["/api/v1/reports/throughput/items?sprintId=12&teamId=1"]);
    });

    test("a link carrying a sprint drill= opens its drawer, and one with a period bucket opens only the period drawer", async () => {
      serve(mockFetch, { items: () => jsonResponse(200, FOUR) });
      renderPage("/reports/throughput?teamId=1&drill=sprint:1:12:DELIVERED");
      await dialog("Delivered · Alpha 2");
      expect(screen.getAllByRole("dialog")).toHaveLength(1);
    });
  });
});
