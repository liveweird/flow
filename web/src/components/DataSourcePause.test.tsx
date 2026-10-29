import { describe, expect, test } from "vitest";
import { DataSourcePausedAlert, DataSourcePausedBadge } from "./DataSourcePause";
import type { DataSourceResponse } from "../api/dataSources";
import { renderWithProviders, screen } from "../test/render";

type Status = DataSourceResponse["status"];

const HEALTHY: Status = {
  state: "CURRENT",
  lastSyncSucceededAt: 1_700_000_060_000,
  lastSyncErrorCode: null,
  consecutiveFailures: 0,
  limitTrips: 0,
  reconcileLimitTrips: 0,
};

const PAUSED: Status = {
  ...HEALTHY,
  state: "FAILED",
  lastSyncErrorCode: "LIMIT_EXCEEDED",
  pausedAt: 1_700_000_120_000,
  pauseReason: "LIMIT_EXCEEDED",
  limitTrips: 5,
};

const RECONCILE_PAUSED: Status = {
  ...HEALTHY,
  reconcilePausedAt: 1_700_000_180_000,
  reconcilePauseReason: "TIME_EXCEEDED",
  reconcileLimitTrips: 3,
};

describe("DataSourcePause", () => {
  test("a connection that is not paused renders neither the badge nor the alert", () => {
    renderWithProviders(
      <>
        <DataSourcePausedBadge status={HEALTHY} />
        <DataSourcePausedAlert status={{ ...HEALTHY, limitTrips: 2 }} />
      </>,
    );
    expect(screen.queryByText("Paused")).not.toBeInTheDocument();
    expect(screen.queryByText("Scheduled syncing is paused")).not.toBeInTheDocument();
    expect(screen.queryByText("Reconcile paused")).not.toBeInTheDocument();
  });

  test("the badge reads Paused and carries the reason", () => {
    renderWithProviders(<DataSourcePausedBadge status={PAUSED} />);
    expect(screen.getByText("Paused").closest("[title]")).toHaveAttribute("title", "a safety limit was hit");
  });

  test("the alert names the date, the reason and the count, and says how to resume", () => {
    renderWithProviders(<DataSourcePausedAlert status={{ ...PAUSED, pauseReason: "TIME_EXCEEDED" }} />);
    expect(screen.getByText("Scheduled syncing is paused")).toBeInTheDocument();
    const body = screen.getByText(/Scheduled syncs have been off since 2023-11-14 22:15/);
    expect(body).toHaveTextContent("the time limit per run was hit");
    expect(body).toHaveTextContent("Syncs in a row that stopped without storing anything: 5");
    expect(body).toHaveTextContent('Run "Sync now" or save the connection to resume.');
  });

  test("an unknown reason code falls back to the generic wording", () => {
    renderWithProviders(<DataSourcePausedAlert status={{ ...PAUSED, pauseReason: "SOMETHING_NEW" }} />);
    expect(screen.getByText(/a limit was hit/)).toBeInTheDocument();
  });

  test("a RECONCILE pause alone names RECONCILE, how to lift it, and that SYNC keeps running", () => {
    renderWithProviders(
      <>
        <DataSourcePausedBadge status={RECONCILE_PAUSED} />
        <DataSourcePausedAlert status={RECONCILE_PAUSED} />
      </>,
    );
    expect(screen.getByText("Reconcile paused").closest("[title]")).toHaveAttribute("title", "the time limit per run was hit");
    expect(screen.queryByText("Paused")).not.toBeInTheDocument();
    expect(screen.queryByText("Scheduled syncing is paused")).not.toBeInTheDocument();
    expect(screen.getByText("Scheduled reconcile is paused")).toBeInTheDocument();
    const body = screen.getByText(/The scheduled daily reconcile has been off since 2023-11-14 22:16/);
    expect(body).toHaveTextContent("the time limit per run was hit");
    expect(body).toHaveTextContent("Reconciles in a row that hit a limit: 3");
    expect(body).toHaveTextContent("Scheduled syncs keep running");
    expect(body).toHaveTextContent('Run "Reconcile now" or save the connection to resume it.');
  });

  test("both pauses render both badges and both alerts, each with its own count", () => {
    const both: Status = {
      ...PAUSED,
      reconcilePausedAt: RECONCILE_PAUSED.reconcilePausedAt,
      reconcilePauseReason: RECONCILE_PAUSED.reconcilePauseReason,
      reconcileLimitTrips: RECONCILE_PAUSED.reconcileLimitTrips,
    };
    renderWithProviders(
      <>
        <DataSourcePausedBadge status={both} />
        <DataSourcePausedAlert status={both} />
      </>,
    );
    expect(screen.getByText("Paused")).toBeInTheDocument();
    expect(screen.getByText("Reconcile paused")).toBeInTheDocument();
    expect(screen.getByText(/Syncs in a row that stopped without storing anything: 5/)).toBeInTheDocument();
    expect(screen.getByText(/Reconciles in a row that hit a limit: 3/)).toBeInTheDocument();
    expect(screen.getByText(/Sync now/)).toBeInTheDocument();
    expect(screen.getByText(/Reconcile now/)).toBeInTheDocument();
  });

  test("a SYNC pause alone leaves the RECONCILE badge and alert out", () => {
    renderWithProviders(
      <>
        <DataSourcePausedBadge status={PAUSED} />
        <DataSourcePausedAlert status={PAUSED} />
      </>,
    );
    expect(screen.queryByText("Reconcile paused")).not.toBeInTheDocument();
    expect(screen.queryByText("Scheduled reconcile is paused")).not.toBeInTheDocument();
  });
});
