// Commit/MR titles and bodies. Jira keys use the Jira stub's own (synthetic) project keys so the later Jira-key
// linking has something to find.
const TYPES = [["feat", 30], ["fix", 34], ["chore", 12], ["refactor", 10], ["docs", 5], ["test", 6], ["perf", 3]];
const VERBS = ["handle", "validate", "drop", "retry", "cache", "batch", "log", "guard", "paginate", "normalize", "expose", "rename", "split", "inline", "bound"];
const OBJECTS = ["empty upstream list", "stale session token", "duplicate invoice rows", "slow cursor reads", "the nightly import", "retry budget", "pending order state", "tenant quota check", "audit log writer", "price rounding", "queue backpressure", "cache warmup", "route table reload", "ledger posting order", "catalog search filter"];
const TAILS = ["on startup", "when the queue is full", "before the flush", "in the nightly job", "for empty input", "behind a flag", "after a restart", "under load"];
const SCOPES = ["router", "auth", "cache", "billing", "search", "import", "cli", "api", "ui", "queue", "ledger", "config"];
const JIRA_KEYS = [["FLO", 400], ["PLT", 350], ["GTM", 300], ["OPS", 150]];
const BODY_SENTENCES = [
  "The previous behaviour dropped the value silently.",
  "Covered by a new test; the old path is kept behind a flag for one release.",
  "No schema change, safe to deploy ahead of the migration.",
  "Found while looking at the slow reads reported last sprint.",
  "Follow-up to the earlier cleanup, no functional change intended.",
];

export function commitTitle(rng) {
  const type = rng.pickWeighted(TYPES);
  const core = `${rng.pick(VERBS)} ${rng.pick(OBJECTS)}${rng.bool(0.3) ? ` ${rng.pick(TAILS)}` : ""}`;
  const r = rng.float();
  if (r < 0.22) {
    const [key, max] = rng.pick(JIRA_KEYS);
    return `${key}-${rng.int(1, max)}: ${core}`;
  }
  if (r < 0.3) {
    const [key, max] = rng.pick(JIRA_KEYS);
    return `${type}(${rng.pick(SCOPES)}): ${core} (${key}-${rng.int(1, max)})`;
  }
  return `${type}(${rng.pick(SCOPES)}): ${core}`;
}

export function commitBody(rng) {
  if (!rng.bool(0.3)) return "";
  const n = rng.int(1, 3);
  return Array.from({ length: n }, () => rng.pick(BODY_SENTENCES)).join(" ");
}

export const mrTitle = commitTitle;
