import { useLayoutEffect, type HTMLAttributes, type RefObject } from "react";
import { useTranslation } from "react-i18next";
import { Divider, Paper, Portal, Stack, Text } from "@mantine/core";
import type { CellTip } from "../hooks/useDeepDiveTooltip";
import type { TipSection } from "../utils/deepDiveCell";
import classes from "../theme.module.css";

const WIDTH = 320;
const MARGIN = 8;
/** About how tall the card gets with every layer and a few authors, until it is measured. */
const HEIGHT_GUESS = 300;

/**
 * Where the card's top edge goes for its height: abutting the cell (no gap — the pointer must be able to travel
 * from the cell onto the card), under it, or over it when it does not fit below and fits above (else the roomier
 * side), always kept inside the viewport.
 */
function topFor(tip: CellTip, height: number, viewport: number): number {
  const fitsBelow = tip.bottom + height <= viewport - MARGIN;
  const fitsAbove = tip.top - height >= MARGIN;
  const above = !fitsBelow && (fitsAbove || tip.top > viewport - tip.bottom);
  const desired = above ? tip.top - height : tip.bottom;
  return Math.max(MARGIN, Math.min(desired, viewport - height - MARGIN));
}

/** One headed block: a divider line, the small heading with its figure on the right, then label/value detail rows. */
function TipSectionBlock({ section }: { section: TipSection }) {
  return (
    <>
      <Divider />
      <div className={classes.ddTipRow}>
        <span className={classes.ddTipHeading}>{section.heading}</span>
        {section.value !== null && (
          <Text component="span" size="sm" fw={600} className={classes.ddTipValue}>
            {section.value}
          </Text>
        )}
      </div>
      {section.rows.map((row, index) => (
        <div key={`${index}:${row.label}`} className={`${classes.ddTipRow} ${classes.ddTipDetail}`}>
          <span className={classes.ddTipMuted}>{row.label}</span>
          <span className={classes.ddTipValue}>{row.value}</span>
        </div>
      ))}
    </>
  );
}

/**
 * The Deep dive's one shared cell tooltip: a fixed-position card (in a portal, so the scroller never clips it)
 * placed by its measured height next to the cell and kept inside the screen. Its open/close rules (hover, focus,
 * Escape, hoverable) live in `useDeepDiveTooltip`; this only draws what the cell holds for the layers shown — the
 * same numbers the cell's `aria-label` carries — as headed sections (Plan, Approximate EV, EV and done markers,
 * worked-not-earned, cost by author) split by divider lines, label left and tabular figure right. A card taller than
 * the viewport scrolls inside itself.
 */
export default function DeepDiveCellTooltip({
  tip,
  tipRef,
  title,
  span,
  sections,
  tipProps,
}: {
  tip: CellTip;
  tipRef: RefObject<HTMLDivElement | null>;
  title: string;
  /** The date line, already carrying "non-working" for such a column; `null` for a day column, whose title says it. */
  span: string | null;
  /** `null` when the cell holds nothing in the layers shown. */
  sections: TipSection[] | null;
  tipProps: Pick<HTMLAttributes<HTMLElement>, "onMouseEnter" | "onMouseLeave">;
}) {
  const { t } = useTranslation();
  const left = Math.max(MARGIN, Math.min(tip.left, window.innerWidth - WIDTH - MARGIN));

  // Measured after layout and applied straight to the element (no state, no second render); the first paint uses the guess.
  useLayoutEffect(() => {
    const element = tipRef.current;
    const height = element?.offsetHeight ?? 0;
    if (element !== null && height > 0) element.style.top = `${topFor(tip, height, window.innerHeight)}px`;
  }, [tip, tipRef, title, span, sections]);

  return (
    <Portal>
      <Paper
        ref={tipRef}
        role="tooltip"
        withBorder
        shadow="md"
        p="xs"
        radius="md"
        className={classes.ddTooltip}
        style={{ left, width: WIDTH, top: topFor(tip, HEIGHT_GUESS, window.innerHeight) }}
        {...tipProps}
      >
        <Stack gap={4}>
          <Text size="sm" fw={600}>
            {title}
          </Text>
          {span !== null && (
            <Text size="xs" className={classes.ddTipMuted}>
              {span}
            </Text>
          )}
          {sections === null ? (
            <>
              <Divider />
              <Text size="sm">{t("reports.deepDive.matrix.tip.none")}</Text>
            </>
          ) : (
            sections.map((section) => <TipSectionBlock key={section.id} section={section} />)
          )}
        </Stack>
      </Paper>
    </Portal>
  );
}
