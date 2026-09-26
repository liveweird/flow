import { describe, expect, it } from "vitest";
import { SUPPORTED_LANGUAGES } from "../i18n";

// Guards translation parity for EVERY shipped language against the canonical EN tree, so
// languages can't silently drift. A shipped locale bundle is all-or-nothing — partialness
// lives only in dictionary CONTENT, never in the UI chrome. Loads every locale JSON via
// Vite's import.meta.glob (build-time expanded — no fs/node types, auto-discovers new
// language folders and area files), so an en/foo.json with no <lang>/foo.json fails here
// rather than shipping half-translated. Mirrors the manual review checks: key parity,
// placeholders, empties.

type Json = Record<string, unknown>;
type JsonModule = { default: Json };

const ALL_MODULES = import.meta.glob<JsonModule>("./*/*.json", { eager: true });

// CLDR plural categories: i18next appends `_<category>` (English uses one/other; other
// languages may add few/many). Stripping the suffix compares the base concept, so a
// language-only `unread_few`/`_many` is not mistaken for a missing key — but a genuinely
// absent key still is.
const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;

// language -> (area -> json)
const BY_LANGUAGE = new Map<string, Map<string, Json>>();
for (const [path, mod] of Object.entries(ALL_MODULES)) {
  const match = /^\.\/([^/]+)\/([^/]+)\.json$/.exec(path);
  if (!match) continue;
  const [, lang, area] = match;
  const areas = BY_LANGUAGE.get(lang) ?? new Map<string, Json>();
  areas.set(area, mod.default);
  BY_LANGUAGE.set(lang, areas);
}

// Flatten nested objects to dot-paths → leaf string values.
function flatten(obj: unknown, prefix = ""): Record<string, string> {
  const out: Record<string, string> = {};
  if (obj !== null && typeof obj === "object") {
    for (const [k, v] of Object.entries(obj as Json)) {
      Object.assign(out, flatten(v, prefix ? `${prefix}.${k}` : k));
    }
  } else {
    out[prefix] = String(obj);
  }
  return out;
}

const base = (key: string): string => key.replace(PLURAL_SUFFIX, "");
const placeholders = (s: string): Set<string> =>
  new Set([...s.matchAll(/{{\s*([\w.]+)/g)].map((m) => m[1]));

const EN = BY_LANGUAGE.get("en") ?? new Map<string, Json>();
const EN_AREAS = [...EN.keys()].sort();
const OTHER_LANGUAGES = SUPPORTED_LANGUAGES.filter((l) => l !== "en");
const LANG_AREA: [string, string][] = OTHER_LANGUAGES.flatMap((lang) =>
  EN_AREAS.map((area): [string, string] => [lang, area]),
);

describe("locale parity vs EN", () => {
  it("the discovered locale folders are exactly the supported languages", () => {
    expect([...BY_LANGUAGE.keys()].sort()).toEqual([...SUPPORTED_LANGUAGES].sort());
  });

  it.each(OTHER_LANGUAGES)("%s has the same set of area files as en", (lang) => {
    expect([...(BY_LANGUAGE.get(lang)?.keys() ?? [])].sort()).toEqual(EN_AREAS);
  });

  it.each(LANG_AREA)("%s/%s.json — every key exists in both languages (plural-aware)", (lang, area) => {
    const en = flatten(EN.get(area));
    const other = flatten(BY_LANGUAGE.get(lang)?.get(area));
    const enBases = new Set(Object.keys(en).map(base));
    const otherBases = new Set(Object.keys(other).map(base));

    const missing = [...enBases].filter((k) => !otherBases.has(k)).sort();
    const extra = [...otherBases].filter((k) => !enBases.has(k)).sort();

    expect(missing, `keys present in en/${area} but missing in ${lang}/${area}`).toEqual([]);
    expect(extra, `keys present in ${lang}/${area} but missing in en/${area}`).toEqual([]);
  });

  it.each(LANG_AREA)("%s/%s.json — placeholders match EN", (lang, area) => {
    const en = flatten(EN.get(area));
    const other = flatten(BY_LANGUAGE.get(lang)?.get(area));

    // Compare the {{token}} set per base-key family (covers plural forms too).
    const family = (flat: Record<string, string>): Map<string, Set<string>> => {
      const m = new Map<string, Set<string>>();
      for (const [k, v] of Object.entries(flat)) {
        const set = m.get(base(k)) ?? new Set<string>();
        placeholders(v).forEach((p) => set.add(p));
        m.set(base(k), set);
      }
      return m;
    };
    const enFam = family(en);
    const otherFam = family(other);

    const mismatches: string[] = [];
    for (const [key, enSet] of enFam) {
      const otherSet = otherFam.get(key) ?? new Set<string>();
      const enSorted = [...enSet].sort();
      const otherSorted = [...otherSet].sort();
      if (JSON.stringify(enSorted) !== JSON.stringify(otherSorted)) {
        mismatches.push(`${area}:${key} — en{${enSorted}} vs ${lang}{${otherSorted}}`);
      }
    }
    expect(mismatches, "placeholder token mismatches").toEqual([]);
  });

  it.each([...SUPPORTED_LANGUAGES])("%s — no empty string values in any area", (lang) => {
    const empties: string[] = [];
    for (const [area, json] of BY_LANGUAGE.get(lang) ?? []) {
      for (const [k, v] of Object.entries(flatten(json))) {
        if (v === "") empties.push(`${lang}/${area}:${k}`);
      }
    }
    expect(empties, "empty translation values").toEqual([]);
  });
});
