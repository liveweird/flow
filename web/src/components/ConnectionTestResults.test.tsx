import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import ConnectionTestResults from "./ConnectionTestResults";
import { renderWithProviders } from "../test/render";
import type { ConnectionTestResult } from "../api/dataSources";

describe("ConnectionTestResults", () => {
  test("an ok row shows Required/OK and no detail, and the cloudId line is absent when not resolved", () => {
    const result: ConnectionTestResult = {
      rows: [{ name: "tenant_info", path: "/_edge/tenant_info", required: true, ok: true, status: 200 }],
    };
    renderWithProviders(<ConnectionTestResults result={result} />);

    expect(screen.getByText("tenant_info")).toBeInTheDocument();
    expect(screen.getByText("/_edge/tenant_info")).toBeInTheDocument();
    expect(screen.getAllByText("Required")).toHaveLength(2); // the column header and the row's own cell
    expect(screen.getByText("OK")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByText(/Cloud ID/)).not.toBeInTheDocument();
  });

  test("an optional failed row combines status/code/scopeHint into the detail cell", () => {
    const result: ConnectionTestResult = {
      rows: [
        {
          name: "bulkfetch",
          path: "/rest/api/3/changelog/bulkfetch",
          required: false,
          ok: false,
          status: 403,
          code: "FORBIDDEN_SCOPE",
          scopeHint: "read:issue.changelog:jira",
        },
      ],
    };
    renderWithProviders(<ConnectionTestResults result={result} />);

    expect(screen.getByText("Optional")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("403 · FORBIDDEN_SCOPE · scope: read:issue.changelog:jira")).toBeInTheDocument();
  });

  test("a failed row with no status/code/scopeHint at all still renders the em-dash detail", () => {
    const result: ConnectionTestResult = {
      rows: [{ name: "search", path: "/rest/api/3/search/jql", required: true, ok: false }],
    };
    renderWithProviders(<ConnectionTestResults result={result} />);

    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  test("a failed row with only a code (no status, no scopeHint) shows the code alone", () => {
    const result: ConnectionTestResult = {
      rows: [{ name: "search", path: "/rest/api/3/search/jql", required: true, ok: false, code: "TIMEOUT" }],
    };
    renderWithProviders(<ConnectionTestResults result={result} />);

    expect(screen.getByText("TIMEOUT")).toBeInTheDocument();
  });

  test("the cloudId line renders once tenant_info resolves it", () => {
    const result: ConnectionTestResult = {
      rows: [{ name: "tenant_info", path: "/_edge/tenant_info", required: true, ok: true, status: 200 }],
      cloudId: "cloud-123",
    };
    renderWithProviders(<ConnectionTestResults result={result} />);

    expect(screen.getByText("Cloud ID: cloud-123")).toBeInTheDocument();
  });
});
