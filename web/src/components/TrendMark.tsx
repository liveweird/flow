import { Text } from "@mantine/core";
import type { Trend, TrendTone } from "../utils/homeOverview";
import { trendColor, trendGlyph } from "../utils/homeTrend";

/**
 * The trend beside a figure in the page's flow: the arrow of the value's direction, coloured by the verdict, and the verdict as a
 * visible word ("Better", "Worse", "No change", from `wordOf`) in the text colour -- so it never travels by hue alone. Renders nothing for
 * a figure with no previous value to compare with. Home's overview and Person trend each bring their own words.
 */
export default function TrendMark({ trend, wordOf }: { trend: Trend | null; wordOf: (tone: TrendTone) => string }) {
  const glyph = trendGlyph(trend);
  if (trend === null || glyph === null) return null;
  return (
    <>
      {" "}
      <Text component="span" c={trendColor(trend.tone)} fw={700} aria-hidden>
        {glyph}
      </Text>{" "}
      {wordOf(trend.tone)}
    </>
  );
}
