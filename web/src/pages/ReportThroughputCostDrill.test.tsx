import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportThroughput from "./ReportThroughput";
import i18n from "../i18n";
import type { ReportDrillItems } from "../api/reportDrill";
import { jsonResponse } from "../test/http";
import { FILTERS, THROUGHPUT_MONTHS, THROUGHPUT_TEAM, THROUGHPUT_USERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props; every series gets a
// button that fires the `onDoubleClick` recharts would forward to its bars, with the first row as payload.
vi.mock("@mantine/charts", () => {
  type BarProps = (series: { name: string }) => { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  const probe = (kind: string) => (props: { data: { label: string }[]; series: { name: string }[]; barProps?: BarProps }) => (
    <div>
      <div data-testid="chart" data-kind={kind} data-series={props.series.map((s) => s.name).join(",")} />
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

const LOGGED_AT = Date.UTC(2026, 8, 9);
/** The tasks the actual cost of 2026-09-07 (13.5 MD) is logged on, as `measure=ACTUAL_COST` serves them: one row per task, `figure` the MD logged on it. */
const COST_ITEMS: ReportDrillItems = {
  meta: { ...THROUGHPUT_TEAM.meta },
  bar: { count: 3, sumMd: 13.5, from: null, to: null },
  items: [
    { issueKey: "FLO-1", summary: "Build the login page", itemKind: "TASK", accountId: null, assignee: "Ann Author, Bob Builder", teamId: null, anchorAt: LOGGED_AT, figure: 8 },
    { issueKey: "FLO-2", summary: "Fix the export", itemKind: "TASK", accountId: "acc-bob", assignee: "Bob Builder", teamId: null, anchorAt: LOGGED_AT, figure: 4 },
    { issueKey: "FLO-9", summary: null, itemKind: "TASK", accountId: null, assignee: null, teamId: null, anchorAt: LOGGED_AT, figure: 1.5 },
  ],
  total: 3,
  truncated: false,
};

function reportFor(url: string) {
  const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
  if (accounts > 1) return THROUGHPUT_USERS;
  return new URL(url, "http://x").searchParams.get("bucket") === "MONTH"
    ? { ...THROUGHPUT_TEAM, byBucket: THROUGHPUT_MONTHS.byBucket }
    : THROUGHPUT_TEAM;
}

function serve(mockFetch: FetchMock, items: (url: string) => Response = () => jsonResponse(200, COST_ITEMS)) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/throughput/items?")) return Promise.resolve(items(url));
    if (url.startsWith("/api/v1/reports/throughput?")) return Promise.resolve(jsonResponse(200, reportFor(url)));
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
        path="/reports/throughput"
        element={
          <>
            <ReportThroughput />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const itemsCalls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/throughput/items?"));
const search = () => screen.getByTestId("where").textContent;
const dialog = (name: string) => screen.findByRole("dialog", { name });
const PERIOD = "Chart: delivered scope by period";
const SPRINT = "Chart: delivered scope by sprint";
const chart = async (name: string) => within(await screen.findByRole("group", { name }));

describe("ReportThroughput page — drilling into the actual cost", () => {
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

  test("a week's Actual cost figure lists the tasks the time was logged on: measure=ACTUAL_COST, the drawer total is the figure, focus returns", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1");
    await chart(PERIOD);
    const button = screen.getByRole("button", { name: showItems("2026-09-07 · Actual cost", "Actual cost (MD)") });
    // The name starts with the visible figure (WCAG 2.5.3).
    expect(button).toHaveAccessibleName("13.5 — Actual cost (MD): show the items of 2026-09-07 · Actual cost");
    await user.click(button);

    const drawer = await dialog("Actual cost · 2026-09-07");
    expect(within(drawer).getByRole("heading", { level: 2 })).toHaveTextContent("Actual cost · 2026-09-07");
    // The server's own total (13.5 MD) is the table's figure; the chart says 13.5 too, so there is no changed-data note.
    expect(await within(drawer).findByText("3 items · 13.5 MD")).toBeInTheDocument();
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?teamId=1&bucketStart=2026-09-07&measure=ACTUAL_COST"]);
    expect(search()).toBe("?teamId=1&drill=ac-bucket%3A2026-09-07");
    const table = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Issue", "Summary", "Authors", "Last logged", "Logged (MD)"]);
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => r.textContent)).toEqual([
      "FLO-1Build the login pageAnn Author, Bob Builder2026-09-098",
      "FLO-2Fix the exportBob Builder2026-09-094",
      "FLO-9—No known author2026-09-091.5",
    ]);
    // The rows add up to the figure on the chart and in the table.
    expect(rows.reduce((sum, row) => sum + Number(row.lastChild?.textContent), 0)).toBe(13.5);

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
  });

  test("a recomputed total that differs from the chart's figure shows the changed-data note", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...COST_ITEMS, bar: { ...COST_ITEMS.bar, sumMd: 14 } }));
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1");
    await chart(PERIOD);
    await user.click(screen.getByRole("button", { name: showItems("2026-09-07 · Actual cost", "Actual cost (MD)") }));
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a week with no cost has nothing to list: its zero is plain text", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?teamId=1");
    await chart(PERIOD);
    expect(screen.queryByRole("button", { name: showItems("2026-09-14 · Actual cost", "Actual cost (MD)") })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: showItems("2026-09-21 · Actual cost", "Actual cost (MD)") })).toBeInTheDocument();
  });

  test("a double-click on the Actual cost bar opens the cost drawer; on the Delivered bar, the delivered one", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1");
    const period = await chart(PERIOD);
    await user.click(period.getByTestId("dbl-ac"));
    await dialog("Actual cost · 2026-09-07");
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?teamId=1&bucketStart=2026-09-07&measure=ACTUAL_COST"]);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(period.getByTestId("dbl-md"));
    await dialog("Delivered · 2026-09-07");
    expect(itemsCalls(mockFetch).at(-1)).toBe("/api/v1/reports/throughput/items?teamId=1&bucketStart=2026-09-07");
  });

  test("the cumulative view keeps the Actual cost figure a button, and adds the running cost as plain text", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1&view=cumulative");
    await chart("Chart: delivered scope by period, cumulative");
    expect(screen.queryByRole("button", { name: /Actual cost, cumulative/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: showItems("2026-09-21 · Actual cost", "Actual cost (MD)") }));
    await dialog("Actual cost · 2026-09-21");
    expect(search()).toBe("?teamId=1&view=cumulative&drill=ac-bucket%3A2026-09-21");
  });

  describe("the sprint chart", () => {
    test("a sprint row's Actual cost figure lists the tasks that sprint's time was logged on: the period narrowed to the sprint, no bucketStart", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&lastSprints=6");
      await chart(SPRINT);
      const button = screen.getByRole("button", { name: showItems("Alpha 1 · Actual cost", "Actual cost (MD)") });
      expect(button).toHaveTextContent("11");
      await user.click(button);

      const drawer = await dialog("Actual cost · Alpha 1");
      expect(await within(drawer).findByText("3 items · 13.5 MD")).toBeInTheDocument();
      expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?sprintId=11&teamId=1&measure=ACTUAL_COST"]);
      expect(search()).toBe("?teamId=1&lastSprints=6&drill=ac-sprint%3A1%3A11");
      // Alpha 1's chart says 11 MD, the list 13.5: the data changed.
      expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
      await user.click(within(drawer).getByRole("button", { name: "Close" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(button).toHaveFocus();
    });

    test("a double-click on the sprint chart's Actual cost bar opens it; the sprint card is the focus anchor", async () => {
      serve(mockFetch, () => jsonResponse(200, { ...COST_ITEMS, bar: { ...COST_ITEMS.bar, sumMd: 11 } }));
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await user.click((await chart(SPRINT)).getByTestId("dbl-ac"));
      const drawer = await dialog("Actual cost · Alpha 1");
      expect(await within(drawer).findByText("3 items · 11 MD")).toBeInTheDocument();
      expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
      expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?sprintId=11&teamId=1&measure=ACTUAL_COST"]);
      expect(document.querySelector('[data-drill-anchor="throughput-sprint"]')).toBeInTheDocument();
    });
  });

  describe("with people picked", () => {
    const ROUTE = "/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob";

    test("a person's cost segment narrows to the time THEY logged, and the drawer's select moves between people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(ROUTE);
      const period = await chart("Chart: delivered scope by period, stacked by person");
      await user.click(period.getByTestId("dbl-a1"));

      const drawer = await dialog("Actual cost · 2026-09-07");
      expect(itemsCalls(mockFetch)).toEqual([
        "/api/v1/reports/throughput/items?teamId=1&accountId=acc-ann&accountId=acc-bob&bucketStart=2026-09-07&measure=ACTUAL_COST&segmentAccountId=acc-bob",
      ]);
      expect(search()).toContain("drill=ac-bucket%3A2026-09-07%3Bs%3Dacc-bob");
      expect(within(drawer).getByRole("combobox", { name: "Show the items of" })).toHaveValue("2. Bob Builder");
    });

    test("a person's Actual cost figure in the share tables opens that person's share of the week and of the sprint", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(ROUTE);
      await chart("Chart: delivered scope by period, stacked by person");
      await user.click(screen.getByRole("button", { name: showItems("2026-09-21 · 2. Bob Builder · Actual cost", "Actual cost (MD)") }));
      await dialog("Actual cost · 2026-09-21");
      expect(itemsCalls(mockFetch).at(-1)).toBe(
        "/api/v1/reports/throughput/items?teamId=1&accountId=acc-ann&accountId=acc-bob&bucketStart=2026-09-21&measure=ACTUAL_COST&segmentAccountId=acc-bob",
      );
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

      await user.click(screen.getByRole("button", { name: showItems("Alpha 1 · 1. Ann Author · Actual cost", "Actual cost (MD)") }));
      await dialog("Actual cost · Alpha 1");
      expect(itemsCalls(mockFetch).at(-1)).toBe(
        "/api/v1/reports/throughput/items?sprintId=11&teamId=1&accountId=acc-ann&accountId=acc-bob&measure=ACTUAL_COST&segmentAccountId=acc-ann",
      );
    });
  });

  test("links open the cost drawers: a sprint one, a week one — and only that drawer, not the delivered one", async () => {
    serve(mockFetch);
    const first = renderPage("/reports/throughput?teamId=1&drill=ac-sprint:1:12");
    await dialog("Actual cost · Alpha 2");
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?sprintId=12&teamId=1&measure=ACTUAL_COST"]);
    first.unmount();

    renderPage("/reports/throughput?teamId=1&drill=ac-bucket:2026-09-07");
    await dialog("Actual cost · 2026-09-07");
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
  });

  test("a garbled cost drill= is ignored", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?teamId=1&drill=ac-bucket:soon");
    await chart(PERIOD);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);
  });

  test("in Polish the cost is 'Koszt rzeczywisty' in the legend entries, the column and the drawer", async () => {
    await i18n.changeLanguage("pl");
    try {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
      const group = await screen.findByRole("group", { name: "Wykres: dostarczony zakres według okresów, ułożony według osób" });
      expect(within(within(group).getByRole("list", { name: "Legenda wykresu" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
        "1. Ann Author",
        "2. Bob Builder",
        "Dostarczone (według osoby przypisanej)",
        "Koszt rzeczywisty (według osoby logującej czas)",
      ]);
      expect(screen.getAllByRole("columnheader", { name: "Koszt rzeczywisty (MD)" }).length).toBeGreaterThan(0);
      expect(screen.getAllByText(/Po wybraniu osób koszt rzeczywisty dzielimy według tego, kto zalogował czas/)).toHaveLength(2);
      await user.click(within(group).getByTestId("dbl-a0"));
      await screen.findByRole("dialog", { name: "Koszt rzeczywisty · 2026-09-07" });
    } finally {
      await i18n.changeLanguage("en");
    }
  });
});
