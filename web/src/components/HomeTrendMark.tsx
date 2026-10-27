import { useTranslation } from "react-i18next";
import type { Trend } from "../utils/homeOverview";
import TrendMark from "./TrendMark";

/** The Home overview's trend beside a figure: `TrendMark` with the overview's words. */
export default function HomeTrendMark({ trend }: { trend: Trend | null }) {
  const { t } = useTranslation();
  return <TrendMark trend={trend} wordOf={(tone) => t(`home.trend.${tone}`)} />;
}
