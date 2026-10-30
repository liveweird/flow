import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import MetricsSettings from "./MetricsSettings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const STORED_SETTINGS = {
  configRevision: 3,
  hoursPerDay: 8,
  timeZone: "Europe/Warsaw",
  weekendDays: [6, 7],
  commitmentGraceMinutes: 0,
  minSampleSize: 5,
  agingWindowItems: 50,
  agingPercentiles: [50, 85, 95],
  backlogWindowSprints: 3,
  epicDriftDays: 14,
  epicEstimateGapTolerancePercent: 25,
  historyHorizonMonths: 24,
  historyHorizonFrom: "2024-10-30",
  updatedAt: 1700000000000,
  updatedByUserId: 1,
};

function mockGetAndPut(mockFetch: FetchMock, putStatus = 204, putBody: unknown = null) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/metrics-settings") {
      return Promise.resolve(jsonResponse(200, STORED_SETTINGS));
    }
    if (method === "PUT" && url === "/api/v1/metrics-settings") {
      return Promise.resolve(
        putStatus === 204
          ? new Response(null, { status: 204 })
          : jsonResponse(putStatus, putBody ?? { title: "x", status: putStatus, detail: "x", instance: "/x", type: "about:blank" }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

/** The parsed body of the PUT the page sent, null when it sent none. */
function putBody(mockFetch: FetchMock): unknown {
  const call = mockFetch.mock.calls.find(([url, init]) => url === "/api/v1/metrics-settings" && (init as RequestInit | undefined)?.method === "PUT");
  return call ? JSON.parse((call[1] as RequestInit).body as string) : null;
}

describe("MetricsSettings page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("pre-fills every field from the loaded settings", async () => {
    mockGetAndPut(mockFetch);
    renderWithProviders(<MetricsSettings />);

    const hoursInput = (await screen.findByLabelText(/hours per day/i)) as HTMLInputElement;
    await waitFor(() => expect(hoursInput.value).toBe("8"));
    expect((screen.getByLabelText(/time zone/i, { selector: "input" }) as HTMLInputElement).value).toBe("Europe/Warsaw");
    expect(screen.getByRole("checkbox", { name: "Sat" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Sun" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Mon" })).not.toBeChecked();
    // The holiday list moved to its own page: no field here, a pointer to it instead.
    expect(screen.queryByLabelText(/holidays/i)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Public holidays" })).toHaveAttribute("href", "/public-holidays");
    expect((screen.getByLabelText(/minimum sample size/i) as HTMLInputElement).value).toBe("5");
    // The history horizon: switch on, the stored months, and the server-computed first day in the hint.
    expect(screen.getByRole("switch", { name: /^Limit history/ })).toBeChecked();
    expect((screen.getByLabelText(/history horizon \(months\)/i) as HTMLInputElement).value).toBe("24");
    expect(screen.getByText("Saved: work since 2024-10-30 counts.")).toBeInTheDocument();
  });

  test("switching the horizon off disables the months field and PUTs null", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<MetricsSettings />);

    const monthsInput = (await screen.findByLabelText(/history horizon \(months\)/i)) as HTMLInputElement;
    await waitFor(() => expect(monthsInput.value).toBe("24"));
    await user.click(screen.getByRole("switch", { name: /^Limit history/ }));
    expect(monthsInput).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(putBody(mockFetch)).not.toBeNull());
    expect(putBody(mockFetch)).toMatchObject({ historyHorizonMonths: null });
  });

  test("an emptied horizon blocks the save with the fixed message (the input itself clamps typed numbers to 6..120 on blur)", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<MetricsSettings />);

    const monthsInput = (await screen.findByLabelText(/history horizon \(months\)/i)) as HTMLInputElement;
    await waitFor(() => expect(monthsInput.value).toBe("24"));
    await user.clear(monthsInput);
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("History horizon must be a whole number of months between 6 and 120")).toBeInTheDocument();
    expect(putBody(mockFetch)).toBeNull();
  });

  test("blocks submission and shows the fixed message when hours per day is invalid", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<MetricsSettings />);

    const hoursInput = (await screen.findByLabelText(/hours per day/i)) as HTMLInputElement;
    await waitFor(() => expect(hoursInput.value).toBe("8"));
    await user.clear(hoursInput);
    await user.type(hoursInput, "0");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Hours per day must be greater than 0 and at most 24")).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "PUT")).toBe(false);
  });

  test("PUTs the full replace body on save", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<MetricsSettings />);

    const hoursInput = (await screen.findByLabelText(/hours per day/i)) as HTMLInputElement;
    await waitFor(() => expect(hoursInput.value).toBe("8"));
    await user.clear(hoursInput);
    await user.type(hoursInput, "10");
    const toleranceInput = screen.getByLabelText(/epic estimate gap tolerance/i) as HTMLInputElement;
    expect(toleranceInput.value).toBe("25");
    await user.clear(toleranceInput);
    await user.type(toleranceInput, "50");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([url, init]) => url === "/api/v1/metrics-settings" && (init as RequestInit | undefined)?.method === "PUT")).toBe(true),
    );
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/metrics-settings" && (init as RequestInit | undefined)?.method === "PUT",
    );
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      hoursPerDay: 10,
      timeZone: "Europe/Warsaw",
      weekendDays: [6, 7],
      commitmentGraceMinutes: 0,
      minSampleSize: 5,
      agingWindowItems: 50,
      agingPercentiles: [50, 85, 95],
      backlogWindowSprints: 3,
      epicDriftDays: 14,
      epicEstimateGapTolerancePercent: 50,
      historyHorizonMonths: 24,
    });
  });

  test("shows the fixed forbidden message on a 403", async () => {
    mockGetAndPut(mockFetch, 403);
    const user = userEvent.setup();
    renderWithProviders(<MetricsSettings />);

    const hoursInput = (await screen.findByLabelText(/hours per day/i)) as HTMLInputElement;
    await waitFor(() => expect(hoursInput.value).toBe("8"));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Only administrators can edit metrics settings")).toBeInTheDocument();
  });

  test("a load failure shows the status-tagged message", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, {})));
    renderWithProviders(<MetricsSettings />);

    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });
});
