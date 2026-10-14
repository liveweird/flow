import userEvent from "@testing-library/user-event";
import { screen } from "./render";

/**
 * Opens the report filter bar's collapsible panel (it starts collapsed — `FilterPanel`) and waits
 * for it: the toggle is found by name (its badge text rides along, so a prefix match), clicked only
 * when still closed, so calling it twice, or after a remembered open state, never folds it again.
 */
export async function openReportFilters(user: ReturnType<typeof userEvent.setup> = userEvent.setup()): Promise<void> {
  const toggle = await screen.findByRole("button", { name: /^Filters/ });
  if (toggle.getAttribute("aria-expanded") !== "true") await user.click(toggle);
  await screen.findByRole("group", { name: "Report filters" });
}
