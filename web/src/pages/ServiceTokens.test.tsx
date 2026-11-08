import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import ServiceTokens from "./ServiceTokens";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const SECRET = `flow_int_${"a1B2c3D4e5".repeat(4)}abc`;

type FetchMock = ReturnType<typeof vi.fn>;
type Row = {
  id: number;
  name: string;
  createdAt: number;
  createdByName: string;
  expiresAt: number | null;
  lastUsedAt: number | null;
  revoked: boolean;
  revokedAt: number | null;
  revokedByName: string | null;
};

const row = (partial: Partial<Row> & Pick<Row, "id" | "name">): Row => ({
  createdAt: Date.now() - 30 * DAY,
  createdByName: "Admin Flow",
  expiresAt: null,
  lastUsedAt: null,
  revoked: false,
  revokedAt: null,
  revokedByName: null,
  ...partial,
});

// Built per test (not at module load): the ages are measured from the frozen clock the suite sets in `beforeEach`.
const ACTIVE = () => row({ id: 1, name: "Astrolabe", lastUsedAt: Date.now() - 5 * MINUTE });
const EXPIRED = () => row({ id: 2, name: "Old sync", expiresAt: Date.now() - 10 * DAY, lastUsedAt: Date.now() - 3 * HOUR });
const REVOKED = () => row({ id: 3, name: "Retired", revoked: true, revokedAt: Date.now() - DAY, revokedByName: "Admin Flow", expiresAt: Date.now() + 400 * DAY });
const FUTURE = () => row({ id: 4, name: "Planned", expiresAt: Date.UTC(2099, 0, 31, 12) });

const writes = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([, init]) => ((init as RequestInit | undefined)?.method ?? "GET") !== "GET");
const bodyOf = (call: unknown[]) => JSON.parse((call[1] as RequestInit).body as string) as Record<string, unknown>;

function serve(mockFetch: FetchMock, items: Row[], reply?: (url: string, init: RequestInit | undefined) => Response | undefined) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const custom = reply?.(url, init);
    if (custom) return Promise.resolve(custom);
    if ((init?.method ?? "GET") === "GET" && url === "/api/v1/service-tokens") return Promise.resolve(jsonResponse(200, { items }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

describe("ServiceTokens page", () => {
  let mockFetch: FetchMock;

  function signIn(role: "ADMIN" | "USER" = "ADMIN") {
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify([role]));
  }

  beforeEach(() => {
    // Only `Date` is faked (timers stay real for userEvent/waitFor): the relative ages and expiries are exact, not wall-clock slack.
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.UTC(2026, 10, 8, 12));
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
  });

  afterEach(() => {
    // isolate:false: a spy on the shared `notifications` singleton and a stubbed global must not outlive their test.
    vi.restoreAllMocks();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the table lists each token with its derived status, expiry and last use; a revoked row has no action", async () => {
    serve(mockFetch, [ACTIVE(), EXPIRED(), REVOKED(), FUTURE()]);
    renderWithProviders(<ServiceTokens />);

    const table = await screen.findByRole("table", { name: "Service tokens" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(4);

    expect(within(rows[0]).getByText("Astrolabe")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Active")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Admin Flow")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Never")).toBeInTheDocument();
    expect(within(rows[0]).getByText("5 min ago")).toBeInTheDocument();

    // An expired token is unrevoked: it still offers Revoke (it keeps its name until then).
    expect(within(rows[1]).getByText("Expired")).toBeInTheDocument();
    expect(within(rows[1]).getByText("3 h ago")).toBeInTheDocument();
    expect(within(rows[1]).getByRole("button", { name: "Revoke the token Old sync" })).toBeInTheDocument();

    // Revoked wins over a future expiry, and the row has no action at all.
    expect(within(rows[2]).getByText("Revoked")).toBeInTheDocument();
    expect(within(rows[2]).queryByRole("button")).not.toBeInTheDocument();

    expect(within(rows[3]).getByText("Active")).toBeInTheDocument();
    expect(within(rows[3]).getByText("2099-01-31")).toBeInTheDocument();
    expect(within(rows[3]).getByText("Never", { selector: "p" })).toBeInTheDocument();

    expect(screen.getByText(/The secret is shown once\. An expired token keeps its name until you revoke it\./)).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Service tokens"]]);
  });

  test("a token used a moment ago reads 'Just now', with the exact time on hover", async () => {
    serve(mockFetch, [row({ id: 1, name: "Fresh", lastUsedAt: Date.now() - 1_000 })]);
    renderWithProviders(<ServiceTokens />);
    const cell = await screen.findByText("Just now");
    expect(cell.getAttribute("title")).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  test("no tokens yet is an empty state", async () => {
    serve(mockFetch, []);
    renderWithProviders(<ServiceTokens />);
    expect(await screen.findByText("No service tokens yet. Create the first one above.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("a failed load is a red alert, not a toast", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    renderWithProviders(<ServiceTokens />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not load the service tokens");
    expect(alert).toHaveTextContent("Load failed (500)");
  });

  test("creating a token sends the trimmed name, reveals the secret once (masked, then shown, copyable) and refreshes the list", async () => {
    const user = userEvent.setup();
    const items: Row[] = [ACTIVE()];
    const created = row({ id: 9, name: "Astrolabe 2" });
    serve(mockFetch, items, (url, init) => {
      if (init?.method === "POST" && url === "/api/v1/service-tokens") {
        items.push(created);
        return jsonResponse(201, { token: created, secret: SECRET });
      }
      return undefined;
    });
    const toast = vi.spyOn(notifications, "show");
    renderWithProviders(<ServiceTokens />);
    await screen.findByRole("table", { name: "Service tokens" });

    expect(screen.getByRole("button", { name: "Create token" })).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Name" }), "  Astrolabe 2 ");
    expect(screen.getByText("14 / 100")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create token" }));

    await waitFor(() => expect(writes(mockFetch)).toHaveLength(1));
    // "Never" sends no expiry at all.
    expect(bodyOf(writes(mockFetch)[0])).toEqual({ name: "Astrolabe 2" });

    expect(await screen.findByText("Token created: Astrolabe 2")).toBeInTheDocument();
    expect(screen.getByText(/Copy it now, it is never shown again\./)).toBeInTheDocument();
    // Masked until the eye toggle; the plaintext is not in the page before that.
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument();
    expect(screen.getByText("*".repeat(SECRET.length))).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show token" }));
    expect(screen.getByText(SECRET)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Copy token" }));
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
    expect(await navigator.clipboard.readText()).toBe(SECRET);

    // The list refetched with the new row, the name field is clear, and no toast (the panel is the confirmation).
    expect(await screen.findByText("Astrolabe 2", { selector: "p" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("");
    expect(toast).not.toHaveBeenCalled();

    // Dismissing the panel drops the secret for good.
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument();
    expect(screen.queryByText("Token created: Astrolabe 2")).not.toBeInTheDocument();
  });

  test("the expiry choice becomes an absolute expiresAt: 90 days, 1 year, 2 years from now", async () => {
    const user = userEvent.setup();
    // The reply echoes the posted name with an id of its own, so every create is told apart from the previous one.
    serve(mockFetch, [], (url, init) => {
      if (init?.method !== "POST" || url !== "/api/v1/service-tokens") return undefined;
      const { name } = JSON.parse(init.body as string) as { name: string };
      return jsonResponse(201, { token: row({ id: 10 + writes(mockFetch).length, name }), secret: SECRET });
    });
    renderWithProviders(<ServiceTokens />);
    await screen.findByText("No service tokens yet. Create the first one above.");

    for (const [index, [label, days]] of ([["In 90 days", 90], ["In 1 year", 365], ["In 2 years", 730]] as const).entries()) {
      const name = `token ${index + 1}`;
      await user.type(screen.getByRole("textbox", { name: "Name" }), name);
      await user.click(screen.getByRole("combobox", { name: "Expires" }));
      await user.click(await screen.findByRole("option", { name: label }));
      await user.click(screen.getByRole("button", { name: "Create token" }));
      await waitFor(() => expect(writes(mockFetch)).toHaveLength(index + 1));
      // The clock is frozen, so the expiry is exact.
      expect(bodyOf(writes(mockFetch)[index])).toEqual({ name, expiresAt: Date.now() + days * DAY });
      // This create's panel is up and its reset has landed (name field empty, button no longer loading) before the next one types.
      await screen.findByText(`Token created: ${name}`);
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(""));
      await waitFor(() => expect(screen.getByRole("button", { name: "Create token" })).not.toHaveAttribute("data-loading"));
    }
    expect(screen.queryByText("Token created: token 1")).not.toBeInTheDocument();
  });

  test("a name clash (409) is an inline red message naming the expired-token rule; no panel opens", async () => {
    const user = userEvent.setup();
    serve(mockFetch, [ACTIVE()], (url, init) =>
      init?.method === "POST" && url === "/api/v1/service-tokens"
        ? jsonResponse(409, { title: "Conflict", status: 409, detail: "A service token with this name already exists and is not revoked; an expired token keeps its name until it is revoked" })
        : undefined,
    );
    renderWithProviders(<ServiceTokens />);
    await screen.findByRole("table", { name: "Service tokens" });

    await user.type(screen.getByRole("textbox", { name: "Name" }), "Astrolabe");
    await user.click(screen.getByRole("button", { name: "Create token" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("A token with this name already exists and is not revoked.");
    expect(alert).toHaveTextContent("An expired token keeps its name until you revoke it");
    expect(screen.queryByText(/Token created/)).not.toBeInTheDocument();
    // The typed name stays so it can be edited.
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Astrolabe");
  });

  test("a rejected name (400) is a general inline message, not a length hint", async () => {
    const user = userEvent.setup();
    serve(mockFetch, [], (url, init) =>
      init?.method === "POST" && url === "/api/v1/service-tokens" ? jsonResponse(400, { title: "Bad Request", status: 400 }) : undefined,
    );
    renderWithProviders(<ServiceTokens />);
    await screen.findByText("No service tokens yet. Create the first one above.");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "tab\tname");
    await user.click(screen.getByRole("button", { name: "Create token" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The name is invalid: 1–100 characters, a single line, no control characters.");
  });

  test("revoking asks first, then POSTs the revoke, toasts and refreshes; the revoked row loses its action", async () => {
    const user = userEvent.setup();
    const items: Row[] = [ACTIVE()];
    serve(mockFetch, items, (url, init) => {
      if (init?.method === "POST" && url === "/api/v1/service-tokens/1/revoke") {
        items[0] = { ...ACTIVE(), revoked: true, revokedAt: Date.now(), revokedByName: "Admin Flow" };
        return new Response(null, { status: 204 });
      }
      return undefined;
    });
    const toast = vi.spyOn(notifications, "show");
    renderWithProviders(<ServiceTokens />);
    await screen.findByRole("table", { name: "Service tokens" });

    await user.click(screen.getByRole("button", { name: "Revoke the token Astrolabe" }));
    const dialog = await screen.findByRole("dialog", { name: "Revoke this token?" });
    expect(within(dialog).getByText(/"Astrolabe" stops working immediately/)).toBeInTheDocument();
    // Cancel changes nothing.
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(writes(mockFetch)).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Revoke the token Astrolabe" }));
    await user.click(await within(await screen.findByRole("dialog", { name: "Revoke this token?" })).findByRole("button", { name: "Revoke" }));

    await waitFor(() => expect(writes(mockFetch)).toHaveLength(1));
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Service token revoked" }));
    expect(await screen.findByText("Revoked")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revoke the token Astrolabe" })).not.toBeInTheDocument();
  });

  test("revoking the token whose secret is on screen drops the panel", async () => {
    const user = userEvent.setup();
    const created = row({ id: 9, name: "Just made" });
    const items: Row[] = [];
    serve(mockFetch, items, (url, init) => {
      if (init?.method === "POST" && url === "/api/v1/service-tokens") {
        items.push(created);
        return jsonResponse(201, { token: created, secret: SECRET });
      }
      if (init?.method === "POST" && url === "/api/v1/service-tokens/9/revoke") return new Response(null, { status: 204 });
      return undefined;
    });
    renderWithProviders(<ServiceTokens />);
    await screen.findByText("No service tokens yet. Create the first one above.");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Just made");
    await user.click(screen.getByRole("button", { name: "Create token" }));
    await screen.findByText("Token created: Just made");

    await user.click(await screen.findByRole("button", { name: "Revoke the token Just made" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Revoke this token?" })).getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(screen.queryByText("Token created: Just made")).not.toBeInTheDocument());
  });

  test("a revoke the server refuses (409 already revoked) stays in the dialog as a red message", async () => {
    const user = userEvent.setup();
    serve(mockFetch, [ACTIVE()], (url, init) =>
      init?.method === "POST" && url === "/api/v1/service-tokens/1/revoke" ? jsonResponse(409, { title: "Conflict", status: 409 }) : undefined,
    );
    renderWithProviders(<ServiceTokens />);
    await screen.findByRole("table", { name: "Service tokens" });

    await user.click(screen.getByRole("button", { name: "Revoke the token Astrolabe" }));
    const dialog = await screen.findByRole("dialog", { name: "Revoke this token?" });
    await user.click(within(dialog).getByRole("button", { name: "Revoke" }));

    expect(await within(dialog).findByText("This token is already revoked.")).toBeInTheDocument();
    expect(within(dialog).getByText("Could not revoke the token")).toBeInTheDocument();
  });

  test("a non-admin session fetches nothing", async () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["USER"]));
    serve(mockFetch, [ACTIVE()]);
    renderWithProviders(<ServiceTokens />);
    await screen.findByRole("heading", { name: "Service tokens" });
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
