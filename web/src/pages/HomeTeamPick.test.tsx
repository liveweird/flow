import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import Home from "./Home";
import { jsonResponse } from "../test/http";
import { HOME } from "../test/homeFixtures";
import { delta, HOME_URL, requested, serve } from "../test/homeServe";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";

vi.mock("@mantine/charts", async () => (await import("../test/homeChartsMock")).homeChartsMock);

const PICK_KEY = "flow.viewSettings.home.teams";
const RESET = { name: "Back to my default teams" };

function renderHome() {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<Home />} />
    </Routes>,
  );
}

const ready = () => screen.findByRole("region", { name: "What is missing from the data" });
const sections = () => screen.getByRole("group", { name: "Team overview" });

describe("Home page — which teams it shows", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("the default puts the teams the viewer manages first, then the rest by name, at most three", async () => {
    localStorage.setItem("flow.auth.userId", "7");
    serve(mockFetch, undefined, { ...FILTERS, teams: [...FILTERS.teams, { ...delta, managerUserId: 7 }] });
    renderHome();
    await ready();
    expect(requested(mockFetch)).toContain(`${HOME_URL}?teamId=1&teamId=2&teamId=4`);
    expect(screen.getByRole("combobox", { name: "Teams shown" })).toBeInTheDocument();
    expect(screen.getByText("Delta")).toBeInTheDocument();
    expect(screen.queryByRole("button", RESET)).not.toBeInTheDocument();
  });

  test("a remembered pick is requested, a stale id is dropped and nothing left means the default", async () => {
    localStorage.setItem(PICK_KEY, "[2,99]");
    serve(mockFetch);
    const first = renderHome();
    await ready();
    expect(requested(mockFetch)).toContain(`${HOME_URL}?teamId=2`);
    expect(screen.getByRole("button", RESET)).toBeInTheDocument();
    first.unmount();

    mockFetch.mockClear();
    localStorage.setItem(PICK_KEY, "[99]");
    renderHome();
    await ready();
    expect(requested(mockFetch)).toContain(`${HOME_URL}?teamId=1&teamId=2&teamId=3`);
    expect(screen.queryByRole("button", RESET)).not.toBeInTheDocument();
  });

  test("picking a team stores the pick, refetches and marks the sections busy until the new overview arrives", async () => {
    localStorage.setItem(PICK_KEY, "[1]");
    let release: (response: Response) => void = (_response) => undefined;
    serve(mockFetch, (url) =>
      url === `${HOME_URL}?teamId=1&teamId=4`
        ? new Promise<Response>((resolve) => (release = resolve))
        : jsonResponse(200, HOME),
    );
    const user = userEvent.setup();
    renderHome();
    await ready();
    await user.click(screen.getByRole("combobox", { name: "Teams shown" }));
    await user.click(await screen.findByRole("option", { name: "Delta" }));
    await waitFor(() => expect(requested(mockFetch)).toContain(`${HOME_URL}?teamId=1&teamId=4`));
    expect(JSON.parse(localStorage.getItem(PICK_KEY) ?? "null")).toEqual([1, 4]);
    await waitFor(() => expect(sections()).toHaveAttribute("aria-busy", "true"));
    release(jsonResponse(200, HOME));
    await waitFor(() => expect(sections()).toHaveAttribute("aria-busy", "false"));
  });

  test("at three picks the field says so, and the reset button returns to the default", async () => {
    localStorage.setItem(PICK_KEY, "[1,2,4]");
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    expect(screen.getByText("Up to 3 teams")).toBeInTheDocument();
    await user.click(screen.getByRole("button", RESET));
    await waitFor(() => expect(requested(mockFetch)).toContain(`${HOME_URL}?teamId=1&teamId=2&teamId=3`));
    expect(JSON.parse(localStorage.getItem(PICK_KEY) ?? "x")).toBeNull();
    expect(screen.queryByRole("button", RESET)).not.toBeInTheDocument();
  });

  test("while the default is shown and the cap is not reached the field explains the default", async () => {
    serve(mockFetch, undefined, { ...FILTERS, teams: FILTERS.teams.slice(0, 2) });
    renderHome();
    await ready();
    expect(requested(mockFetch)).toContain(`${HOME_URL}?teamId=1&teamId=2`);
    expect(screen.getByText(/Default: the teams you manage/)).toBeInTheDocument();
  });
});
