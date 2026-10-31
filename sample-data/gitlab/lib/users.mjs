// GitLab users of the synthetic instance and how their identities line up with the Jira stub's people.
import { BOT_NOREPLY_DOMAIN, DAY_MS, JIRA_TEAM_BY_NUMBER, NOREPLY_DOMAIN, REFERENCE_MS, jiraAccountId, jiraEmail } from "./constants.mjs";
import { sha256 } from "./rng.mjs";

/** GitLab humans 1..6 map to these Jira stub numbers (all on a team); more humans continue with the other on-team numbers. */
const DEFAULT_JIRA_NUMBERS = [1, 4, 3, 5, 9, 13];
const TZ_MINUTES = [120, 60, 0, -300, 330, 60, 120, -180];

export const noreplyEmail = (u) => `${u.id}-${u.username}@${NOREPLY_DOMAIN}`;
export const canonicalEmail = (email) => email.trim().toLowerCase();
/** `e<sha256(canonical email)[0:32]>` (gitlab-integration.md "Identity"). */
export const emailIdentityKey = (email) => `e${sha256(canonicalEmail(email)).slice(0, 32)}`;

export function buildUsers(humans) {
  const onTeam = Object.keys(JIRA_TEAM_BY_NUMBER).map(Number).filter((n) => JIRA_TEAM_BY_NUMBER[n] && !DEFAULT_JIRA_NUMBERS.includes(n));
  const jiraNumbers = [...DEFAULT_JIRA_NUMBERS, ...onTeam];
  for (let n = 31; jiraNumbers.length < humans; n++) jiraNumbers.push(n);
  const createdMs = REFERENCE_MS - 900 * DAY_MS;

  const admin = {
    id: 1, username: "flow-sync", name: "Flow Sync (admin)", kind: "admin", state: "active", isAdmin: true, bot: false,
    email: `flow-sync@flow-sample.example`, commitEmail: `flow-sync@flow-sample.example`, secondary: [], jiraNo: null, tz: 0, createdMs,
    commitEmails: [[`flow-sync@flow-sample.example`, 1]],
  };
  const list = [admin];
  for (let i = 0; i < humans; i++) {
    const jiraNo = jiraNumbers[i];
    const u = {
      id: 2 + i, username: `sample.dev${jiraNo}`, name: `Sample Dev ${jiraNo}`, kind: "human", state: i === 5 ? "deactivated" : "active", isAdmin: false, bot: false,
      email: jiraEmail(jiraNo), secondary: [], jiraNo, tz: TZ_MINUTES[i % TZ_MINUTES.length], createdMs: createdMs + i * 7 * DAY_MS,
    };
    u.commitEmail = u.email;
    u.commitEmails = [[u.email, 1]];
    if (i === 0 || i === 4) {
      // Private commit email: half of this person's commits carry the GitLab noreply address.
      u.commitEmail = noreplyEmail(u);
      u.commitEmails = [[u.email, 0.7], [noreplyEmail(u), 0.3]];
    }
    if (i === 2) {
      // The person registered a private address as primary; the Jira address is a confirmed SECONDARY email.
      u.email = `sample.dev${jiraNo}@mail.example`;
      u.secondary = [{ id: 1000 + u.id, email: jiraEmail(jiraNo), confirmed_at: new Date(u.createdMs + DAY_MS).toISOString() }];
      u.commitEmail = u.email;
      u.commitEmails = [[jiraEmail(jiraNo), 0.55], [u.email, 0.45]];
    }
    list.push(u);
  }
  const botId = 2 + humans;
  const botName = "project_3_bot_4f1a9c2e7b";
  list.push({
    id: botId, username: botName, name: "Dependency Updates", kind: "bot", state: "active", isAdmin: false, bot: true,
    email: `${botName}@${BOT_NOREPLY_DOMAIN}`, commitEmail: `${botName}@${BOT_NOREPLY_DOMAIN}`, secondary: [], jiraNo: null, tz: 0, createdMs,
    commitEmails: [[`${botName}@${BOT_NOREPLY_DOMAIN}`, 1]],
  });
  return list;
}

/** A commit author with no GitLab account: a contractor's personal address. */
export const CONTRACTOR = {
  id: null, kind: "unmatched", name: "Sample Contractor", email: "contractor.sample@mail.example", tz: 60,
  commitEmails: [["contractor.sample@mail.example", 1]],
};

/** Resolve a commit email to the identity key Flow will compute (user id via primary/secondary/commit/noreply email, else the email hash). */
export function identityKeyFor(users, email) {
  const e = canonicalEmail(email);
  for (const u of users) {
    const all = [u.email, u.commitEmail, ...u.secondary.map((s) => s.email), noreplyEmail(u)].map(canonicalEmail);
    if (all.includes(e)) return { key: `u${u.id}`, user: u };
  }
  return { key: emailIdentityKey(email), user: null };
}

export function jiraAlignment(users) {
  return users.filter((u) => u.jiraNo).map((u) => ({
    gitlabUserId: u.id, username: u.username, jiraUserNumber: u.jiraNo, jiraAccountId: jiraAccountId(u.jiraNo), jiraEmail: jiraEmail(u.jiraNo),
    team: JIRA_TEAM_BY_NUMBER[u.jiraNo], matchedBy: u.email === jiraEmail(u.jiraNo) ? "primary" : "secondary",
  }));
}
