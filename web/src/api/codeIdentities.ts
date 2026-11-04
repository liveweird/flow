// Code identities API (`/api/v1/data-sources/{id}/code-identities`) — the ADMIN-only mapping of a GitLab source's
// users and commit addresses to Jira people. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type CodeIdentityPage = paths["/api/v1/data-sources/{id}/code-identities"]["get"]["responses"]["200"]["content"]["application/json"];
export type CodeIdentityResponse = CodeIdentityPage["items"][number];
export type CodeIdentityStatus = NonNullable<NonNullable<paths["/api/v1/data-sources/{id}/code-identities"]["get"]["parameters"]["query"]>["status"]>;
export type CodeIdentityDecisionRequest =
  paths["/api/v1/data-sources/{id}/code-identities/{identityKey}"]["put"]["requestBody"]["content"]["application/json"];
export type CodeIdentityConfirmResult =
  paths["/api/v1/data-sources/{id}/code-identities/confirm-proposals"]["post"]["responses"]["200"]["content"]["application/json"];

type CodeIdentityListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  status?: CodeIdentityStatus;
  q?: string;
};

const base = (id: number) => `/api/v1/data-sources/${id}/code-identities`;
const keyPath = (id: number, key: string) => `${base(id)}/${encodeURIComponent(key)}`;

export async function listCodeIdentities(id: number, q: CodeIdentityListQuery): Promise<CodeIdentityPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, status: q.status, q: q.q });
  return jsonRequest<CodeIdentityPage>(`${base(id)}?${params}`);
}

/** Store (or change) the decision for one identity; answers the row as the list shows it now. */
export async function decideCodeIdentity(
  id: number,
  identityKey: string,
  body: CodeIdentityDecisionRequest,
): Promise<CodeIdentityResponse> {
  return jsonRequest<CodeIdentityResponse>(keyPath(id, identityKey), { method: "PUT", body: JSON.stringify(body) });
}

export async function removeCodeIdentityDecision(id: number, identityKey: string): Promise<void> {
  await voidRequest(keyPath(id, identityKey), { method: "DELETE" });
}

/** Confirm every current proposal of the source (no body); counts only the decisions actually written. */
export async function confirmCodeIdentityProposals(id: number): Promise<CodeIdentityConfirmResult> {
  return jsonRequest<CodeIdentityConfirmResult>(`${base(id)}/confirm-proposals`, { method: "POST" });
}
