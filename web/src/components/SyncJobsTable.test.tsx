import { describe, expect, test, vi } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import type { SyncJobResponse } from "../api/dataSources";
import SyncJobsTable from "./SyncJobsTable";

const BASE_JOB: SyncJobResponse = {
  id: 1,
  connectionId: 1,
  kind: "SYNC",
  status: "FAILED",
  priority: 0,
  configRevision: 1,
  requestedAt: 1,
  startedAt: 2,
  attempt: 5,
  maxAttempts: 5,
  progress: {},
};

function renderTable(items: SyncJobResponse[]) {
  return renderWithProviders(
    <SyncJobsTable
      data={{ items, page: 1, pageSize: 20, total: items.length }}
      isLoading={false}
      isError={false}
      error={null}
      kind={null}
      onKindChange={vi.fn()}
      status={null}
      onStatusChange={vi.fn()}
      page={1}
      pageSize={20}
      onPageChange={vi.fn()}
      onPageSizeChange={vi.fn()}
      onCancel={vi.fn()}
      cancellingId={null}
    />,
  );
}

describe("SyncJobsTable error cell", () => {
  test("the error detail shows under the code, as plain text with the full text in the title", () => {
    const detail = "Lease expired on attempt 5 of 5 (the worker stopped mid-job — restarted or out of memory?)";
    renderTable([{ ...BASE_JOB, errorCode: "RETRIES_EXHAUSTED", errorDetail: detail }]);
    expect(screen.getByText("RETRIES_EXHAUSTED")).toBeInTheDocument();
    expect(screen.getByText(detail)).toHaveAttribute("title", detail);
  });

  test("server text is never interpreted as markup", () => {
    const detail = "IllegalStateException: <b>boom</b> **x**";
    renderTable([{ ...BASE_JOB, errorCode: "INTERNAL", errorDetail: detail }]);
    expect(screen.getByText(detail)).toBeInTheDocument();
    expect(document.querySelector("b")).toBeNull();
  });

  test("no detail renders nothing beyond the code or the dash", () => {
    renderTable([
      { ...BASE_JOB, id: 1, errorCode: "TIMEOUT", errorDetail: null },
      { ...BASE_JOB, id: 2, status: "SUCCEEDED", errorCode: null, errorDetail: "" },
    ]);
    expect(screen.getByText("TIMEOUT")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(document.querySelectorAll("[title]")).toHaveLength(0);
  });
});
