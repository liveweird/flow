import { useState } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import DomainStageOverrides from "./DomainStageOverrides";
import { renderWithProviders } from "../test/render";
import type { DomainRowState, DomainStageRowState, StatusRowState } from "../utils/metricsConfigForm";

const STATUSES: StatusRowState[] = [
  { statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "IN_PROGRESS", blocked: false, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
  { statusId: "10", name: "Done", category: "DONE", stage: "", blocked: false, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
];
const DOMAINS: DomainRowState[] = [
  { projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: "" },
  { projectKey: "OPS", domainKey: "OPS", domainName: "OPS", ownerTeamId: "" },
];

/** Holds the overrides the way the page does, and exposes the latest list for assertions. */
function Harness({
  domains = DOMAINS,
  initial = [],
  listed = STATUSES,
  onState,
}: {
  domains?: DomainRowState[];
  listed?: StatusRowState[];
  initial?: DomainStageRowState[];
  onState: (next: DomainStageRowState[]) => void;
}) {
  const [overrides, setOverrides] = useState(initial);
  return (
    <DomainStageOverrides
      statuses={STATUSES}
      listedStatuses={listed}
      domains={domains}
      overrides={overrides}
      onChange={(next) => {
        setOverrides(next);
        onState(next);
      }}
    />
  );
}

describe("DomainStageOverrides", () => {
  test("picks a second domain and overrides only there; the first domain's override stays and the picker counts it", async () => {
    let latest: DomainStageRowState[] = [];
    const user = userEvent.setup();
    renderWithProviders(
      <Harness initial={[{ domainKey: "ENG", statusId: "3", stage: "DONE" }]} onState={(next) => (latest = next)} />,
    );

    const picker = (await screen.findByRole("combobox", { name: "Domain" })) as HTMLInputElement;
    expect(picker.value).toBe("ENG (1 overridden)");
    await user.click(picker);
    await user.click(await screen.findByRole("option", { name: "OPS" }));

    const opsStage = (await screen.findByRole("combobox", { name: "Stage for Done in domain OPS" })) as HTMLInputElement;
    expect(opsStage.value).toBe("");
    await user.click(opsStage);
    await user.click(await screen.findByRole("option", { name: "Not started" }));

    await waitFor(() => expect(screen.getByText("Overrides in total: 2")).toBeInTheDocument());
    expect(latest).toEqual(
      expect.arrayContaining([
        { domainKey: "ENG", statusId: "3", stage: "DONE" },
        { domainKey: "OPS", statusId: "10", stage: "NOT_STARTED" },
      ]),
    );
  });

  test("re-choosing the selected stage deselects it, which removes that override", async () => {
    let latest: DomainStageRowState[] = [{ domainKey: "ENG", statusId: "3", stage: "DONE" }];
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={latest} onState={(next) => (latest = next)} />);

    const stage = (await screen.findByRole("combobox", { name: "Stage for In Progress in domain ENG" })) as HTMLInputElement;
    expect(stage.value).toBe("Done");
    await user.click(stage);
    await user.click(await screen.findByRole("option", { name: "Done" })); // re-choosing the selected option deselects it
    await waitFor(() => expect(latest).toEqual([]));
    expect(screen.getByText("No overrides: every domain uses the stages above.")).toBeInTheDocument();
  });

  test("shows a prompt instead of the table when no domain exists", async () => {
    renderWithProviders(<Harness domains={[]} onState={() => undefined} />);
    expect(await screen.findByText("No domains yet — define them on the Domains tab first.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Domain" })).not.toBeInTheDocument();
  });

  test("an override whose domain is no longer defined is flagged and still removable", async () => {
    let latest: DomainStageRowState[] = [{ domainKey: "GONE", statusId: "3", stage: "DONE" }];
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={latest} onState={(next) => (latest = next)} />);

    expect(await screen.findByText(/"GONE" is no longer a domain/)).toBeInTheDocument();
    await user.click(await screen.findByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "GONE (no longer a domain) (1 overridden)" }));
    await user.click(await screen.findByRole("button", { name: "Remove the override for In Progress in domain GONE" }));

    await waitFor(() => expect(latest).toEqual([]));
    expect(screen.queryByText(/is no longer a domain/)).not.toBeInTheDocument();
  });

  test("an override whose status is no longer reported is listed with a remove action", async () => {
    let latest: DomainStageRowState[] = [
      { domainKey: "ENG", statusId: "999", stage: "DONE" },
      { domainKey: "ENG", statusId: "3", stage: "DONE" },
    ];
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={latest} onState={(next) => (latest = next)} />);

    expect(await screen.findByText(/Status 999 is no longer reported by this connection.*domain ENG \(Done\)/)).toBeInTheDocument();
    expect(screen.getByText("Overrides in total: 2")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove the override for unknown status 999 in domain ENG" }));

    await waitFor(() => expect(latest).toEqual([{ domainKey: "ENG", statusId: "3", stage: "DONE" }]));
    expect(screen.queryByText(/no longer reported/)).not.toBeInTheDocument();
  });

  test("lists only the listed statuses, and a status left out of the table is not mistaken for one the connection dropped", async () => {
    renderWithProviders(
      <Harness listed={[STATUSES[0]]} initial={[{ domainKey: "ENG", statusId: "10", stage: "DONE" }]} onState={() => undefined} />,
    );

    expect(await screen.findByRole("combobox", { name: "Stage for In Progress in domain ENG" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Stage for Done in domain ENG" })).not.toBeInTheDocument();
    expect(screen.queryByText(/is no longer reported/)).not.toBeInTheDocument();
    expect(screen.getByText("Overrides in total: 1")).toBeInTheDocument();
  });
});
