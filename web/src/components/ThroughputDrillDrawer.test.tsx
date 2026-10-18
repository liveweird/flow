import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import ThroughputDrillDrawer from "./ThroughputDrillDrawer";
import { jsonResponse } from "../test/http";
import { THROUGHPUT_ITEMS, THROUGHPUT_TEAM } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

const TARGET = { kind: "bucket", bucketStart: "2026-09-07" } as const;

function props(over: Partial<Parameters<typeof ThroughputDrillDrawer>[0]> = {}): Parameters<typeof ThroughputDrillDrawer>[0] {
  return {
    opened: true,
    target: TARGET,
    filter: { teamId: 1 },
    bucket: "WEEK",
    byBucket: THROUGHPUT_TEAM.byBucket,
    people: [],
    timeZone: "UTC",
    onClose: vi.fn(),
    onSegmentChange: vi.fn(),
    ...over,
  };
}

describe("ThroughputDrillDrawer", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(jsonResponse(200, THROUGHPUT_ITEMS));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("fetches the bar's items while open", async () => {
    renderWithProviders(<ThroughputDrillDrawer {...props()} />);
    expect(await screen.findByRole("dialog", { name: "Delivered · 2026-09-07" })).toBeInTheDocument();
    expect(await screen.findByText("5 items · 12.5 MD")).toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  test("a closed drawer holding the last bar (its exit transition) requests nothing and shows nothing", () => {
    renderWithProviders(<ThroughputDrillDrawer {...props({ opened: false })} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("closing keeps the answer cached and does not refetch, even with the last bar still given", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = renderWithProviders(<ThroughputDrillDrawer {...props()} />, { queryClient });
    await screen.findByText("5 items · 12.5 MD");
    view.rerender(<ThroughputDrillDrawer {...props({ opened: false })} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  test("no bar at all is closed and quiet", () => {
    renderWithProviders(<ThroughputDrillDrawer {...props({ opened: false, target: null })} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
