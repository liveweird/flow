import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { AppShell } from "@mantine/core";
import type { TelemetryCurrent } from "../api/telemetry";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen, within } from "../test/render";
import { currentFixture, historyFixture, MB, processFixture, seriesFixture } from "../test/telemetryFixtures";
import TelemetryPanel from "./TelemetryPanel";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: { series: { label: string }[] }) => (
    <div data-testid="line-chart" data-labels={props.series.map((s) => s.label).join("|")} />
  ),
}));

function stubApi(current: TelemetryCurrent = currentFixture()) {
  const fetchMock = vi.fn((url: string) => {
    if (url.startsWith("/api/v1/telemetry/current")) return Promise.resolve(jsonResponse(200, current));
    const query = new URL(url, "http://x").searchParams;
    const group = query.get("group") as "memory";
    const range = query.get("range") as "24h";
    return Promise.resolve(jsonResponse(200, historyFixture(group, range, [seriesFixture("mem.cgroup_working_set", [100 * MB, 200 * MB])])));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const calls = (fetchMock: ReturnType<typeof stubApi>, path: string) =>
  fetchMock.mock.calls.map((call) => String(call[0])).filter((url) => url.startsWith(path));

function renderPanel(onClose = () => {}, queryClient?: QueryClient) {
  return renderWithProviders(
    <AppShell aside={{ width: 420, breakpoint: "sm" }}>
      <AppShell.Aside>
        <TelemetryPanel onClose={onClose} />
      </AppShell.Aside>
    </AppShell>,
    { queryClient },
  );
}

const section = (name: string) => screen.getByRole("button", { name });

describe("TelemetryPanel", () => {
  beforeEach(() => {
    localStorage.setItem("flow.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    localStorage.clear();
  });

  test("shows the title, when the newest sample was taken, a close button and the six sections with Memory open", async () => {
    stubApi();
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderPanel(onClose);
    expect(await screen.findByRole("heading", { level: 2, name: "Telemetry" })).toBeInTheDocument();
    // The header (the newest sample of any process) and the web process's own heading.
    expect(await screen.findAllByText("Sampled at 2026-10-24 11:55 UTC")).toHaveLength(2);
    for (const name of ["Memory", "Storage", "Sync", "DERIVE", "Requests", "JVM"]) {
      expect(section(name)).toBeInTheDocument();
    }
    expect(section("Memory")).toHaveAttribute("aria-expanded", "true");
    expect(section("Storage")).toHaveAttribute("aria-expanded", "false");
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test("Memory prints each process's figures with the share of its limit, and TEXT badges from 80 % (High) and 90 % (Critical)", async () => {
    stubApi();
    renderPanel();
    const web = await screen.findByRole("table", { name: "Memory of flow-web-1" });
    expect(within(web).getByText("768 MB")).toBeInTheDocument();
    expect(within(web).getByText("640 MB")).toBeInTheDocument();
    expect(within(web).getByText("83.3%")).toBeInTheDocument();
    expect(within(web).getByText("High")).toBeInTheDocument();
    expect(within(web).queryByText("Critical")).not.toBeInTheDocument();
    const worker = screen.getByRole("table", { name: "Memory of flow-worker-1" });
    expect(within(worker).getByText("92.1%")).toBeInTheDocument();
    expect(within(worker).getByText("Critical")).toBeInTheDocument();
  });

  test("a process below 80 % has no badge; a stale one is flagged by text, in its heading and as an alert", async () => {
    stubApi(currentFixture({ processes: [processFixture({ memory: { ...processFixture().memory, percentOfLimit: 79.9 } }), processFixture({ id: 2, role: "worker", instance: "flow-worker-1", stale: true })] }));
    renderPanel();
    const web = await screen.findByRole("table", { name: "Memory of flow-web-1" });
    expect(within(web).queryByText("High")).not.toBeInTheDocument();
    expect(within(web).queryByText("Critical")).not.toBeInTheDocument();
    expect(screen.getByText("Stale")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Worker has stopped reporting");
    expect(screen.getByRole("alert")).toHaveTextContent("flow-worker-1 has sent no sample for more than three sample intervals");
  });

  test("outside a container there is no limit and the panel says so", async () => {
    const base = processFixture();
    stubApi(currentFixture({ processes: [processFixture({ memory: { ...base.memory, containerLimitBytes: null, percentOfLimit: null, workingSetBytes: null } })] }));
    renderPanel();
    const table = await screen.findByRole("table", { name: "Memory of flow-web-1" });
    expect(within(table).getByText("No limit")).toBeInTheDocument();
    expect(screen.getByText("Not running in a container: no limit, so no share of it either.")).toBeInTheDocument();
  });

  test("before the first sample the process sections say when it arrives", async () => {
    stubApi(currentFixture({ processes: [processFixture({ sampledAt: null })] }));
    renderPanel();
    expect(await screen.findByText("No sample yet: the first one arrives within 5 min of a process starting.")).toBeInTheDocument();
    expect(screen.getByText("No sample yet")).toBeInTheDocument();
    expect(screen.queryByText(/^Sampled at/)).not.toBeInTheDocument();
  });

  test("with no process at all, Memory and JVM show the same note", async () => {
    stubApi(currentFixture({ processes: [] }));
    const user = userEvent.setup();
    renderPanel();
    expect(await screen.findAllByText(/the first one arrives within 5 min/)).toHaveLength(1);
    await user.click(section("JVM"));
    expect(screen.getAllByText(/the first one arrives within 5 min/)).toHaveLength(2);
  });

  test("Storage lists the database, the schemas and each data source's ≈ estimate; before the first measurement it says so", async () => {
    stubApi();
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "Storage" }));
    const sizes = screen.getByRole("table", { name: "Database size per schema" });
    expect(within(sizes).getByText("1.5 GB")).toBeInTheDocument();
    expect(within(sizes).getByText("Schema raw")).toBeInTheDocument();
    expect(within(sizes).getByText("900 MB")).toBeInTheDocument();
    const sources = screen.getByRole("table", { name: "Estimated size per data source" });
    expect(within(sources).getByText("64 000")).toBeInTheDocument();
    expect(within(sources).getByText("≈ 1 GB")).toBeInTheDocument();
    expect(within(sources).getByText("Disabled")).toBeInTheDocument();
    expect(screen.getByText(/≈ is an estimate/)).toBeInTheDocument();
    unmount();

    stubApi(currentFixture({ storage: null }));
    renderPanel();
    expect(await screen.findByText("Storage is measured hourly: not yet.")).toBeInTheDocument();
  });

  test("Sync shows each data source's freshness, failures, 429s, queue and last backfill", async () => {
    stubApi();
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "Sync" }));
    const table = screen.getByRole("table", { name: "Sync health per data source" });
    const cook = within(table).getByText("COOK").closest("tr")!;
    expect(within(cook).getByText("3 h ago")).toBeInTheDocument();
    expect(within(cook).getByText("Failed in a row: 2")).toBeInTheDocument();
    expect(within(cook).getByText("JIRA_TIMEOUT")).toBeInTheDocument();
    expect(within(cook).getByText("12")).toBeInTheDocument();
    expect(within(cook).getByText("Back-off 1.5 min")).toBeInTheDocument();
    expect(within(cook).getByText("1 pending · 0 running")).toBeInTheDocument();
    expect(within(cook).getByText("1.5 h")).toBeInTheDocument();
    const spare = within(table).getByText("Spare").closest("tr")!;
    expect(within(spare).getByText("Never")).toBeInTheDocument();
    // The trend has a data-source picker and asks for the sync group.
    expect(screen.getByRole("combobox", { name: "Data source" })).toHaveValue("COOK");
  });

  test("DERIVE shows the last run, its duration, the weekly peak and the rows written; a source without a run says so", async () => {
    stubApi();
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "DERIVE" }));
    const table = screen.getByRole("table", { name: "DERIVE runs per data source" });
    const cook = within(table).getByText("COOK").closest("tr")!;
    expect(within(cook).getByText("Succeeded")).toBeInTheDocument();
    expect(within(cook).getByText("42 s")).toBeInTheDocument();
    expect(within(cook).getByText("1.6 min")).toBeInTheDocument();
    expect(within(cook).getByText("1 234 567")).toBeInTheDocument();
    expect(within(table).getByText("No run yet")).toBeInTheDocument();
  });

  test("a failed DERIVE run reads Failed, and no data source at all says so in Sync and DERIVE", async () => {
    const base = currentFixture();
    const first = base.connections[0]!;
    stubApi({ ...base, connections: [{ ...first, derive: { ...first.derive!, lastStatus: "FAILED" } }] });
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "DERIVE" }));
    expect(screen.getByText("Failed")).toBeInTheDocument();
    unmount();

    localStorage.removeItem("flow.viewSettings.telemetry.sections");
    stubApi({ ...base, connections: [] });
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "Sync" }));
    await user.click(screen.getByRole("button", { name: "DERIVE" }));
    expect(screen.getAllByText("No data source yet.")).toHaveLength(2);
  });

  test("Requests shows the total and the busiest routes with 5xx and 429 badges; without a sample it says so", async () => {
    stubApi();
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "Requests" }));
    const table = screen.getByRole("table", { name: "Requests per route" });
    const total = within(table).getByText("All routes").closest("tr")!;
    expect(within(total).getByText("1 500")).toBeInTheDocument();
    expect(within(total).getByText("480 ms")).toBeInTheDocument();
    expect(within(total).getByText("2")).toBeInTheDocument();
    const home = within(table).getByText("/api/v1/reports/home").closest("tr")!;
    expect(within(home).getByText("900")).toBeInTheDocument();
    expect(within(home).getByText("3")).toBeInTheDocument();
    expect(screen.getByText("Counts: the last 5 min. Percentiles: the last 5 min. Sampled at 2026-10-24 11:55 UTC. Telemetry's own requests are left out.")).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "p95 (5 min)" })).toBeInTheDocument();
    unmount();

    stubApi(currentFixture({ requests: { windowSeconds: 300, percentileWindowSeconds: 300, measuredAt: null, total: currentFixture().requests.total, routes: [] } }));
    localStorage.setItem("flow.viewSettings.telemetry.sections", '["requests"]');
    renderPanel();
    expect(await screen.findByText(/No web process has a recent sample/)).toBeInTheDocument();
  });

  test("JVM shows CPU, threads, GC and the pool of each process", async () => {
    stubApi();
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "JVM" }));
    const web = screen.getByRole("table", { name: "JVM figures of flow-web-1" });
    expect(within(web).getByText("12.3%")).toBeInTheDocument();
    expect(within(web).getByText("41")).toBeInTheDocument();
    expect(within(web).getByText("25 ms")).toBeInTheDocument();
    expect(within(web).getByText("Waiting for a database connection")).toBeInTheDocument();
  });

  test("JVM before the first sample says when it arrives", async () => {
    stubApi(currentFixture({ processes: [processFixture({ sampledAt: null })] }));
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "JVM" }));
    expect(screen.getAllByText(/the first one arrives within 5 min/)).toHaveLength(2);
  });

  test("history is asked only for expanded sections, and a range switch asks again for the new range", async () => {
    const fetchMock = stubApi();
    const user = userEvent.setup();
    renderPanel();
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    // (A set: Mantine's Collapse may mount a panel's body twice in the first render, which the query cache answers once.)
    const histories = () => [...new Set(calls(fetchMock, "/api/v1/telemetry/history"))];
    expect(histories()).toEqual(["/api/v1/telemetry/history?group=memory&range=24h"]);

    await user.click(screen.getByRole("radio", { name: "7 days" }));
    await vi.waitFor(() => expect(histories()).toContain("/api/v1/telemetry/history?group=memory&range=7d"));
    expect(screen.getByRole("radio", { name: "7 days" })).toBeChecked();

    await user.click(section("JVM"));
    await vi.waitFor(() => expect(histories()).toContain("/api/v1/telemetry/history?group=jvm&range=7d"));
    expect(histories().some((url) => url.includes("group=storage"))).toBe(false);
  });

  test("the range and the expanded sections are remembered on this device", async () => {
    stubApi();
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "Memory" }));
    await user.click(screen.getByRole("radio", { name: "90 days" }));
    await user.click(section("Storage"));
    expect(localStorage.getItem("flow.viewSettings.telemetry.range")).toBe('"90d"');
    expect(localStorage.getItem("flow.viewSettings.telemetry.sections")).toBe('["storage"]');
    unmount();

    stubApi();
    renderPanel();
    expect(await screen.findByRole("button", { name: "Storage" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("radio", { name: "90 days" })).toBeChecked();
    expect(section("Storage")).toHaveAttribute("aria-expanded", "true");
    expect(section("Memory")).toHaveAttribute("aria-expanded", "false");
  });

  test("corrupt stored state falls back to 24 h with Memory open", async () => {
    localStorage.setItem("flow.viewSettings.telemetry.range", '"1y"');
    localStorage.setItem("flow.viewSettings.telemetry.sections", '["nope"]');
    stubApi();
    renderPanel();
    expect(await screen.findByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("radio", { name: "24 h" })).toBeChecked();
  });

  test("current is refetched every minute while the panel is open and not at all once it is closed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = stubApi();
    const { unmount } = renderPanel();
    await screen.findByRole("heading", { level: 2, name: "Telemetry" });
    expect(calls(fetchMock, "/api/v1/telemetry/current")).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls(fetchMock, "/api/v1/telemetry/current")).toHaveLength(2);
    unmount();
    await vi.advanceTimersByTimeAsync(180_000);
    expect(calls(fetchMock, "/api/v1/telemetry/current")).toHaveLength(2);
  });

  test("the refresh is configured to stand still in a background tab", async () => {
    stubApi();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPanel(() => {}, queryClient);
    await screen.findByRole("heading", { level: 2, name: "Telemetry" });
    const observer = queryClient.getQueryCache().find({ queryKey: ["telemetry", "current"] })?.observers[0];
    expect(observer?.options.refetchInterval).toBe(60_000);
    expect(observer?.options.refetchIntervalInBackground).toBe(false);
  });

  test("a failed load renders inline with no sections", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "boom" }))));
    renderPanel();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Memory" })).not.toBeInTheDocument();
  });
  test("the percentiles are labelled with their own window, which may differ from the counts'", async () => {
    const base = currentFixture();
    stubApi({ ...base, requests: { ...base.requests, windowSeconds: 300, percentileWindowSeconds: 900 } });
    localStorage.setItem("flow.viewSettings.telemetry.sections", '["requests"]');
    renderPanel();
    const table = await screen.findByRole("table", { name: "Requests per route" });
    expect(within(table).getByRole("columnheader", { name: "p50 (15 min)" })).toBeInTheDocument();
    expect(screen.getByText(/Counts: the last 5 min\. Percentiles: the last 15 min\./)).toBeInTheDocument();
  });

  test("a wedged replica listed beside a live one of the same role keeps its own table and Stale badge", async () => {
    stubApi(currentFixture({ processes: [processFixture(), processFixture({ id: 3, instance: "flow-web-0", stale: true })] }));
    renderPanel();
    expect(await screen.findByRole("table", { name: "Memory of flow-web-1" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Memory of flow-web-0" })).toBeInTheDocument();
    expect(screen.getAllByText("Stale")).toHaveLength(1);
    expect(screen.getByRole("alert")).toHaveTextContent("flow-web-0 has sent no sample");
  });

  test("the headings form one outline: h2 title, an h3 per section, an h4 per process", async () => {
    stubApi();
    renderPanel();
    await screen.findByRole("table", { name: "Memory of flow-web-1" });
    expect(headingOutline()).toEqual([
      [2, "Telemetry"],
      [3, "Memory"],
      [4, "Web · flow-web-1"],
      [4, "Worker · flow-worker-1"],
      [3, "Storage"],
      [3, "Sync"],
      [3, "DERIVE"],
      [3, "Requests"],
      [3, "JVM"],
    ]);
  });

  test("a failed background refresh keeps the old figures but says they may be out of date", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let fail = false;
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.startsWith("/api/v1/telemetry/current")) return Promise.resolve(fail ? jsonResponse(429, { title: "slow down" }) : jsonResponse(200, currentFixture()));
      return Promise.resolve(jsonResponse(200, historyFixture("memory", "24h", [])));
    }));
    renderPanel();
    await screen.findByRole("table", { name: "Memory of flow-web-1" });
    expect(screen.queryByText("The figures could not be refreshed and may be out of date")).not.toBeInTheDocument();
    fail = true;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(await screen.findByText("The figures could not be refreshed and may be out of date")).toBeInTheDocument();
    expect(screen.getByText("Too many requests. Wait a minute and try again.")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Memory of flow-web-1" })).toBeInTheDocument();
  });

  test("a 429 on the first load is an inline error, not a retry storm", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(jsonResponse(429, { title: "slow down" })));
    vi.stubGlobal("fetch", fetchMock);
    renderPanel();
    expect(await screen.findByText("Too many requests. Wait a minute and try again.")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
