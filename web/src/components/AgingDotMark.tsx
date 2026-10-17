import { AGING_BLOCKED_COLOR, colorVar } from "../utils/agingChart";

/** Radii of the dot and its rings, in px (the dot is 12px across: ≥ 8px by the marker spec). */
const DOT_R = 6;
const BLOCKED_R = 10;
const SELECTED_R = 13.5;

/**
 * The aging dot, drawn around (0, 0) inside an `<svg>`: a solid dot in its band colour with a 2px surface ring (so overlapping
 * dots stay separate), or — when the item is WAITING — a hollow one (surface fill, band-coloured outline). A blocked item gets a
 * red outer ring with a gap. Colour is never the only carrier: shape (hollow, ringed) and the tooltip, legend and table say it too.
 */
export default function AgingDotMark({
  color,
  waiting,
  blocked,
  selected = false,
}: {
  /** The band colour as a Mantine name (`"red.7"`). */
  color: string;
  waiting: boolean;
  blocked: boolean;
  selected?: boolean;
}) {
  const band = colorVar(color);
  return (
    <>
      {selected && <circle r={SELECTED_R} fill="none" stroke="var(--mantine-color-text)" strokeWidth={1.5} data-mark="selected" />}
      {blocked && <circle r={BLOCKED_R} fill="none" stroke={colorVar(AGING_BLOCKED_COLOR)} strokeWidth={2} data-mark="blocked" />}
      {waiting ? (
        <circle r={DOT_R - 0.75} fill="var(--mantine-color-body)" stroke={band} strokeWidth={2.5} data-mark="waiting" />
      ) : (
        <circle r={DOT_R} fill={band} stroke="var(--mantine-color-body)" strokeWidth={2} data-mark="solid" />
      )}
    </>
  );
}

/** The dot as a legend glyph: the same marks in a small, decorative `<svg>`. */
export function AgingDotGlyph({ color, waiting = false, blocked = false }: { color: string; waiting?: boolean; blocked?: boolean }) {
  return (
    <svg width={24} height={24} viewBox="-12 -12 24 24" aria-hidden focusable="false">
      <AgingDotMark color={color} waiting={waiting} blocked={blocked} />
    </svg>
  );
}
