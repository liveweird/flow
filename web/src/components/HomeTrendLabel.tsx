import type { HomeFigureRow } from "../utils/homeOverview";
import { trendCssColor, trendGlyph } from "../utils/homeTrend";

/** The geometry recharts hands a bar's `label` renderer (the bar's rectangle and its index in the data). */
interface BarLabelProps {
  x?: number | string;
  y?: number | string;
  width?: number | string;
  height?: number | string;
  index?: number;
}

const GAP = 6;

/**
 * The trend arrow at the end of a horizontal bar (recharts `<Bar label>` content): the glyph of the value's direction in the
 * verdict's colour. It is decoration (`aria-hidden`) — the verdict is text in the chart's tooltip and hidden table. A bar with no
 * previous value to compare with (or a hidden figure, which draws no bar) gets no arrow.
 */
export default function HomeTrendLabel({ rows, ...bar }: BarLabelProps & { rows: readonly HomeFigureRow[] }) {
  const row = bar.index === undefined ? undefined : rows[bar.index];
  const glyph = row ? trendGlyph(row.trend) : null;
  if (!row?.trend || glyph === null) return null;
  const x = Number(bar.x) + Number(bar.width) + GAP;
  const y = Number(bar.y) + Number(bar.height) / 2;
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
  return (
    <text x={x} y={y} dominantBaseline="central" fontSize={14} fontWeight={700} fill={trendCssColor(row.trend.tone)} aria-hidden>
      {glyph}
    </text>
  );
}
