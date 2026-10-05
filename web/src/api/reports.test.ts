import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { deepDiveReport } from "../test/deepDiveFixtures";
import {
  fetchDeepDive,
  fetchDeepDiveEpics,
  fetchDeepDiveEpicTasks,
  fetchDeepDiveSprints,
  type DeepDiveEpicOption,
  type DeepDiveSprintOption,
  type DeepDiveTaskOption,
} from "./reports";

const page = { items: [], page: 1, pageSize: 20, total: 0 };
const sprint: DeepDiveSprintOption = {
  id: 4,
  connectionId: 1,
  name: "FLO Sprint 4",
  state: "closed",
  startAt: 1_000,
  endAt: 2_000,
  completeAt: 2_000,
  taskCount: 23,
};
const epic: DeepDiveEpicOption = {
  id: 33,
  connectionId: 1,
  key: "FLO-33",
  summary: "Login",
  domain: "FLO",
};
const task: DeepDiveTaskOption = {
  id: 34,
  connectionId: 1,
  key: "FLO-34",
  summary: null,
};

describe("Deep dive fetchers", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });
  const fetchMock = () => fetch as unknown as ReturnType<typeof vi.fn>;
  const urlOfCall = (n = 0) => fetchMock().mock.calls[n][0] as string;

  test("fetchDeepDive sends the selection in its canonical query, repeated keys included", async () => {
    const report = deepDiveReport();
    fetchMock().mockResolvedValueOnce(jsonResponse(200, report));
    await expect(
      fetchDeepDive({
        to: "2026-03-31",
        sprintIds: [5, 4],
        domain: "FLO",
        connectionId: 2,
      }),
    ).resolves.toEqual(report);
    expect(urlOfCall()).toBe(
      "/api/v1/reports/deep-dive?domain=FLO&sprintId=4&sprintId=5&connectionId=2&to=2026-03-31",
    );

    fetchMock().mockResolvedValueOnce(jsonResponse(200, report));
    await fetchDeepDive({ epicIds: ["FLO-1"], issueIds: ["FLO-3", "FLO-2"] });
    expect(urlOfCall(1)).toBe(
      "/api/v1/reports/deep-dive?epicId=FLO-1&issueId=FLO-2&issueId=FLO-3",
    );
  });

  test("fetchDeepDiveSprints sends the required domain and the paging/search params, skipping the blank ones", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveSprints({
      domain: "FLO",
      q: "Sprint 4",
      page: 2,
      pageSize: 50,
      connectionId: 3,
    });
    expect(urlOfCall()).toBe(
      "/api/v1/reports/deep-dive/sprints?domain=FLO&q=Sprint+4&page=2&pageSize=50&connectionId=3",
    );

    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveSprints({ domain: "FLO", q: "" });
    expect(urlOfCall(1)).toBe("/api/v1/reports/deep-dive/sprints?domain=FLO");
  });

  test("the option lists return the typed page the server sent", async () => {
    fetchMock().mockResolvedValueOnce(
      jsonResponse(200, { ...page, items: [sprint], total: 1 }),
    );
    expect((await fetchDeepDiveSprints({ domain: "FLO" })).items).toEqual([
      sprint,
    ]);
    fetchMock().mockResolvedValueOnce(
      jsonResponse(200, { ...page, items: [epic], total: 1 }),
    );
    expect((await fetchDeepDiveEpics({})).items).toEqual([epic]);
    fetchMock().mockResolvedValueOnce(
      jsonResponse(200, { ...page, items: [task], total: 1 }),
    );
    expect((await fetchDeepDiveEpicTasks("FLO-33", {})).items).toEqual([task]);
  });

  test("fetchDeepDiveEpics narrows by an optional domain", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveEpics({
      q: "épic",
      domain: "FLO",
      page: 1,
      pageSize: 20,
    });
    expect(urlOfCall()).toBe(
      "/api/v1/reports/deep-dive/epics?q=%C3%A9pic&domain=FLO&page=1&pageSize=20",
    );

    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveEpics({});
    expect(urlOfCall(1)).toBe("/api/v1/reports/deep-dive/epics?");
  });

  test("q and domain are trimmed and stripped of control characters; a blank result is omitted", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveEpics({
      q: "  lo\u0000gin\u0085 ",
      domain: " FLO\u007f ",
    });
    expect(urlOfCall()).toBe(
      "/api/v1/reports/deep-dive/epics?q=login&domain=FLO",
    );

    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveSprints({ domain: "\u0000 ", q: "\u0085" });
    expect(urlOfCall(1)).toBe("/api/v1/reports/deep-dive/sprints?");

    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveEpicTasks("FLO-33", { q: " a\u0001b " });
    expect(urlOfCall(2)).toBe(
      "/api/v1/reports/deep-dive/epics/FLO-33/tasks?q=ab",
    );
  });

  test("fetchDeepDiveEpicTasks puts the epic key in the path, encoded", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveEpicTasks("FLO-33", { q: "login", connectionId: 1 });
    expect(urlOfCall()).toBe(
      "/api/v1/reports/deep-dive/epics/FLO-33/tasks?q=login&connectionId=1",
    );

    fetchMock().mockResolvedValueOnce(jsonResponse(200, page));
    await fetchDeepDiveEpicTasks("A/B", {});
    expect(urlOfCall(1)).toBe("/api/v1/reports/deep-dive/epics/A%2FB/tasks?");
  });

  test("a failure surfaces as an ApiError with the status", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(400, { detail: "no" }));
    await expect(fetchDeepDive({})).rejects.toMatchObject({ status: 400 });
  });
});
