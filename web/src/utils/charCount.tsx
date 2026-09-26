import { Text } from "@mantine/core";
import { useTranslation } from "react-i18next";

/** nearLimit counters stay hidden until the text reaches this share of the limit. */
const NEAR_LIMIT_RATIO = 0.8;

export type CharCountMode = "always" | "nearLimit";

/** THE visibility rule — the single predicate deciding whether a counter renders at all. */
function shouldShowCharCount(current: number, max: number, mode: CharCountMode): boolean {
  return mode === "always" || current >= max * NEAR_LIMIT_RATIO;
}

// The shared "123 / 4000" character counter under capped text fields. Dimmed while under the
// limit, red when over — over-limit is reachable only through programmatic value pushes, since
// native maxLength blocks typing/paste. Not exported: charCountDescription is the only caller,
// and this file's other exports are plain functions, not components — fast-refresh opts out.
// eslint-disable-next-line react-refresh/only-export-components -- a util file mixing a private component with plain helpers; nothing here is hot-reloaded as a route.
function CharCount({ current, max }: { current: number; max: number }) {
  const { t } = useTranslation();
  return (
    <Text size="xs" c={current > max ? "red" : "dimmed"} ta="right" component="span" display="block">
      {t("common.charCount", { current, max })}
    </Text>
  );
}

/**
 * Description-slot helper for plain TextInputs: the counter node when visible, else undefined
 * (never an empty element — a truthy description renders Mantine's wrapper div even when empty).
 * Pair with inputWrapperOrder={["label", "input", "description", "error"]} to sit below the input.
 */
export function charCountDescription(current: number, max: number, mode: CharCountMode = "nearLimit") {
  return shouldShowCharCount(current, max, mode) ? <CharCount current={current} max={max} /> : undefined;
}
