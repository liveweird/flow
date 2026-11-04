// Commit-graph queries over a Map<sha, commit> (commit.parents = parent shas): the order `git log` prints, ancestor
// sets, `a..b` ranges and merge bases. Dates and the creation sequence break ties deterministically.

export function ancestors(map, sha) {
  const seen = new Set();
  const stack = [sha];
  while (stack.length) {
    const s = stack.pop();
    if (seen.has(s)) continue;
    seen.add(s);
    for (const p of map.get(s).parents) stack.push(p);
  }
  return seen;
}

const newer = (a, b) => (a.committedMs !== b.committedMs ? a.committedMs > b.committedMs : a.seq > b.seq);

/** `git log tip` (reverse chronological walk from the tip) minus everything reachable from `exclude` (`exclude..tip`). */
export function logWalk(map, tip, exclude = null) {
  const hidden = exclude ? ancestors(map, exclude) : new Set();
  if (hidden.has(tip)) return [];
  const seen = new Set(hidden);
  seen.add(tip);
  const frontier = [map.get(tip)];
  const out = [];
  while (frontier.length) {
    let best = 0;
    for (let i = 1; i < frontier.length; i++) if (newer(frontier[i], frontier[best])) best = i;
    const c = frontier.splice(best, 1)[0];
    out.push(c);
    for (const p of c.parents) {
      if (!seen.has(p)) {
        seen.add(p);
        frontier.push(map.get(p));
      }
    }
  }
  return out;
}

/** A best common ancestor of `a` and `b`: the first commit of a's walk that b can reach. */
export function mergeBase(map, a, b) {
  const reach = ancestors(map, b);
  for (const c of logWalk(map, a)) if (reach.has(c.sha)) return c;
  return null;
}
