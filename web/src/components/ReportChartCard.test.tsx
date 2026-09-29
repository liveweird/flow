import { describe, expect, test } from "vitest";
import ReportChartCard from "./ReportChartCard";
import { ApiError } from "../api/http";
import { renderWithProviders, screen } from "../test/render";

const base = { title: "Scope", isPending: false, empty: false };

describe("ReportChartCard", () => {
  test("renders the title, caption and body", () => {
    renderWithProviders(
      <ReportChartCard {...base} caption="in MD">
        <p>body</p>
      </ReportChartCard>,
    );
    expect(screen.getByRole("heading", { name: "Scope" })).toBeInTheDocument();
    expect(screen.getByText("in MD")).toBeInTheDocument();
    expect(screen.getByText("body")).toBeInTheDocument();
  });

  test("first load shows the spinner, not the body", () => {
    renderWithProviders(
      <ReportChartCard {...base} isPending>
        <p>body</p>
      </ReportChartCard>,
    );
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.queryByText("body")).not.toBeInTheDocument();
  });

  test("an empty period shows the empty state", () => {
    renderWithProviders(
      <ReportChartCard {...base} empty>
        <p>body</p>
      </ReportChartCard>,
    );
    expect(screen.getByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByText("body")).not.toBeInTheDocument();
  });

  test("a failure is a red alert with the status, never the raw message", () => {
    renderWithProviders(
      <ReportChartCard {...base} error={new ApiError(503, {})}>
        <p>body</p>
      </ReportChartCard>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Load failed (503)");
    expect(screen.queryByText("body")).not.toBeInTheDocument();
  });

  test("a refetch over kept data dims the previous body and marks it busy", () => {
    renderWithProviders(
      <ReportChartCard {...base} isRefreshing>
        <p>body</p>
      </ReportChartCard>,
    );
    expect(screen.getByText("body").parentElement).toHaveAttribute("aria-busy", "true");
  });
});
