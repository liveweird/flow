import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import CreateUser from "./CreateUser";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

const PASSWORD_RE = /^[A-Za-z0-9_-]{16}$/;

type FetchMock = ReturnType<typeof vi.fn>;

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/users/new" element={<CreateUser />} />
      <Route path="/users" element={<PathProbe />} />
      <Route path="/" element={<PathProbe />} />
    </Routes>,
    { route: "/users/new" },
  );
}

function mockPost(mockFetch: FetchMock, status = 201) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if ((init?.method ?? "GET") === "POST" && url === "/api/v1/users") {
      return Promise.resolve(
        status === 201
          ? jsonResponse(201, { id: 9, name: "Alice", email: "alice@example.com", roles: [] })
          : jsonResponse(status, { title: "x", status }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function postBodyOf(mockFetch: FetchMock): Record<string, unknown> {
  const call = mockFetch.mock.calls.find(
    ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/users",
  );
  return JSON.parse((call![1] as RequestInit).body as string) as Record<string, unknown>;
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, admin = false) {
  await user.type(screen.getByLabelText(/^name( \*)?$/i), "Alice");
  await user.type(screen.getByLabelText(/^email( \*)?$/i), "alice@example.com");
  if (admin) await user.click(screen.getByLabelText("Administrator"));
  await user.click(screen.getByRole("button", { name: /^create$/i }));
}

describe("CreateUser page", () => {
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

  test("posts a generated password and reveals it once in the confirmation modal", async () => {
    mockPost(mockFetch);
    const user = userEvent.setup();
    renderCreate();

    // No password inputs anywhere on the form.
    expect(screen.queryByLabelText(/password/i)).not.toBeInTheDocument();

    await fillAndSubmit(user);
    expect(await screen.findByText("User created")).toBeInTheDocument();

    const body = postBodyOf(mockFetch);
    expect(body).toEqual({
      name: "Alice",
      email: "alice@example.com",
      password: expect.stringMatching(PASSWORD_RE),
      roles: [],
      language: "en",
    });
    const password = body.password as string;

    // Masked by default; the eye toggle reveals and hides it again.
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByText(password)).not.toBeInTheDocument();
    expect(within(dialog).getByText("*".repeat(password.length))).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: /show password/i }));
    expect(within(dialog).getByText(password)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: /hide password/i }));
    expect(within(dialog).queryByText(password)).not.toBeInTheDocument();

    // The onboarding mailto draft carries the password, CRLF-encoded.
    const mailto = within(dialog).getByRole("link", { name: /compose onboarding email/i });
    expect(mailto.getAttribute("href")).toContain(encodeURIComponent(password));
  });

  test("closing the confirmation navigates away and the password is gone for good", async () => {
    mockPost(mockFetch);
    const user = userEvent.setup();
    renderCreate();

    await fillAndSubmit(user);
    const dialog = await screen.findByRole("dialog");
    const password = postBodyOf(mockFetch).password as string;
    await user.click(within(dialog).getByRole("button", { name: /^close$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/users"));
    expect(screen.queryByText(password)).not.toBeInTheDocument();
    expect(screen.queryByText("User created")).not.toBeInTheDocument();
  });

  test("the Administrator checkbox posts the ADMIN role", async () => {
    mockPost(mockFetch);
    const user = userEvent.setup();
    renderCreate();

    await fillAndSubmit(user, true);
    await screen.findByText("User created");
    expect(postBodyOf(mockFetch).roles).toEqual(["ADMIN"]);
  });

  test("a 409 surfaces the email-in-use error and stays on the form", async () => {
    mockPost(mockFetch, 409);
    const user = userEvent.setup();
    renderCreate();

    await fillAndSubmit(user);
    expect(await screen.findByText(/already used by another account/i)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("client-side validation blocks an empty submission", async () => {
    mockPost(mockFetch);
    const user = userEvent.setup();
    renderCreate();

    await user.click(screen.getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText(/1–50 characters/i)).toBeInTheDocument();
    expect(screen.getByText(/valid email/i)).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
