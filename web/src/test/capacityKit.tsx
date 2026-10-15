/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding for the Capacity page's tests: the fixtures, the fetch stub and the render helpers
import { vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { act } from "@testing-library/react";
import { useLocation } from "react-router-dom";
import Capacity from "../pages/Capacity";
import { jsonResponse } from "./http";
import { renderWithProviders } from "./render";

export const DAYS = ["2026-03-06", "2026-03-07", "2026-03-08", "2026-03-09"];
const FRIDAY_8AM = Date.UTC(2026, 2, 6, 8);
const MONDAY_5PM = Date.UTC(2026, 2, 9, 16);

export const TEAMS = {
  items: [
    { id: 3, name: "Payments", description: null, managerUserId: 7, managerName: "Mia Manager", memberCount: 2, createdAt: 1, updatedAt: 2 },
    { id: 4, name: "Identity", description: null, managerUserId: null, managerName: null, memberCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  page: 1,
  pageSize: 100,
  total: 2,
};

export function sprintSummary(sprintId: number, name: string, hasCalendar: boolean) {
  return {
    sprintId,
    connectionId: 1,
    name,
    state: "CLOSED",
    startAt: FRIDAY_8AM,
    endAt: MONDAY_5PM,
    completeAt: MONDAY_5PM,
    hasCalendar,
    capacityMd: 20,
    capacitySource: "TEAM_DEFAULT",
  };
}

export const SPRINTS = {
  items: [sprintSummary(12, "Sprint 12", false), sprintSummary(11, "Sprint 11", true)],
  page: 1,
  pageSize: 100,
  total: 2,
};

export function person(accountId: string, displayName: string, coefficient: number, member = true) {
  return { accountId, displayName, member, enabled: true, coefficient, cells: DAYS.map((day) => ({ day, availability: 1 })) };
}

// Fri + Mon counted, the weekend off; Ada at 1, Bob at 0.8 and no longer a member: 1 x (2 + 0.8 x 2)... = (2 + 1.6) = 3.6.
function calendar(overrides: Record<string, unknown> = {}) {
  return {
    teamId: 3,
    sprintId: 11,
    connectionId: 1,
    sprintName: "Sprint 11",
    sprintState: "CLOSED",
    window: { from: DAYS[0], to: DAYS[3] },
    note: null,
    hasCalendar: true,
    canEdit: true,
    capacityMd: 3.6,
    teamCoefficient: 1,
    days: DAYS.map((day, i) => ({ day, enabled: i === 0 || i === 3, holiday: null as string | null })),
    people: [person("ada", "Ada Lovelace", 1), person("bob", "Bob Builder", 0.8, false)],
    derivedCapacityMd: 20,
    derivedCapacitySource: "TEAM_DEFAULT",
    updatedAt: 1000,
    updatedByUserId: 7,
    ...overrides,
  };
}

type Reply = { status: number; body?: unknown };

export interface Api {
  calendar?: Record<string, unknown>;
  capacity?: Record<string, unknown>;
  sprints?: unknown;
  teams?: unknown;
  puts?: Record<string, Reply>;
}

export type FetchMock = ReturnType<typeof vi.fn>;

export function serve(mockFetch: FetchMock, api: Api = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const put = api.puts?.[`${method} ${url}`];
    if (put) return Promise.resolve(put.body === undefined ? new Response(null, { status: put.status }) : jsonResponse(put.status, put.body));
    if (method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
    if (url.startsWith("/api/v1/teams?")) return Promise.resolve(jsonResponse(200, api.teams ?? TEAMS));
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, { timeZone: "Europe/Warsaw" }));
    if (/^\/api\/v1\/teams\/\d+\/sprint-calendars\?/.test(url)) return Promise.resolve(jsonResponse(200, api.sprints ?? SPRINTS));
    if (/^\/api\/v1\/teams\/\d+\/sprint-calendars\/\d+$/.test(url)) return Promise.resolve(jsonResponse(200, calendar(api.calendar)));
    if (/^\/api\/v1\/teams\/\d+\/capacity$/.test(url)) {
      return Promise.resolve(jsonResponse(200, { teamId: 3, defaultCapacityMd: 40, canEdit: true, updatedAt: 500, ...api.capacity }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Probe() {
  const { pathname, search } = useLocation();
  return <p data-testid="at">{pathname + search}</p>;
}

export function renderPage(route = "/capacity") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  renderWithProviders(
    <>
      <Capacity />
      <Probe />
    </>,
    { route, queryClient },
  );
  return queryClient;
}

/** A refetch the way a window refocus does it: the calendar comes back from the (possibly changed) server. */
export const refetchCalendar = (queryClient: QueryClient) => act(() => queryClient.refetchQueries({ queryKey: ["capacity", "calendar"] }));
export const calendarGets = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([url, init]) => url === "/api/v1/teams/3/sprint-calendars/11" && !(init as RequestInit | undefined)?.method).length;

export const putCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
export const putBody = (mockFetch: FetchMock, url: string): unknown => {
  const call = putCalls(mockFetch).find(([u]) => u === url);
  return JSON.parse((call?.[1] as RequestInit).body as string);
};

