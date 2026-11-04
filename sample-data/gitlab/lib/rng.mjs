// Seeded PRNG (mulberry32) and small deterministic helpers shared by the GitLab stub generator.
// Same generator as sample-data/jira/generate.mjs; every random draw in this directory comes from here,
// never from Math.random() or Date.now().
import { createHash } from "node:crypto";

export function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function makeRng(seed) {
  const raw = mulberry32(seed);
  return {
    float: () => raw(),
    int(min, max) {
      return min + Math.floor(raw() * (max - min + 1));
    },
    bool(p) {
      return raw() < p;
    },
    pick(arr) {
      return arr[this.int(0, arr.length - 1)];
    },
    pickWeighted(pairs) {
      const total = pairs.reduce((s, [, w]) => s + w, 0);
      let r = raw() * total;
      for (const [value, weight] of pairs) {
        if (r < weight) return value;
        r -= weight;
      }
      return pairs[pairs.length - 1][0];
    },
    shuffle(arr) {
      const a = arr.slice();
      for (let i = a.length - 1; i > 0; i--) {
        const j = this.int(0, i);
        [a[i], a[j]] = [a[j], a[i]];
      }
      return a;
    },
  };
}

/** FNV-1a 32-bit over a label: one independent stream per concern, so changing one concern never shifts another. */
export function seedFor(label) {
  let h = 0x811c9dc5;
  for (let i = 0; i < label.length; i++) {
    h ^= label.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

export const rngFor = (label) => makeRng(seedFor(`flow-gitlab:${label}`));

export const sha1 = (text) => createHash("sha1").update(text).digest("hex");
export const sha256 = (text) => createHash("sha256").update(text).digest("hex");
