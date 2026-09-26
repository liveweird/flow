import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import EditUser from "./EditUser";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const STORED_USER = { id: 7, name: "Bob Basic", email: "bob@example.com", roles: [] as string[] };

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderEdit(route = "/users/7/edit") {
  return renderWithProviders(
    <Routes>
      <Route path="/users/:id/edit" element={<EditUser />} />
      <Route path="/users" element={<PathProbe />} />
      <Route path="/" element={<PathProbe />} />
    </Routes>,
    { route },
  );
}

function mockGetAndPut(mockFetch: FetchMock, putStatus = 204, putBody: unknown = null) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/users/7") {
      return Promise.resolve(jsonResponse(200, STORED_USER));
    }
    if (method === "PUT" && url === "/api/v1/users/7/language") {
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    if (method === "PUT" && url === "/api/v1/users/7") {
      return Promise.resolve(
        putStatus === 204
          ? new Response(null, { status: 204 })
          : jsonResponse(putStatus, putBody ?? { title: "x", status: putStatus }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("EditUser page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("pre-fills the form, PUTs the edited user with roles, and navigates back", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("Bob Basic"));

    await user.clear(nameInput);
    await user.type(nameInput, "Bob Promoted");
    await user.click(screen.getByLabelText("Administrator"));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/users"));
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/users/7",
    );
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      name: "Bob Promoted",
      email: "bob@example.com",
      roles: ["ADMIN"],
    });
    // The language was untouched — no language PUT rides along.
    expect(
      mockFetch.mock.calls.some(([url]) => url === "/api/v1/users/7/language"),
    ).toBe(false);
  });

  test("a changed language is saved via its own endpoint after the main update", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("Bob Basic"));

    await user.click(screen.getByLabelText("Language", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Polski" }));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/users"));
    const putUrls = mockFetch.mock.calls
      .filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT")
      .map(([url]) => url);
    // Its own endpoint, AFTER the main update (a failed save never half-applies).
    expect(putUrls).toEqual(["/api/v1/users/7", "/api/v1/users/7/language"]);
    const langCall = mockFetch.mock.calls.find(([url]) => url === "/api/v1/users/7/language");
    expect(JSON.parse((langCall![1] as RequestInit).body as string)).toEqual({ language: "pl" });
  });

  test("a last-administrator 409 shows its dedicated message", async () => {
    mockGetAndPut(mockFetch, 409, {
      title: "Conflict",
      status: 409,
      detail: "The last administrator cannot be demoted",
    });
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("Bob Basic"));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/last administrator cannot be removed/i)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("a plain 409 reads as email-in-use", async () => {
    mockGetAndPut(mockFetch, 409);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("Bob Basic"));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/already used by another account/i)).toBeInTheDocument();
  });

  test("404 on load shows the not-found alert with a back link", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "nf", status: 404 })));
    renderEdit();

    expect(await screen.findByText("User not found.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /back to users/i })).toHaveAttribute("href", "/users");
  });

});
