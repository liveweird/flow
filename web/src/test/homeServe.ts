import type { vi } from "vitest";
import { jsonResponse } from "./http";
import { HOME } from "./homeFixtures";
import { FILTERS } from "./reportFixtures";

type FetchMock = ReturnType<typeof vi.fn>;

export const HOME_URL = "/api/v1/reports/home";
const NOT_FOUND = { title: "Not Found", status: 404 };

/** Home's own reference data: the three teams of the overview fixture plus a fourth (Delta, id 4), so the default has to choose. */
export const delta = { id: 4, name: "Delta", managerUserId: null, sprints: [], members: [] };
const FILTERS_WITH_DELTA = { ...FILTERS, teams: [...FILTERS.teams, delta] };

export function serve(
  mockFetch: FetchMock,
  answer: (url: string) => Response | Promise<Response> = () => jsonResponse(200, HOME),
  filters: unknown = FILTERS_WITH_DELTA,
) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, filters));
    if (url === HOME_URL || url.startsWith(`${HOME_URL}?`)) return Promise.resolve(answer(url));
    return Promise.resolve(jsonResponse(404, NOT_FOUND));
  });
}

export const requested = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string);
