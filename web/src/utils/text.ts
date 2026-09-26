import type { ComboboxItem, OptionsFilter } from "@mantine/core";

// NFD decomposition misses letters that have no combining mark: ł/Ł is L-with-stroke, so it
// survives the \p{M} strip and needs an explicit mapping (same for đ, ø and the ligatures).
// Lowercase-only keys — folding lowercases first. Mirrors the server's unaccent() rules
// (infra/db/Sql.kt containsNormalized) closely enough for the option labels shown here.
const NON_DECOMPOSING: Record<string, string> = {
  ł: "l",
  đ: "d",
  ø: "o",
  æ: "ae",
  œ: "oe",
  ß: "ss",
};

/** Case- and accent-insensitive fold: "Żółw" → "zolw". */
export function foldDiacritics(s: string): string {
  return s
    .toLowerCase()
    .normalize("NFD")
    .replace(/\p{M}+/gu, "")
    .replace(/[łđøæœß]/g, (c) => NON_DECOMPOSING[c]);
}

const matches = (item: ComboboxItem, query: string) => foldDiacritics(item.label).includes(query);

// Drop-in replacement for Mantine's defaultOptionsFilter — the same label-contains-search
// contract (grouped options included), but diacritics-insensitive on both sides so typing
// "zolw" finds "Żółw". Wired app-wide as the Select/MultiSelect/TagsInput default in theme.ts.
export const foldedOptionsFilter: OptionsFilter = ({ options, search }) => {
  const query = foldDiacritics(search.trim());
  return options
    .map((option) =>
      "group" in option
        ? { ...option, items: option.items.filter((item) => matches(item, query)) }
        : option,
    )
    .filter((option) =>
      "group" in option ? option.items.length > 0 : matches(option as ComboboxItem, query),
    );
};
