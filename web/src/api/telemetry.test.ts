import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { getTelemetryCurrent, getTelemetryHistory } from "./telemetry";

describe("telemetry api", () => {
  beforeEach(() => {
    localStorage.setItem("flow.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("current is a plain GET, history carries its group and range", async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(jsonResponse(200, { url })));
    vi.stubGlobal("fetch", fetchMock);
    await getTelemetryCurrent();
    await getTelemetryHistory("derive", "90d");
    expect(fetchMock.mock.calls.map((call) => String(call[0]))).toEqual([
      "/api/v1/telemetry/current",
      "/api/v1/telemetry/history?group=derive&range=90d",
    ]);
  });
});
