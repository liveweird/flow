import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { Trend } from "../utils/homeOverview";
import { trendColor, trendGlyph } from "../utils/homeTrend";

/**
 * The trend beside a figure in the page's flow: the arrow of the value's direction, coloured by the verdict, and the verdict as a
 * visible word ("Better", "Worse", "No change") in the text colour -- so it never travels by hue alone. Renders nothing for a figure
 * with no previous value to compare with.
 */
export default function HomeTrendMark({ trend }: { trend: Trend | null }) {
  const { t } = useTranslation();
  const glyph = trendGlyph(trend);
  if (trend === null || glyph === null) return null;
  return (
    <>
      {" "}
      <Text component="span" c={trendColor(trend.tone)} fw={700} aria-hidden>
        {glyph}
      </Text>{" "}
      {t(`home.trend.${trend.tone}`)}
    </>
  );
}
