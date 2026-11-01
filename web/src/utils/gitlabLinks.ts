import type { CodeActivityReport } from "../api/reports";

/** The slice of a report source a GitLab link needs. */
type GitLabSource = Pick<CodeActivityReport["sources"][number], "baseUrl">;

/**
 * A GitLab base URL made safe to link from: parsed, `http(s)` only (never a `javascript:` href), no credentials, no query or fragment, no
 * trailing slash (a GitLab under a relative URL root keeps its path). Anything else: `undefined`.
 */
function normalizeBase(baseUrl: string): string | undefined {
  let url: URL;
  try {
    url = new URL(baseUrl.trim());
  } catch {
    return undefined;
  }
  if ((url.protocol !== "https:" && url.protocol !== "http:") || url.username !== "" || url.password !== "") return undefined;
  let path = url.pathname;
  while (path.endsWith("/")) path = path.slice(0, -1);
  return `${url.origin}${path}`;
}

/**
 * The GitLab an item's link points at. The drill lists carry no connection, so the host is known only when every source in scope
 * shares the SAME base URL (one GitLab, or two sources on it); with two different hosts an item is ambiguous and stays plain text
 * rather than linking to the wrong GitLab (the Jira link's rule, `resolveJiraSite`).
 */
export function resolveGitLabBase(sources: readonly GitLabSource[]): string | undefined {
  const bases = new Set(sources.map((source) => normalizeBase(source.baseUrl)));
  const [only] = bases;
  return bases.size === 1 ? only : undefined;
}

const MERGE_REQUEST_KEY = /^([\w.-]+(?:\/[\w.-]+)*)!(\d{1,18})$/;
const COMMIT_KEY = /^([\w.-]+(?:\/[\w.-]+)*)@([0-9a-f]{4,40})$/;

/**
 * The page of an item key on [base]: `<project path>!<iid>` is `<base>/<path>/-/merge_requests/<iid>` and `<project path>@<sha>` is
 * `<base>/<path>/-/commit/<sha>` (GitLab resolves the abbreviated sha). `undefined` when the base is unknown or the key is not one
 * of the two shapes (a project the server could not name is listed as `project-<id>`, which still matches and opens nothing useful,
 * so a path is only linked when GitLab can have it).
 */
export function gitlabItemUrl(base: string | undefined, issueKey: string): string | undefined {
  if (base === undefined) return undefined;
  const mergeRequest = MERGE_REQUEST_KEY.exec(issueKey);
  if (mergeRequest !== null) return `${base}/${encodePath(mergeRequest[1])}/-/merge_requests/${mergeRequest[2]}`;
  const commit = COMMIT_KEY.exec(issueKey);
  return commit === null ? undefined : `${base}/${encodePath(commit[1])}/-/commit/${commit[2]}`;
}

function encodePath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}
