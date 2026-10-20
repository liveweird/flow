import type { ReportFilters } from "../api/reports";

/** The slice of a report-filter connection a Jira link needs. */
type JiraSite = Pick<ReportFilters["connections"][number], "id" | "siteUrl">;

/** `https://x.atlassian.net/` → `https://x.atlassian.net`; blank (unknown) or not `https://` (defence in depth: never a `javascript:` or `http:` href) → undefined. */
function normalizeSiteUrl(siteUrl: string | undefined): string | undefined {
  let origin = siteUrl?.trim() ?? "";
  while (origin.endsWith("/")) origin = origin.slice(0, -1);
  return origin.toLowerCase().startsWith("https://") ? origin : undefined;
}

/**
 * The Jira site an issue key belongs to. With a `connectionId` it is that connection's site (unknown connection or blank site:
 * undefined). Most report rows do not carry their connection, so without one the site is known only when every connection
 * that has a site shares the SAME one (a single Jira, or two connections to it) — with two different sites a key is ambiguous
 * and stays plain text rather than linking to the wrong Jira. The server now enforces ONE site for every data source, so this
 * returns that one site; the two-site branch guards rows created before the rule, which can still hold two.
 */
export function resolveJiraSite(connections: readonly JiraSite[] | undefined, connectionId?: number): string | undefined {
  if (connections === undefined) return undefined;
  if (connectionId !== undefined) return normalizeSiteUrl(connections.find((c) => c.id === connectionId)?.siteUrl);
  const sites = new Set(connections.map((c) => normalizeSiteUrl(c.siteUrl)).filter((s) => s !== undefined));
  return sites.size === 1 ? [...sites][0] : undefined;
}

/** `{siteUrl}/browse/{KEY}` for an issue or epic key, or undefined when the site is unknown. */
export function jiraBrowseUrl(siteUrl: string | undefined, issueKey: string): string | undefined {
  const origin = normalizeSiteUrl(siteUrl);
  return origin === undefined ? undefined : `${origin}/browse/${encodeURIComponent(issueKey)}`;
}
