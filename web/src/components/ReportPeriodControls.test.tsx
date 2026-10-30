import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { DatesProvider } from "@mantine/dates";
import "@mantine/dates/styles.css";
import ReportPeriodControls from "./ReportPeriodControls";
import { renderWithProviders, screen } from "../test/render";

const TODAY = "2026-10-30";

function Controls({ horizonFrom }: { horizonFrom?: string | null }) {
  return (
    <DatesProvider settings={{ locale: "en", firstDayOfWeek: 1 }}>
      <ReportPeriodControls
        filter={{ from: "2026-04-20", to: "2026-05-10" }}
        timeZone="UTC"
        horizonFrom={horizonFrom}
        teams={[]}
        onChange={() => {}}
      />
    </DatesProvider>
  );
}

afterEach(() => vi.useRealTimers());

describe("ReportPeriodControls and the history horizon", () => {
  test("with a horizon the custom range picker offers no day before its first day", async () => {
    const user = userEvent.setup();
    vi.useFakeTimers({ toFake: ["Date"], now: new Date(`${TODAY}T12:00:00Z`) });
    renderWithProviders(<Controls horizonFrom="2026-04-25" />);
    await user.click(await screen.findByRole("button", { name: "Date range" }));
    expect(screen.getByRole("button", { name: "24 April 2026" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "25 April 2026" })).toBeEnabled();
  });

  test("without a horizon every earlier day stays selectable", async () => {
    const user = userEvent.setup();
    vi.useFakeTimers({ toFake: ["Date"], now: new Date(`${TODAY}T12:00:00Z`) });
    renderWithProviders(<Controls horizonFrom={null} />);
    await user.click(await screen.findByRole("button", { name: "Date range" }));
    expect(screen.getByRole("button", { name: "24 April 2026" })).toBeEnabled();
  });
});
