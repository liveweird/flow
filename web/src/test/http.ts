// Shared test helper: a JSON Response for fetch mocks (previously copy-pasted per test file).
export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
