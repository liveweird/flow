import type { ComponentType } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportBlockedTime from "./ReportBlockedTime";
import ReportCommitments from "./ReportCommitments";
import ReportCosts from "./ReportCosts";
import ReportCycleTime from "./ReportCycleTime";
import ReportEpicAccuracy from "./ReportEpicAccuracy";
import ReportEstimateAdjustments from "./ReportEstimateAdjustments";
import ReportReportedTime from "./ReportReportedTime";
import ReportSprintConsistency from "./ReportSprintConsistency";
import ReportTaskAccuracy from "./ReportTaskAccuracy";
import ReportTaskEstimates from "./ReportTaskEstimates";
import ReportThroughput from "./ReportThroughput";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor } from "../test/render";

// Every team-required report that has a drill. A hand-typed or pre-1.7.0 link names a bar and no team; the page gives it the default team
// (the first by name: Alpha, id 1) once the reference data is in, and that one-time normalisation must not close the drill it carries.
// The report itself is never served (404): the URL is the subject, so no chart is drawn and none needs mocking.
const PAGES: [name: string, path: string, drill: string, Page: ComponentType][] = [
  ["blocked time", "/reports/blocked-time", "hist:RATIO:1", ReportBlockedTime],
  ["commitments", "/reports/commitments", "sprint:1:12:FINAL", ReportCommitments],
  ["costs", "/reports/costs", "cost:WORK_CATEGORY:Run", ReportCosts],
  ["cycle time", "/reports/cycle-time", "hist:WORKING_DAYS:1", ReportCycleTime],
  ["epic estimation accuracy", "/reports/epic-estimation-accuracy", "hist:AT_START:0", ReportEpicAccuracy],
  ["estimate adjustments", "/reports/estimate-adjustments", "hist:TASKS:1", ReportEstimateAdjustments],
  ["reported time", "/reports/reported-time-ratio", "hist:WORKING_DAYS:1", ReportReportedTime],
  ["sprint consistency", "/reports/sprint-consistency", "sprint:1:12:CARRIED_OVER", ReportSprintConsistency],
  ["task estimation accuracy", "/reports/task-estimation-accuracy", "hist:AT_DONE:0", ReportTaskAccuracy],
  ["task estimates", "/reports/task-estimates", "hist:AT_START:0", ReportTaskEstimates],
  ["throughput", "/reports/throughput", "bucket:2026-09-07", ReportThroughput],
];

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}
const search = () => screen.getByTestId("where").textContent ?? "";
const drillOf = (spelled: string) => new URLSearchParams({ drill: spelled }).toString();

describe.each(PAGES)("%s — a drill link without a team", (_name, path, drill, Page) => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) =>
        Promise.resolve(url === "/api/v1/reports/filters" ? jsonResponse(200, FILTERS) : jsonResponse(404, { title: "Not Found", status: 404 })),
      ),
    );
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("keeps its drill when the page gives the link the default team, and a genuine filter change still closes it", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route
          path={path}
          element={
            <>
              <Page />
              <Where />
            </>
          }
        />
      </Routes>,
      { route: `${path}?drill=${drill}` },
    );
    // Normalised: the team arrives (a replace), and the drill is still there once the dust has settled.
    await waitFor(() => expect(search()).toContain("teamId=1"));
    await openReportFilters(user);
    expect(search()).toContain(drillOf(drill));

    // The user's own change of filter closes it, as ever.
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(search()).toContain("teamId=2"));
    expect(search()).not.toContain("drill=");
  });
});
