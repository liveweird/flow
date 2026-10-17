import { act } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import type { SyncJobResponse } from "../api/dataSources";
import JobScheduledNote from "./JobScheduledNote";
import JobStateBadge from "./JobStateBadge";

/** 2100-01-01T12:30:00Z. */
const FAR_FUTURE = Date.UTC(2100, 0, 1, 12, 30);

function renderNote(job: Pick<SyncJobResponse, "status" | "notBefore">) {
  return renderWithProviders(
    <>
      <JobStateBadge status={job.status} />
      <JobScheduledNote job={job} />
    </>,
  );
}

describe("JobScheduledNote", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  test("a notBefore that has already passed (a job a shutdown released back to PENDING) shows nothing", () => {
    renderNote({ status: "PENDING", notBefore: 1 });
    expect(screen.queryByText(/Waiting for further changes/)).toBeNull();
  });

  test("the note goes away on its own when notBefore passes, without waiting for a poll", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
    vi.setSystemTime(Date.UTC(2030, 5, 1, 10, 0));
    renderNote({ status: "PENDING", notBefore: Date.UTC(2030, 5, 1, 10, 1) });
    expect(screen.getByText("Waiting for further changes — starts after 2030-06-01 10:01")).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(61_000);
    });
    expect(screen.queryByText(/Waiting for further changes/)).toBeNull();
  });

  test("a PENDING job held back by notBefore reads as scheduled, next to the neutral Pending badge", () => {
    renderNote({ status: "PENDING", notBefore: FAR_FUTURE });
    expect(screen.getByText("Pending")).toBeInTheDocument();
    expect(screen.getByText("Waiting for further changes — starts after 2100-01-01 12:30")).toBeInTheDocument();
    expect(screen.queryByText("Failed")).toBeNull();
  });

  test("renders nothing without notBefore or for a job that is no longer PENDING", () => {
    for (const job of [
      { status: "PENDING", notBefore: null },
      { status: "PENDING", notBefore: undefined },
      { status: "RUNNING", notBefore: FAR_FUTURE },
      { status: "SUCCEEDED", notBefore: FAR_FUTURE },
    ] satisfies Pick<SyncJobResponse, "status" | "notBefore">[]) {
      const { unmount } = renderNote(job);
      expect(screen.queryByText(/Waiting for further changes/)).toBeNull();
      unmount();
    }
  });
});
