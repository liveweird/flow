import type { UseQueryResult } from "@tanstack/react-query";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ReportDrillDrawer, { type ReportDrillDrawerProps } from "./ReportDrillDrawer";
import type { ReportDrillItems } from "../api/reportDrill";
import { THROUGHPUT_ITEMS } from "../test/reportFixtures";
import { renderWithProviders, screen, within } from "../test/render";

/** The slice of a query result the drawer reads. */
function result(over: Partial<UseQueryResult<ReportDrillItems>>): UseQueryResult<ReportDrillItems> {
  return { data: undefined, error: null, ...over } as UseQueryResult<ReportDrillItems>;
}

const PEOPLE = [
  { accountId: "acc-ann", label: "Ann Author", position: 1 },
  { accountId: "acc-bob", label: "Bob Builder", position: 2 },
];

function renderDrawer(over: Partial<ReportDrillDrawerProps> = {}) {
  const props: ReportDrillDrawerProps = {
    opened: true,
    onClose: vi.fn(),
    title: "Delivered",
    subtitle: "2026-09-07",
    query: result({ data: THROUGHPUT_ITEMS }),
    unit: "md",
    anchor: "done",
    timeZone: "UTC",
    ...over,
  };
  return { props, ...renderWithProviders(<ReportDrillDrawer {...props} />) };
}

describe("ReportDrillDrawer", () => {
  test("is a named dialog with the server's bar line, one row per item and an accessible close button", () => {
    renderDrawer();
    const dialog = screen.getByRole("dialog", { name: "Delivered · 2026-09-07" });
    expect(within(dialog).getByText("5 items · 12.5 MD")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Close" })).toBeInTheDocument();
    expect(within(dialog).getAllByRole("row")).toHaveLength(6);
    expect(within(dialog).getByRole("region", { name: "Items behind the bar" })).toBeInTheDocument();
    // Issue keys are plain text, never Jira links.
    expect(within(dialog).queryByRole("link")).not.toBeInTheDocument();
  });

  test("the pane is as wide as the screen allows up to a table-sized cap, and its table fills the height left instead of a fixed cap", () => {
    renderDrawer();
    const dialog = screen.getByRole("dialog", { name: "Delivered · 2026-09-07" });
    // Mantine hands the size to the pane as a CSS variable on the drawer's root, which contains the dialog.
    const root = dialog.closest("[style*='--drawer-size']") as HTMLElement;
    expect(root.style.getPropertyValue("--drawer-size")).toBe("calc(min(100vw, 90rem, max(60rem, 75vw)))");
    // The pane is a flex column and its table's scroller takes what is left: no fixed max-height, no dead space under it.
    const region = within(dialog).getByRole("region", { name: "Items behind the bar" });
    expect(region.className).toMatch(/tableScrollFill/);
    expect(region.getAttribute("style") ?? "").not.toContain("max-height");
    expect(region.querySelector("[style*='--table-max-height']")).toBeNull();
    expect(dialog.className).toMatch(/drillDrawerContent/);
    expect(region).toHaveAttribute("tabindex", "0");
  });

  test("the title is a heading with the context as a dimmed subtitle inside it, and there is no footer button back to the chart", () => {
    renderDrawer();
    const dialog = screen.getByRole("dialog", { name: "Delivered · 2026-09-07" });
    const heading = within(dialog).getByRole("heading", { level: 2 });
    expect(heading).toHaveTextContent("Delivered · 2026-09-07");
    expect(heading.className).toMatch(/drillDrawerTitle/);
    expect(within(dialog).getByText("2026-09-07").className).toMatch(/drillDrawerSubtitle/);
    expect(within(dialog).queryByRole("button", { name: "Back to the chart" })).not.toBeInTheDocument();
    // The X is the one button the pane's chrome has.
    expect(within(dialog).getByRole("button", { name: "Close" })).toBeInTheDocument();
  });

  test("without a subtitle the dialog is named by the title alone", () => {
    renderDrawer({ subtitle: undefined });
    expect(screen.getByRole("dialog", { name: "Delivered" })).toBeInTheDocument();
  });

  test("a bar with no MD prints just its item count", () => {
    renderDrawer({ query: result({ data: { ...THROUGHPUT_ITEMS, bar: { count: 1, sumMd: null, from: 1, to: 2 }, items: [], total: 1 } }) });
    expect(screen.getByText("1 item")).toBeInTheDocument();
    expect(screen.getByText("No items behind this bar.")).toBeInTheDocument();
  });

  test("the changed-data note appears only when the chart's count disagrees with the server's", () => {
    const { unmount } = renderDrawer({ chartCount: 5 });
    expect(screen.queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    unmount();
    renderDrawer({ chartCount: 4 });
    expect(screen.getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("no chart count to compare against is no note", () => {
    renderDrawer({ chartCount: undefined });
    expect(screen.queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
  });

  test("the Kind column follows the request, not the rows: shown for a list that may mix tasks and epics, absent otherwise", () => {
    const { unmount } = renderDrawer({ showKind: true });
    const headers = () => screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers()).toContain("Kind");
    expect(THROUGHPUT_ITEMS.items.every((item) => item.itemKind === "TASK")).toBe(true);
    unmount();
    renderDrawer();
    expect(headers()).not.toContain("Kind");
  });

  test("a truncated list says how many of how many are shown", () => {
    renderDrawer({ query: result({ data: { ...THROUGHPUT_ITEMS, total: 731, truncated: true, bar: { ...THROUGHPUT_ITEMS.bar, count: 731 } } }) });
    expect(screen.getByText("Showing the 5 largest of 731 items.")).toBeInTheDocument();
  });

  test("loading and failure are told inside the drawer", () => {
    const { unmount } = renderDrawer({ query: result({ data: undefined }) });
    expect(screen.getByRole("status")).toBeInTheDocument();
    unmount();
    renderDrawer({ query: result({ error: new Error("boom") }) });
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.queryByText("boom")).not.toBeInTheDocument();
  });

  test("the X and Escape ask to close", async () => {
    const user = userEvent.setup();
    const { props } = renderDrawer();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await user.keyboard("{Escape}");
    expect(props.onClose).toHaveBeenCalledTimes(2);
  });

  test("nothing is rendered while closed", () => {
    renderDrawer({ opened: false });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  describe("the person select", () => {
    test("is offered from two people on, numbered like the chart's legend, and reports the choice (Everyone clears it)", async () => {
      const user = userEvent.setup();
      const onSegmentChange = vi.fn();
      renderDrawer({ people: PEOPLE, segmentAccountId: "acc-bob", onSegmentChange });
      const select = screen.getByRole("combobox", { name: "Show the items of" });
      expect(select).toHaveValue("2. Bob Builder");
      await user.click(select);
      expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Everyone", "1. Ann Author", "2. Bob Builder"]);
      await user.click(screen.getByRole("option", { name: "1. Ann Author" }));
      expect(onSegmentChange).toHaveBeenLastCalledWith("acc-ann");
      await user.click(select);
      await user.click(screen.getByRole("option", { name: "Everyone" }));
      expect(onSegmentChange).toHaveBeenLastCalledWith(undefined);
    });

    test("offers no Everyone where every list is one person's (Compare people): just the two people", async () => {
      const user = userEvent.setup();
      const onSegmentChange = vi.fn();
      renderDrawer({ people: PEOPLE, segmentAccountId: "acc-bob", onSegmentChange, allowEveryone: false });
      const select = screen.getByRole("combobox", { name: "Show the items of" });
      expect(select).toHaveValue("2. Bob Builder");
      await user.click(select);
      expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["1. Ann Author", "2. Bob Builder"]);
      await user.click(screen.getByRole("option", { name: "1. Ann Author" }));
      expect(onSegmentChange).toHaveBeenLastCalledWith("acc-ann");
    });

    test("shows Everyone for the whole bar, and is absent for one person or none", () => {
      const { unmount } = renderDrawer({ people: PEOPLE, onSegmentChange: vi.fn() });
      expect(screen.getByRole("combobox", { name: "Show the items of" })).toHaveValue("Everyone");
      unmount();
      const one = renderDrawer({ people: PEOPLE.slice(0, 1), onSegmentChange: vi.fn() });
      expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
      one.unmount();
      renderDrawer({});
      expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    });

    test("stays on screen while the narrowed list loads", () => {
      renderDrawer({ people: PEOPLE, onSegmentChange: vi.fn(), query: result({ data: undefined }) });
      expect(screen.getByRole("combobox", { name: "Show the items of" })).toBeInTheDocument();
      expect(screen.getByRole("status")).toBeInTheDocument();
    });
  });
});
