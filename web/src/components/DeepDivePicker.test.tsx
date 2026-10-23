import { useState } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import DeepDivePicker, { type PickerOption, type PickerPage } from "./DeepDivePicker";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { ApiError } from "../api/http";
import type { PickFacts } from "../utils/deepDivePanel";

const OPTIONS: PickerOption[] = [
  { value: "E-1", label: "Epic one", connectionId: 1, domain: "Payments" },
  { value: "E-2", label: "Epic two", connectionId: 1, domain: null },
];

type Props = Partial<Parameters<typeof DeepDivePicker>[0]>;

/** The picker under a controlled value, reporting every change. */
function Harness({ onChange, initial = [], ...props }: Props & { initial?: string[] }) {
  const [value, setValue] = useState<string[]>(initial);
  return (
    <DeepDivePicker
      label="Epics"
      placeholder="Pick epics"
      scope={["epics"]}
      load={async () => ({ options: OPTIONS, total: OPTIONS.length })}
      value={value}
      connectionOf={(v) => OPTIONS.find((option) => option.value === v)?.connectionId}
      max={3}
      labels={{}}
      {...props}
      onChange={(next, facts) => {
        setValue(next);
        onChange?.(next, facts);
      }}
    />
  );
}

const input = () => screen.getByRole("combobox", { name: "Epics" });

describe("DeepDivePicker", () => {
  test("lists what the server answered, says how many of the maximum are used, and hands back the facts of a pick", async () => {
    const onChange = vi.fn<(value: string[], facts: Record<string, PickFacts>) => void>();
    const user = userEvent.setup();
    renderWithProviders(<Harness onChange={onChange} />);
    expect(screen.getByText("0 of 3 selected.")).toBeInTheDocument();
    await user.click(input());
    await user.click(await screen.findByRole("option", { name: "Epic one" }));
    expect(onChange).toHaveBeenCalledWith(["E-1"], { "E-1": { connectionId: 1, label: "Epic one", domain: "Payments" } });
    expect(screen.getByText("1 of 3 selected.")).toBeInTheDocument();
    // a null domain is a fact too (the epic has none), and a name only appears when the answer gave one
    await user.click(input());
    await user.click(await screen.findByRole("option", { name: "Epic two" }));
    expect(onChange).toHaveBeenLastCalledWith(["E-1", "E-2"], {
      "E-1": { connectionId: 1, label: "Epic one", domain: "Payments" },
      "E-2": { connectionId: 1, label: "Epic two", domain: null },
    });
  });

  test("a sprint option's plain name travels with its facts", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        label="Sprints"
        onChange={onChange}
        load={async () => ({ options: [{ value: "5", label: "Sprint 5 · dates", connectionId: 2, name: "Sprint 5" }], total: 1 })}
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "Sprints" }));
    await user.click(await screen.findByRole("option", { name: "Sprint 5 · dates" }));
    expect(onChange).toHaveBeenCalledWith(["5"], { "5": { connectionId: 2, label: "Sprint 5 · dates", name: "Sprint 5" } });
  });

  test("a pick keeps its label when the list no longer contains it: the label the report knows, else the value itself", async () => {
    renderWithProviders(
      <Harness
        initial={["E-9", "E-10"]}
        labels={{ "E-9": "Epic nine (from a report)" }}
        load={async () => ({ options: [], total: 0 })}
      />,
    );
    expect(await screen.findByText("Epic nine (from a report)")).toBeInTheDocument();
    expect(screen.getByText("E-10")).toBeInTheDocument();
  });

  test("typing is debounced into ONE server search, and the typed text is what the server is asked", async () => {
    const load = vi.fn(async (q: string | undefined): Promise<PickerPage> => ({ options: q === "tw" ? [OPTIONS[1]] : OPTIONS, total: 2 }));
    const user = userEvent.setup();
    renderWithProviders(<Harness load={load} />);
    await waitFor(() => expect(load).toHaveBeenCalledWith(undefined));
    await user.type(input(), "tw");
    await waitFor(() => expect(load).toHaveBeenCalledWith("tw"));
    expect(load.mock.calls.map(([q]) => q)).toEqual([undefined, "tw"]);
    expect(await screen.findByRole("option", { name: "Epic two" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Epic one" })).not.toBeInTheDocument();
  });

  test("a cut list says how many of the matches it shows; an answer with no option says nothing was found", async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<Harness load={async () => ({ options: OPTIONS, total: 250 })} />);
    expect(await screen.findByText(/Showing the first 2 of 250 matches/)).toBeInTheDocument();
    unmount();

    renderWithProviders(<Harness load={async () => ({ options: [], total: 0 })} />);
    await user.click(input());
    expect(await screen.findByText("Nothing found")).toBeInTheDocument();
  });

  test("a failed load is said inline in the control's error line", async () => {
    renderWithProviders(
      <Harness
        load={async () => {
          throw new ApiError(503, "unavailable");
        }}
      />,
    );
    expect(await screen.findByText("Load failed (503)")).toBeInTheDocument();
  });

  test("a value placed in two connections is two options, told apart by connection", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        onChange={onChange}
        initial={["S-1"]}
        connectionOf={(value) => (value === "S-1" ? 1 : null)}
        load={async () => ({
          options: [
            { value: "S-1", label: "Sprint A (conn 1)", connectionId: 1 },
            { value: "S-1", label: "Sprint A (conn 2)", connectionId: 2 },
          ],
          total: 2,
        })}
      />,
    );
    await user.click(input());
    // the connection-1 pick is already in; the connection-2 one is a different option
    await user.click(await screen.findByRole("option", { name: "Sprint A (conn 2)" }));
    expect(onChange).toHaveBeenCalledWith(["S-1"], { "S-1": { connectionId: 2, label: "Sprint A (conn 2)" } });
  });

  test("a gated picker is disabled with the hint; a removable one stays live for its picks but requests and lists nothing", async () => {
    const load = vi.fn(async (): Promise<PickerPage> => ({ options: OPTIONS, total: 2 }));
    const { unmount } = renderWithProviders(<Harness enabled={false} disabledHint="Pick a domain first." load={load} />);
    expect(input()).toBeDisabled();
    expect(screen.getByText("0 of 3 selected. Pick a domain first.")).toBeInTheDocument();
    unmount();

    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <Harness enabled={false} removableWhenDisabled initial={["E-1"]} labels={{ "E-1": "Epic one" }} onChange={onChange} load={load} />,
    );
    expect(input()).not.toBeDisabled();
    await user.click(screen.getByLabelText("Clear Epics"));
    expect(onChange).toHaveBeenCalledWith([], {});
    expect(load).not.toHaveBeenCalled();
  });
});
