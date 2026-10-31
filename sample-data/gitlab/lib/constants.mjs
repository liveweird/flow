// Fixed facts of the synthetic self-managed GitLab world. Names are neutral on purpose: the host is `*.example`
// (RFC 2606), groups/projects/people are invented, nothing refers to a real company, product or person.
export const HOST = "gitlab.flow-sample.example";
export const BASE_URL = `https://${HOST}`;
export const NOREPLY_DOMAIN = `users.noreply.${HOST}`;
export const BOT_NOREPLY_DOMAIN = `noreply.${HOST}`;

export const REFERENCE_ISO = "2026-09-01T00:00:00.000Z";
export const REFERENCE_MS = Date.parse(REFERENCE_ISO);
export const DAY_MS = 86_400_000;
export const HOUR_MS = 3_600_000;
export const MIN_MS = 60_000;
/** The state `gitlab-day2` simulates: one day of wall-clock time later. */
export const DAY2_MS = REFERENCE_MS + DAY_MS;
export const SCENARIO = "gitlab-day2";

export const GITLAB_VERSION = { version: "19.3.1", revision: "6a4d9c0e1f2b", kas: { enabled: true, externalUrl: `wss://${HOST}/-/kubernetes-agent/`, externalK8sProxyUrl: `https://${HOST}/-/kubernetes-agent/k8s-proxy/`, version: "19.3.1" }, enterprise: false };

export const PER_PAGE = 100;

/** Number of the Jira stub's `user<n>@flow-sample.example` people that are on a team (sample-data/jira/expected.json `teams.roster`); null = on no team. */
export const JIRA_TEAM_BY_NUMBER = {
  1: "Platform Core", 2: "Growth", 3: "Platform Core", 4: "Payments", 5: "Reliability", 6: "Payments", 7: null, 8: "Payments", 9: "Growth", 10: "Growth",
  11: "Platform Core", 12: "Growth", 13: "Reliability", 14: "Growth", 15: "Reliability", 16: "Growth", 17: "Reliability", 18: "Platform Core", 19: "Platform Core", 20: "Payments",
  21: "Reliability", 22: "Payments", 23: "Platform Core", 24: "Payments", 25: "Payments", 26: "Reliability", 27: null, 28: "Platform Core", 29: "Growth", 30: "Reliability",
};
export const jiraAccountId = (n) => `5f8a1b2c3d4e5f6a7b8c9d${String(n).padStart(2, "0")}`;
export const jiraEmail = (n) => `user${n}@flow-sample.example`;

export const GROUPS = [
  { id: 7, name: "Platform", path: "platform", fullPath: "platform", parentId: null },
  { id: 8, name: "Tooling", path: "tooling", fullPath: "platform/tooling", parentId: 7 },
  { id: 9, name: "Apps", path: "apps", fullPath: "apps", parentId: null },
  { id: 12, name: "Sandbox", path: "sandbox", fullPath: "sandbox", parentId: null },
];
/** The groups a data source is configured with (subgroups come with them). */
export const CONFIGURED_GROUP_IDS = [7, 9];

/** The fixed projects. `share` is the fraction of the in-scope commit budget; `merge`: how merge requests land. */
export const FIXED_PROJECTS = [
  { id: 1, name: "Gateway", path: "gateway", groupId: 7, branch: "main", tpl: "go", merge: "merge", share: 0.3, archived: false, scope: "in" },
  { id: 2, name: "Build Kit", path: "build-kit", groupId: 8, branch: "main", tpl: "ts", merge: "squash", share: 0.23, archived: false, scope: "in" },
  { id: 3, name: "Storefront", path: "storefront", groupId: 9, branch: "main", tpl: "tsx", merge: "merge", share: 0.27, archived: false, scope: "in" },
  { id: 4, name: "Ledger", path: "ledger", groupId: 9, branch: "master", tpl: "py", merge: "ff", share: 0.2, archived: false, scope: "in" },
  { id: 5, name: "Legacy Portal", path: "legacy-portal", groupId: 9, branch: "main", tpl: "rb", merge: "merge", share: 0, archived: true, scope: "archived" },
  { id: 6, name: "Experiments", path: "experiments", groupId: 12, branch: "main", tpl: "rs", merge: "merge", share: 0, archived: false, scope: "out" },
];
/** Appears only in `day2`. */
export const DAY2_NEW_PROJECT = { id: 7, name: "Lint Rules", path: "lint-rules", groupId: 8, branch: "main", tpl: "ts", merge: "merge", share: 0, archived: false, scope: "in" };
