import { afterEach, describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation, useNavigate } from "react-router-dom";
import ReportFilterBar, { type ReportControls } from "./ReportFilterBar";
import { useReportFilter } from "../hooks/useReportFilter";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";

function Harness({ controls }: { controls?: ReportControls }) {
  const { filter, setFilter } = useReportFilter(FILTERS);
  const { search } = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <button type="button" onClick={() => navigate("/reports/velocity?from=2026-03-01&to=2026-03-31")}>
        go
      </button>
      <ReportFilterBar filters={FILTERS} filter={filter} onChange={setFilter} controls={controls} />
      <output data-testid="search">{search}</output>
    </>
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");

async function pick(user: ReturnType<typeof userEvent.setup>, combobox: string, option: string) {
  await user.click(screen.getByRole("combobox", { name: combobox }));
  await user.click(await screen.findByRole("option", { name: option }));
}

afterEach(() => localStorage.clear());

describe("ReportFilterBar", () => {
  test("a preset writes an inclusive from/to range into the URL", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    await pick(user, "Period", "Last 30 days");
    const params = search();
    expect(params.get("from")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(params.get("to")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect((screen.getByRole("combobox", { name: "Period" }) as HTMLInputElement).value).toBe("Last 30 days");
  });

  test("last N sprints replaces the date range", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity?from=2026-01-01&to=2026-02-01" });
    await pick(user, "Period", "Last 3 sprints");
    expect(search().get("lastSprints")).toBe("3");
    expect(search().has("from")).toBe(false);
  });

  test("the custom range shows a date picker", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    expect(screen.queryByText("Date range")).not.toBeInTheDocument();
    await pick(user, "Period", "Custom range");
    expect(await screen.findByText("Date range")).toBeInTheDocument();
    expect(search().has("from")).toBe(true);
  });

  test("one sprint needs a team, then picks that team's newest sprint and offers its others", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    expect(await screen.findByRole("option", { name: "One sprint" })).toHaveAttribute("data-combobox-disabled", "true");
    await user.click(screen.getByRole("combobox", { name: "Period" }));

    await pick(user, "Team", "Alpha");
    await pick(user, "Period", "One sprint");
    // Alpha 3 is newer but still open — the default is the newest sprint that has completed.
    expect(search().get("sprintId")).toBe("12");
    await pick(user, "Sprint", "Alpha 1");
    expect(search().get("sprintId")).toBe("11");
  });

  test("one sprint falls back to an open sprint when the team has no completed one", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    await pick(user, "Team", "Gamma");
    await pick(user, "Period", "One sprint");
    expect(search().get("sprintId")).toBe("31");
  });

  test("a sprint link without a team still shows its sprint, leaving the Team select empty", () => {
    renderWithProviders(<Harness />, { route: "/reports/velocity?sprintId=12" });
    expect((screen.getByRole("combobox", { name: "Sprint" }) as HTMLInputElement).value).toBe("Alpha 2");
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("");
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
  });

  test("the custom range picker resyncs when the URL changes underneath it (Back/forward)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity?from=2026-01-01&to=2026-02-01" });
    expect(screen.getByText("2026-01-01 – 2026-02-01")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "go" }));
    expect(await screen.findByText("2026-03-01 – 2026-03-31")).toBeInTheDocument();
  });

  test("changing the team drops a sprint the new team does not list and any member", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity?teamId=1&sprintId=11&accountId=acc-ann" });
    await pick(user, "Team", "Beta");
    const params = search();
    expect(params.get("teamId")).toBe("2");
    expect(params.has("sprintId")).toBe(false);
    expect(params.has("accountId")).toBe(false);
  });

  test("the member select appears with a team and writes accountId; clearing the team clears it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    await pick(user, "Team", "Alpha");
    await pick(user, "Member", "Bob Builder");
    expect(search().get("teamId")).toBe("1");
    expect(search().get("accountId")).toBe("acc-bob");

    await user.click(screen.getByLabelText("Clear Team"));
    await waitFor(() => expect(search().has("teamId")).toBe(false));
    expect(search().has("accountId")).toBe(false);
  });

  test("report-specific controls are absent unless the report asks for them", () => {
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    for (const label of ["Domain", "Activity type", "Work category"]) {
      expect(screen.queryByRole("combobox", { name: label })).not.toBeInTheDocument();
    }
    expect(screen.queryByText("Domain view")).not.toBeInTheDocument();
    expect(screen.queryByText("Break down by")).not.toBeInTheDocument();
  });

  test("the offered controls write their params", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness controls={{ domainView: "EPIC", domain: true, activityType: true, workCategory: true, breakdown: true }} />,
      { route: "/reports/velocity" },
    );
    // The report's own default view is preselected; choosing the other writes the param.
    expect(screen.getByRole("radio", { name: "Earned in" })).toBeChecked();
    await user.click(screen.getByText("Delivered in"));
    expect(search().get("domainView")).toBe("TASK");

    await user.click(screen.getByText("Domain", { selector: "label" }));
    await pick(user, "Domain", "Flow");
    await pick(user, "Activity type", "Bug");
    await pick(user, "Work category", "(uncategorized)");
    await user.click(screen.getByText("Activity type", { selector: "label" }));
    const params = search();
    expect(params.get("domain")).toBe("FLO");
    expect(params.get("activityType")).toBe("Bug");
    expect(params.get("workCategory")).toBe("UNCATEGORIZED");

    await user.click(screen.getByRole("radio", { name: "Domain" }));
    expect(search().get("breakdown")).toBe("DOMAIN");
  });

  test("a report with no team × domain split lets the last of the two win", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness controls={{ domain: true, domainExcludesTeam: true }} />, { route: "/reports/wip?teamId=1&accountId=acc-ann" });
    await pick(user, "Domain", "Flow");
    expect(search().get("domain")).toBe("FLO");
    expect(search().has("teamId")).toBe(false);
    expect(search().has("accountId")).toBe(false);
    await pick(user, "Team", "Beta");
    expect(search().get("teamId")).toBe("2");
    expect(search().has("domain")).toBe(false);
  });

  test("clearing the domain leaves the team alone, and a report without the exclusion keeps both", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness controls={{ domain: true }} />, { route: "/reports/throughput?teamId=1&domain=FLO" });
    await pick(user, "Team", "Beta");
    expect(search().get("domain")).toBe("FLO");
    expect(search().get("teamId")).toBe("2");
  });

  test("the WIP controls write by and itemKind; board column is disabled until one team is picked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness controls={{ wipBy: true, itemKind: true }} />, { route: "/reports/wip" });
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Board column" })).toBeDisabled();
    await user.click(screen.getByText("Epics"));
    expect(search().get("itemKind")).toBe("EPIC");
    await user.click(screen.getByText("Status"));
    expect(search().get("by")).toBe("STATUS");
    await pick(user, "Team", "Alpha");
    await waitFor(() => expect(screen.getByRole("radio", { name: "Board column" })).toBeEnabled());
    await user.click(screen.getByText("Board column"));
    expect(search().get("by")).toBe("COLUMN");
    // The team goes: a column choice nothing can honour reads as the default again.
    await user.click(screen.getByLabelText("Clear Team"));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked());
  });

  test("a report that is as of now offers no period at all — the other controls stay", async () => {
    renderWithProviders(<Harness controls={{ noPeriod: true, domain: true }} />, { route: "/reports/aging-wip?lastSprints=3" });
    expect(screen.queryByRole("combobox", { name: "Period" })).not.toBeInTheDocument();
    expect(screen.queryByText("Date range")).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Team" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
  });

  test("a report with no user level offers the team but no member, even with a team chosen", async () => {
    renderWithProviders(<Harness controls={{ noMember: true }} />, { route: "/reports/epic-progress?teamId=1" });
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
  });

  test("a bare report link starts on the remembered team, and the URL says so", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    await waitFor(() => expect(search().get("teamId")).toBe("2"));
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Beta");
  });

  test("a link that carries any filter param stays as written — a unit-level link keeps its level", () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    renderWithProviders(<Harness />, { route: "/reports/velocity?lastSprints=3" });
    expect(search().has("teamId")).toBe(false);
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("");
  });

  test("only the Team control touches the memory: other changes keep it, clearing the team forgets it", async () => {
    const user = userEvent.setup();
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    renderWithProviders(<Harness />, { route: "/reports/velocity?lastSprints=3" });
    await pick(user, "Period", "Last 6 sprints");
    expect(localStorage.getItem("flow.viewSettings.reports.teamId")).toBe("2");

    await pick(user, "Team", "Alpha");
    expect(localStorage.getItem("flow.viewSettings.reports.teamId")).toBe("1");
    await user.click(screen.getByLabelText("Clear Team"));
    expect(localStorage.getItem("flow.viewSettings.reports.teamId")).toBe("null");
  });

  test("a remembered team that no longer exists is left alone", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "99");
    renderWithProviders(<Harness />, { route: "/reports/velocity" });
    expect(search().has("teamId")).toBe(false);
  });
});
