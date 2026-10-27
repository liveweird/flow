import { cssColor } from "../utils/homeTrend";

/**
 * The dot renderer of a trend line whose points can lie in a PARTIAL window (it starts before the backfill or ends after the last derived day):
 * such a point is a HOLLOW dot — the surface colour inside a thicker ring — every other a filled one, so the difference is a shape, never hue
 * alone (the tooltip and the table say "partial" too). `partialAt` is per data index. A gap (no `cx`/`cy`) draws nothing; the line is not
 * connected across it (`connectNulls` is off). `onClick` (per data index) makes a dot a button for a pointer (the chart's drill; the keyboard
 * route is the numbers table).
 */
export function partialDot(color: string, partialAt: readonly boolean[], onClick?: (index: number) => void) {
  const stroke = cssColor(color);
  return function PartialDot({ cx, cy, index }: { cx?: number | string; cy?: number | string; index: number }) {
    if (typeof cx !== "number" || typeof cy !== "number") return null;
    const clickable = onClick === undefined ? {} : { onClick: () => onClick(index), style: { cursor: "pointer" } };
    return partialAt[index] === true ? (
      <circle cx={cx} cy={cy} r={4} fill="var(--mantine-color-body)" stroke={stroke} strokeWidth={2} data-partial="true" {...clickable} />
    ) : (
      <circle cx={cx} cy={cy} r={3} fill={stroke} stroke={stroke} strokeWidth={1} {...clickable} />
    );
  };
}
