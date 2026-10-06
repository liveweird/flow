import { useState } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import DomainStageOverrides from "./DomainStageOverrides";
import { renderWithProviders } from "../test/render";
import type { DomainRowState, DomainStageRowState, StatusRowState } from "../utils/metricsConfigForm";

const STATUSES: StatusRowState[] = [
  { statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "IN_PROGRESS", blocked: false },
  { statusId: "10", name: "Done", category: "DONE", stage: "", blocked: false },
];
const DOMAINS: DomainRowState[] = [
  { projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: "" },
  { projectKey: "OPS", domainKey: "OPS", domainName: "OPS", ownerTeamId: "" },
];

/** Holds the overrides the way the page does, and exposes the latest list for assertions. */
function Harness({
  domains = DOMAINS,
  initial = [],
  onState,
}: {
  domains?: DomainRowState[];
  initial?: DomainStageRowState[];
  onState: (next: DomainStageRowState[]) => void;
}) {
  const [overrides, setOverrides] = useState(initial);
  return (
    <DomainStageOverrides
      statuses={STATUSES}
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
});
