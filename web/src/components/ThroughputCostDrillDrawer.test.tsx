import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import ThroughputCostDrillDrawer from "./ThroughputCostDrillDrawer";
import type { ReportDrillItems } from "../api/reportDrill";
import { jsonResponse } from "../test/http";
import { THROUGHPUT_ITEMS, THROUGHPUT_TEAM, THROUGHPUT_USERS } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

const BUCKET = { kind: "ac-bucket", bucketStart: "2026-09-07" } as const;
const SPRINT = { kind: "ac-sprint", teamId: 1, sprintId: 11 } as const;
const COST: ReportDrillItems = { ...THROUGHPUT_ITEMS, bar: { count: 5, sumMd: 13.5, from: null, to: null } };

function props(over: Partial<Parameters<typeof ThroughputCostDrillDrawer>[0]> = {}): Parameters<typeof ThroughputCostDrillDrawer>[0] {
  return {
    opened: true,
    target: BUCKET,
    filter: { teamId: 1 },
    bucket: "WEEK",
    byBucket: THROUGHPUT_TEAM.byBucket,
    bySprint: THROUGHPUT_TEAM.bySprint,
    sprintLabel: ({ sprintId }) => (sprintId === 11 ? "Alpha 1" : undefined),
    timeZone: "UTC",
    onClose: vi.fn(),
    onSegmentChange: vi.fn(),
    ...over,
  };
}

describe("ThroughputCostDrillDrawer", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(jsonResponse(200, COST));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a week's cost: titled 'Actual cost' over the week, asked with measure=ACTUAL_COST and the week", async () => {
    renderWithProviders(<ThroughputCostDrillDrawer {...props()} />);
    expect(await screen.findByRole("dialog", { name: "Actual cost · 2026-09-07" })).toBeInTheDocument();
    expect(await screen.findByText("5 items · 13.5 MD")).toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/reports/throughput/items?teamId=1&bucketStart=2026-09-07&measure=ACTUAL_COST");
    // The chart's cost for that week is 13.5 MD, the list says 13.5: no note.
    expect(screen.queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
  });

  test("a sprint's cost: titled over the sprint as the chart labels it, the period narrowed to it and no bucketStart", async () => {
    renderWithProviders(<ThroughputCostDrillDrawer {...props({ target: SPRINT })} />);
    expect(await screen.findByRole("dialog", { name: "Actual cost · Alpha 1" })).toBeInTheDocument();
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/reports/throughput/items?sprintId=11&teamId=1&measure=ACTUAL_COST");
    // Alpha 1's chart says 11 MD, the list 13.5: the data changed.
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a sprint the chart does not know is named by its id", async () => {
    renderWithProviders(<ThroughputCostDrillDrawer {...props({ target: { kind: "ac-sprint", teamId: 1, sprintId: 99 } })} />);
    expect(await screen.findByRole("dialog", { name: "Actual cost · Sprint 99" })).toBeInTheDocument();
  });

  test("one person's segment is held against THEIR logged time, not the bar's", async () => {
    const ann = { kind: "ac-bucket", bucketStart: "2026-09-07", segmentAccountId: "acc-ann" } as const;
    mockFetch.mockResolvedValue(jsonResponse(200, { ...COST, bar: { count: 2, sumMd: 7, from: null, to: null } }));
    renderWithProviders(
      <ThroughputCostDrillDrawer
        {...props({
          target: ann,
          byBucket: THROUGHPUT_USERS.byBucket,
          periodPeople: [{ accountId: "acc-ann", label: "Ann Author", position: 1, key: "p0", costKey: "a0" }],
        })}
      />,
    );
    expect(await screen.findByText("2 items · 7 MD")).toBeInTheDocument();
    // Ann's share of that week is 7 MD (the bar is 11): no note.
    expect(screen.queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(mockFetch.mock.calls[0][0]).toContain("segmentAccountId=acc-ann");
  });

  test("closed, or another kind of bar, requests nothing and shows nothing", () => {
    const { unmount } = renderWithProviders(<ThroughputCostDrillDrawer {...props({ opened: false })} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    unmount();
    renderWithProviders(<ThroughputCostDrillDrawer {...props({ target: { kind: "bucket", bucketStart: "2026-09-07" } })} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
