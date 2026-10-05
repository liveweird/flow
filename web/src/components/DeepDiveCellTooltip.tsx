import { useLayoutEffect, type HTMLAttributes, type RefObject } from "react";
import { useTranslation } from "react-i18next";
import { List, Paper, Portal, Stack, Text } from "@mantine/core";
import type { CellTip } from "../hooks/useDeepDiveTooltip";
import type { CellFacts } from "../utils/deepDiveCell";
import classes from "../theme.module.css";

const WIDTH = 320;
const MARGIN = 8;
/** About how tall the card gets with every layer and a few authors, until it is measured. */
const HEIGHT_GUESS = 220;

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

/**
 * The Deep dive's one shared cell tooltip: a fixed-position card (in a portal, so the scroller never clips it)
 * placed by its measured height next to the cell and kept inside the screen. Its open/close rules (hover, focus,
 * Escape, hoverable) live in `useDeepDiveTooltip`; this only draws what the cell holds for the layers shown — the
 * same numbers the cell's `aria-label` carries. A card taller than the viewport scrolls inside itself.
 */
export default function DeepDiveCellTooltip({
  tip,
  tipRef,
  title,
  span,
  facts,
  tipProps,
}: {
  tip: CellTip;
  tipRef: RefObject<HTMLDivElement | null>;
  title: string;
  /** The date line, already carrying "non-working" for such a column; `null` for a day column, whose title says it. */
  span: string | null;
  /** `null` when the cell holds nothing in the layers shown. */
  facts: CellFacts | null;
  tipProps: Pick<HTMLAttributes<HTMLElement>, "onMouseEnter" | "onMouseLeave">;
}) {
  const { t } = useTranslation();
  const left = Math.max(MARGIN, Math.min(tip.left, window.innerWidth - WIDTH - MARGIN));
  const lines =
    facts === null
      ? [t("reports.deepDive.matrix.tip.none")]
      : [facts.pv, facts.exec, facts.ev, facts.done, facts.cost].filter((line): line is string => line !== null);

  // Measured after layout and applied straight to the element (no state, no second render); the first paint uses the guess.
  useLayoutEffect(() => {
    const element = tipRef.current;
    const height = element?.offsetHeight ?? 0;
    if (element !== null && height > 0) element.style.top = `${topFor(tip, height, window.innerHeight)}px`;
  }, [tip, tipRef, title, span, facts]);

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
        <Stack gap={2}>
          <Text size="sm" fw={600}>
            {title}
          </Text>
          {span !== null && (
            <Text size="xs" c="dimmed">
              {span}
            </Text>
          )}
          {lines.map((line) => (
            <Text key={line} size="sm">
              {line}
            </Text>
          ))}
          {facts !== null && facts.authors.length > 0 && (
            <List size="xs" withPadding>
              {facts.authors.map((author, index) => (
                <List.Item key={`${index}:${author}`}>{author}</List.Item>
              ))}
            </List>
          )}
        </Stack>
      </Paper>
    </Portal>
  );
}
