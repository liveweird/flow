-- Removes the Flow-login team roster (V6 `team_members`). A team had two member lists: the dated Jira-account membership
-- (`metrics.team_membership`, V15, domain-model D1), which every metric, report, capacity calendar and report filter reads, and
-- this login join, inherited from Covenant, which nothing outside `teams/` ever read -- no metric, report or permission (editing a
-- team's capacity is the team's manager, V23, or an ADMIN). The table is a pure hard-delete join, so there is no data to carry over;
-- its `idx_team_members_user_id` index and both foreign keys go with it. `teams.manager_user_id` (V23) and `metrics.team_membership`
-- are untouched. No existing V1-V29 file changes (their bytes are immutable -- `MigrationChecksumTest`).
DROP TABLE team_members;
