import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import PublicHolidays from "./PublicHolidays";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { localYear } from "../utils/publicHolidays";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

// The page opens on the current year, so the fixture is built around it.
const YEAR = Number(localYear());
type Row = { id: number; date: string; name: string };
const NEW_YEAR: Row = { id: 1, date: `${YEAR}-01-01`, name: "New Year's Day" };
const EPIPHANY: Row = { id: 2, date: `${YEAR}-01-06`, name: "Epiphany" };
const CHRISTMAS_LAST: Row = { id: 3, date: `${YEAR - 1}-12-25`, name: "Christmas Day" };

function serve(mockFetch: FetchMock, rows: Row[], reply?: { status: number; body?: unknown }) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/public-holidays") return Promise.resolve(jsonResponse(200, { items: rows }));
    if (reply && url.startsWith("/api/v1/public-holidays")) {
      return Promise.resolve(reply.body === undefined ? new Response(null, { status: reply.status }) : jsonResponse(reply.status, reply.body));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

const writes = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([, init]) => ((init as RequestInit | undefined)?.method ?? "GET") !== "GET");

describe("PublicHolidays page", () => {
  let mockFetch: FetchMock;

  function signIn(role: "ADMIN" | "USER") {
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify([role]));
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    // isolate:false — a spy on the shared `notifications` singleton must not outlive its test.
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a USER reads the current year's holidays with their weekday, and gets no add strip and no delete", async () => {
    signIn("USER");
    serve(mockFetch, [CHRISTMAS_LAST, NEW_YEAR, EPIPHANY]);
    renderWithProviders(<PublicHolidays />);

    const table = await screen.findByRole("table", { name: `Public holidays of ${YEAR}` });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText(NEW_YEAR.date)).toBeInTheDocument();
    expect(within(rows[0]).getByText(/^[A-Z][a-z]+day$/)).toBeInTheDocument();
    expect(within(rows[0]).getByText("New Year's Day")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Epiphany")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add holiday" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete the holiday/ })).not.toBeInTheDocument();
    expect(screen.getByText(/The list is maintained by administrators/)).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Public holidays"]]);
  });

  test("the year picker offers the years of the data plus the current one, and filters the table client-side", async () => {
    signIn("USER");
    serve(mockFetch, [CHRISTMAS_LAST, NEW_YEAR]);
    const user = userEvent.setup();
    renderWithProviders(<PublicHolidays />);

    await screen.findByRole("table", { name: `Public holidays of ${YEAR}` });
    await user.click(screen.getByRole("combobox", { name: "Year" }));
    const options = (await screen.findAllByRole("option")).map((o) => o.textContent);
    expect(options).toEqual([String(YEAR - 1), String(YEAR)]);
    await user.click(screen.getByRole("option", { name: String(YEAR - 1) }));

    const table = await screen.findByRole("table", { name: `Public holidays of ${YEAR - 1}` });
    expect(within(table).getByText("Christmas Day")).toBeInTheDocument();
    expect(screen.queryByText("New Year's Day")).not.toBeInTheDocument();
    // One fetch: switching years never goes back to the server.
    expect(mockFetch.mock.calls.filter(([url]) => url === "/api/v1/public-holidays")).toHaveLength(1);
  });

  test("a year without holidays says so (and the current year is always offered)", async () => {
    signIn("USER");
    serve(mockFetch, [CHRISTMAS_LAST]);
    renderWithProviders(<PublicHolidays />);
    expect(await screen.findByText(`There are no public holidays in ${YEAR}.`)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("an admin adds a holiday: POST, a toast, the list refetched on the new row's year", async () => {
    signIn("ADMIN");
    const rows: Row[] = [NEW_YEAR];
    serve(mockFetch, rows);
    const user = userEvent.setup();
    const toast = vi.spyOn(notifications, "show");
    renderWithProviders(<PublicHolidays />);
    await screen.findByRole("table", { name: `Public holidays of ${YEAR}` });

    expect(screen.getByRole("button", { name: "Add holiday" })).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Date" }), "not a date");
    expect(screen.getByText("Enter a real date as YYYY-MM-DD.")).toBeInTheDocument();
    await user.clear(screen.getByRole("textbox", { name: "Date" }));
    await user.type(screen.getByRole("textbox", { name: "Date" }), `${YEAR + 1}-03-04`);
    await user.type(screen.getByRole("textbox", { name: "Name" }), "  E2E Holiday ");

    const created: Row = { id: 9, date: `${YEAR + 1}-03-04`, name: "E2E Holiday" };
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        rows.push(created);
        return Promise.resolve(jsonResponse(201, created));
      }
      return Promise.resolve(jsonResponse(200, { items: rows }));
    });
    await user.click(screen.getByRole("button", { name: "Add holiday" }));

    await waitFor(() => expect(writes(mockFetch)).toHaveLength(1));
    expect(JSON.parse((writes(mockFetch)[0][1] as RequestInit).body as string)).toEqual({ date: `${YEAR + 1}-03-04`, name: "E2E Holiday" });
    expect(await screen.findByRole("table", { name: `Public holidays of ${YEAR + 1}` })).toBeInTheDocument();
    expect(screen.getByText("E2E Holiday")).toBeInTheDocument();
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Public holiday added — reports re-derive shortly" }));
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("");
  });

  test("Enter in the name field submits the add form; an incomplete form does not submit", async () => {
    signIn("ADMIN");
    const rows: Row[] = [NEW_YEAR];
    serve(mockFetch, rows);
    const user = userEvent.setup();
    renderWithProviders(<PublicHolidays />);
    await screen.findByRole("table", { name: `Public holidays of ${YEAR}` });

    await user.type(screen.getByRole("textbox", { name: "Name" }), "Midsummer{Enter}");
    expect(writes(mockFetch)).toHaveLength(0);

    const created: Row = { id: 9, date: `${YEAR}-06-24`, name: "Midsummer" };
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        rows.push(created);
        return Promise.resolve(jsonResponse(201, created));
      }
      return Promise.resolve(jsonResponse(200, { items: rows }));
    });
    await user.type(screen.getByRole("textbox", { name: "Date" }), `${created.date}{Enter}`);

    await waitFor(() => expect(writes(mockFetch)).toHaveLength(1));
    expect(JSON.parse((writes(mockFetch)[0][1] as RequestInit).body as string)).toEqual({ date: created.date, name: "Midsummer" });
  });

  test.each([
    [409, "A holiday already exists on this date."],
    [400, "The holiday is invalid — check the date and the name."],
    [403, "Only administrators can change the public holidays."],
  ])("a %i on add shows its fixed message inline, never a toast", async (status, message) => {
    signIn("ADMIN");
    serve(mockFetch, [NEW_YEAR], { status, body: { title: "x", status, detail: "server words", instance: "/x", type: "about:blank" } });
    const user = userEvent.setup();
    const toast = vi.spyOn(notifications, "show");
    renderWithProviders(<PublicHolidays />);
    await screen.findByRole("table", { name: `Public holidays of ${YEAR}` });

    await user.type(screen.getByRole("textbox", { name: "Date" }), NEW_YEAR.date);
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Again");
    await user.click(screen.getByRole("button", { name: "Add holiday" }));

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText("server words")).not.toBeInTheDocument();
    expect(toast).not.toHaveBeenCalled();
  });

  test("an admin deletes a holiday behind a confirmation: DELETE by id, then a toast", async () => {
    signIn("ADMIN");
    const rows: Row[] = [NEW_YEAR, EPIPHANY];
    serve(mockFetch, rows);
    const user = userEvent.setup();
    const toast = vi.spyOn(notifications, "show");
    renderWithProviders(<PublicHolidays />);

    await user.click(await screen.findByRole("button", { name: "Delete the holiday Epiphany" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete this holiday?" });
    expect(within(dialog).getByText(new RegExp(`${EPIPHANY.date} · Epiphany`))).toBeInTheDocument();
    expect(writes(mockFetch)).toHaveLength(0);

    mockFetch.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === "DELETE") {
        rows.splice(1, 1);
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(200, { items: rows }));
    });
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(writes(mockFetch)).toHaveLength(1));
    expect(writes(mockFetch)[0][0]).toBe(`/api/v1/public-holidays/${EPIPHANY.id}`);
    await waitFor(() => expect(screen.queryByText("Epiphany")).not.toBeInTheDocument());
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Public holiday deleted — reports re-derive shortly" }));
  });

  test("a failed delete says the delete failed (not a save), with the status", async () => {
    signIn("ADMIN");
    serve(mockFetch, [NEW_YEAR], { status: 500, body: { title: "x", status: 500 } });
    const user = userEvent.setup();
    renderWithProviders(<PublicHolidays />);

    await user.click(await screen.findByRole("button", { name: "Delete the holiday New Year's Day" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("Delete failed (500)")).toBeInTheDocument();
    expect(screen.queryByText(/Save failed/)).not.toBeInTheDocument();
  });

  test("cancelling the confirmation deletes nothing, and a 404 on delete says the holiday is gone", async () => {
    signIn("ADMIN");
    serve(mockFetch, [NEW_YEAR], { status: 404, body: { title: "Not Found", status: 404 } });
    const user = userEvent.setup();
    renderWithProviders(<PublicHolidays />);

    await user.click(await screen.findByRole("button", { name: "Delete the holiday New Year's Day" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes(mockFetch)).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Delete the holiday New Year's Day" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("This holiday no longer exists.")).toBeInTheDocument();
  });

  test("a failed load is an inline alert with the status", async () => {
    signIn("USER");
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 })));
    renderWithProviders(<PublicHolidays />);
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });
});
