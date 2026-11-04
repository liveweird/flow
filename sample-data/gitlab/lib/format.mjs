// The JSON shapes of GitLab REST API v4 responses, built from the world model.
import { BASE_URL, HOST, GITLAB_VERSION, REFERENCE_MS } from "./constants.mjs";
import { noreplyEmail } from "./users.mjs";

const pad = (n, w = 2) => String(Math.abs(n)).padStart(w, "0");
/** `2026-08-30T10:11:12.000+02:00`: how git-derived dates (commits) are printed, in the committer's own offset. */
export function gitTime(ms, tzMinutes) {
  const d = new Date(ms + tzMinutes * 60_000);
  const sign = tzMinutes < 0 ? "-" : "+";
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}.${pad(d.getUTCMilliseconds(), 3)}${sign}${pad(Math.floor(Math.abs(tzMinutes) / 60))}:${pad(Math.abs(tzMinutes) % 60)}`;
}
/** `2026-08-30T10:11:12.345Z`: how database timestamps (users, projects, merge requests, notes) are printed. */
export const isoZ = (ms) => new Date(ms).toISOString();
export const dateOnly = (ms) => new Date(ms).toISOString().slice(0, 10);

export const userRef = (u) => ({ id: u.id, username: u.username, public_email: "", name: u.name, state: u.state, locked: false, avatar_url: null, web_url: `${BASE_URL}/${u.username}` });

export function userAdmin(u) {
  return {
    id: u.id, username: u.username, public_email: "", name: u.name, state: u.state, locked: false, avatar_url: null, web_url: `${BASE_URL}/${u.username}`,
    created_at: isoZ(u.createdMs), bio: "", location: null, linkedin: "", twitter: "", discord: "", website_url: "", github: "", job_title: "", pronouns: null, organization: "",
    bot: u.bot, work_information: null, followers: 0, following: 0, local_time: null, last_sign_in_at: u.state === "active" ? isoZ(REFERENCE_MS - 3_600_000 * (u.id + 1)) : null,
    confirmed_at: isoZ(u.createdMs + 60_000), last_activity_on: u.state === "active" ? "2026-08-31" : "2026-05-02", email: u.email, theme_id: 3, color_scheme_id: 1, projects_limit: 100000,
    current_sign_in_at: null, identities: [], can_create_group: !u.bot, can_create_project: !u.bot, two_factor_enabled: u.kind === "human", external: false, private_profile: false,
    commit_email: u.commitEmail ?? noreplyEmail(u), shared_runners_minutes_limit: null, extra_shared_runners_minutes_limit: null, scim_identities: [], is_admin: u.isAdmin,
    note: null, namespace_id: 1000 + u.id, created_by: null, email_reset_offered_at: null,
  };
}

export const groupJson = (g, groupById) => ({
  id: g.id, web_url: `${BASE_URL}/groups/${g.fullPath}`, name: g.name, path: g.path, description: `${g.name} group`, visibility: "internal", share_with_group_lock: false,
  require_two_factor_authentication: false, two_factor_grace_period: 48, project_creation_level: "developer", auto_devops_enabled: null, subgroup_creation_level: "maintainer",
  emails_disabled: false, emails_enabled: true, mentions_disabled: null, lfs_enabled: true, default_branch_protection: 2, avatar_url: null, request_access_enabled: true,
  full_name: g.parentId ? `${groupById.get(g.parentId).name} / ${g.name}` : g.name, full_path: g.fullPath, created_at: "2025-03-14T09:00:00.000Z", parent_id: g.parentId, organization_id: 1,
  shared_runners_setting: "enabled", ldap_cn: null, ldap_access: null, wiki_access_level: "enabled",
});

/** `state`: 'Started' | 'day2'. The project's namespace/default branch/activity depend on it. */
export function projectView(p, repo, state, groupById) {
  const day2 = state === "day2";
  const groupId = day2 && repo?.day2Info?.movedToGroupId ? repo.day2Info.movedToGroupId : p.groupId;
  const g = groupById.get(groupId);
  const fullPath = day2 && repo?.day2Info?.newFullPath ? repo.day2Info.newFullPath : p.fullPath;
  const view = (day2 ? repo?.views.day2 : repo?.views.started) ?? repo?.views.day2 ?? repo?.views.started;
  const tip = view ? repo.commits.get(view.tip) : null;
  return {
    id: p.id, fullPath, groupId, group: g, defaultBranch: view?.branch ?? p.branch, archived: p.archived,
    lastActivityMs: tip ? tip.committedMs : p.createdMs, createdMs: day2 && p.day2Only ? REFERENCE_MS + 3_600_000 : p.createdMs, name: p.name, path: fullPath.split("/").at(-1),
  };
}

export function projectJson(v, p, { detail = false, groupById }) {
  const g = v.group;
  const base = {
    id: v.id, description: `${p.name}: synthetic sample project`, name: p.name, name_with_namespace: `${g.parentId ? `${groupById.get(g.parentId).name} / ` : ""}${g.name} / ${p.name}`,
    path: v.path, path_with_namespace: v.fullPath, created_at: isoZ(v.createdMs), default_branch: v.defaultBranch, tag_list: [], topics: [],
    ssh_url_to_repo: `git@${HOST}:${v.fullPath}.git`, http_url_to_repo: `${BASE_URL}/${v.fullPath}.git`, web_url: `${BASE_URL}/${v.fullPath}`, readme_url: `${BASE_URL}/${v.fullPath}/-/blob/${v.defaultBranch}/README.md`,
    forks_count: 0, avatar_url: null, star_count: 0, last_activity_at: isoZ(v.lastActivityMs),
    namespace: { id: g.id, name: g.name, path: g.path, kind: "group", full_path: g.fullPath, parent_id: g.parentId, avatar_url: null, web_url: `${BASE_URL}/groups/${g.fullPath}` },
    container_registry_image_prefix: `registry.${HOST}/${v.fullPath}`,
    _links: {
      self: `${BASE_URL}/api/v4/projects/${v.id}`, issues: `${BASE_URL}/api/v4/projects/${v.id}/issues`, merge_requests: `${BASE_URL}/api/v4/projects/${v.id}/merge_requests`,
      repo_branches: `${BASE_URL}/api/v4/projects/${v.id}/repository/branches`, labels: `${BASE_URL}/api/v4/projects/${v.id}/labels`, events: `${BASE_URL}/api/v4/projects/${v.id}/events`,
      members: `${BASE_URL}/api/v4/projects/${v.id}/members`, cluster_agents: `${BASE_URL}/api/v4/projects/${v.id}/cluster_agents`,
    },
    packages_enabled: true, empty_repo: false, archived: v.archived, visibility: "internal", resolve_outdated_diff_discussions: false,
    issues_enabled: true, merge_requests_enabled: true, wiki_enabled: true, jobs_enabled: true, snippets_enabled: true, container_registry_enabled: true,
    service_desk_enabled: false, can_create_merge_request_in: true, issues_access_level: "enabled", repository_access_level: "enabled", merge_requests_access_level: "enabled",
    shared_runners_enabled: true, lfs_enabled: true, creator_id: 2, import_status: "none", open_issues_count: 0,
    merge_method: p.merge === "ff" ? "ff" : "merge", squash_option: p.merge === "squash" ? "always" : "default_off", only_allow_merge_if_pipeline_succeeds: false,
    only_allow_merge_if_all_discussions_are_resolved: false, remove_source_branch_after_merge: true, request_access_enabled: true,
  };
  if (detail) {
    Object.assign(base, { ci_config_path: null, compliance_frameworks: [], permissions: { project_access: null, group_access: null }, mr_default_target_self: false });
  }
  return base;
}

const trailers = {};
export function commitJson(c, project, { stats = false } = {}) {
  const j = {
    id: c.sha, short_id: c.sha.slice(0, 8), created_at: gitTime(c.committedMs, c.committerTz), parent_ids: c.parents, title: c.title, message: c.message,
    author_name: c.authorName, author_email: c.authorEmail, authored_date: gitTime(c.authoredMs, c.authorTz), committer_name: c.committerName, committer_email: c.committerEmail,
    committed_date: gitTime(c.committedMs, c.committerTz), trailers, extended_trailers: trailers, web_url: `${BASE_URL}/${project.fullPath}/-/commit/${c.sha}`,
  };
  if (stats) j.stats = { additions: c.stats.additions, deletions: c.stats.deletions, total: c.stats.total };
  return j;
}

export function diffFileJson(f) {
  const j = {
    diff: f.diff, new_path: f.newPath, old_path: f.oldPath, a_mode: f.aMode, b_mode: f.bMode, new_file: f.newFile, renamed_file: f.renamedFile, deleted_file: f.deletedFile,
    generated_file: f.generatedFile,
  };
  if (f.tooLarge) j.too_large = true;
  if (f.collapsed) j.collapsed = true;
  return j;
}

export function branchJson(c, project, name) {
  return {
    name, commit: commitJson(c, project), merged: false, protected: true, developers_can_push: false, developers_can_merge: false, can_push: true, default: true,
    web_url: `${BASE_URL}/${project.fullPath}/-/tree/${name}`,
  };
}

const mergeStatus = (m) => (m.state === "opened" ? (m.draft ? "draft_status" : "mergeable") : m.state === "locked" ? "mergeable" : "not_open");
const sinceZ = (ms) => (ms == null ? null : isoZ(ms));

/** A merge request as listed by `GET /groups/:id/merge_requests` (and the project-level routes). */
export function mrJson(m, project) {
  const refs = { short: `!${m.iid}`, relative: `!${m.iid}`, full: `${project.fullPath}!${m.iid}` };
  const mergedBy = m.state === "merged" ? userRef(m.merger) : null;
  const closedBy = m.state === "closed" ? userRef(m.closer ?? m.author) : null;
  return {
    id: m.id, iid: m.iid, project_id: m.projectId, title: m.title, description: `Synthetic merge request ${refs.full}.`, state: m.state, created_at: isoZ(m.createdMs), updated_at: isoZ(m.updatedMs),
    merged_by: mergedBy, merge_user: mergedBy, merged_at: sinceZ(m.state === "merged" ? m.mergedMs : null), closed_by: closedBy, closed_at: sinceZ(m.state === "closed" ? m.closedMs : null),
    target_branch: m.targetBranch, source_branch: m.sourceBranch, user_notes_count: m.notes.filter((n) => !n.system).length, upvotes: 0, downvotes: 0, author: userRef(m.author),
    assignees: m.assignees.map(userRef), assignee: m.assignees[0] ? userRef(m.assignees[0]) : null, reviewers: m.reviewers.map(userRef), source_project_id: m.projectId, target_project_id: m.projectId,
    labels: m.labels, draft: m.draft, work_in_progress: m.draft, milestone: null, merge_when_pipeline_succeeds: false, merge_status: m.state === "opened" ? "can_be_merged" : "unchecked",
    detailed_merge_status: mergeStatus(m), sha: m.sha, merge_commit_sha: m.mergeCommitSha ?? null, squash_commit_sha: m.squashCommitSha ?? null, discussion_locked: null,
    should_remove_source_branch: null, force_remove_source_branch: true, prepared_at: isoZ(m.createdMs + 30_000), reference: refs.short, references: refs,
    web_url: `${BASE_URL}/${project.fullPath}/-/merge_requests/${m.iid}`,
    time_stats: { time_estimate: 0, total_time_spent: 0, human_time_estimate: null, human_total_time_spent: null }, squash: m.squash || project.merge === "squash", squash_on_merge: m.squash || project.merge === "squash",
    has_conflicts: false, blocking_discussions_resolved: true, task_completion_status: { count: 0, completed_count: 0 },
  };
}

export function noteJson(n, m) {
  const j = {
    id: n.id, type: n.type, body: n.body, author: userRef(n.user), created_at: isoZ(n.at), updated_at: isoZ(n.at), system: n.system, noteable_id: m.id, noteable_type: "MergeRequest",
    project_id: m.projectId, commit_id: null, resolvable: n.type === "DiffNote", resolved: n.type === "DiffNote" ? !!n.resolved : false,
    resolved_by: n.type === "DiffNote" && n.resolved ? userRef(m.author) : null, resolved_at: n.type === "DiffNote" && n.resolved ? isoZ(n.at + 3_600_000) : null,
    confirmed: null, confirmed_by: null, noteable_iid: m.iid, commands_changes: {},
  };
  if (n.type === "DiffNote") j.position = n.position;
  return j;
}

export function approvalsJson(m, project) {
  return {
    id: m.id, iid: m.iid, project_id: m.projectId, title: m.title, description: `Synthetic merge request ${project.fullPath}!${m.iid}.`, state: m.state, created_at: isoZ(m.createdMs),
    updated_at: isoZ(m.updatedMs), merge_status: mergeStatus(m), approvals_required: 1, approvals_left: Math.max(0, 1 - m.approvedBy.length), require_password_to_approve: false,
    approved_by: m.approvedBy.map((a) => ({ user: userRef(a.user) })), suggested_approvers: [], approvers: [], approver_groups: [], user_has_approved: false, user_can_approve: false,
    approval_rules_left: [], has_approval_rules: true, merge_request_approvers_available: true, multiple_approval_rules_available: true, invalid_approvers_rules: [],
  };
}

export const reviewersJson = (m) => m.reviewerStates.map((s) => ({ user: userRef(s.user), state: s.state, created_at: isoZ(s.at) }));

export const versionJson = () => ({ version: GITLAB_VERSION.version, revision: GITLAB_VERSION.revision, kas: GITLAB_VERSION.kas, enterprise: GITLAB_VERSION.enterprise });

export const tokenSelfJson = (admin) => ({
  id: 77, name: "flow-read-only", revoked: false, created_at: "2026-08-01T08:00:00.000Z", description: "Flow read-only sync", scopes: ["read_api"], user_id: admin.id,
  last_used_at: "2026-08-31T22:15:04.000Z", active: true, expires_at: "2027-08-01",
});
