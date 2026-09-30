import type { ReactNode } from "react";
import { Table } from "@mantine/core";
import classes from "../theme.module.css";

/**
 * The one scroller for a table that can outgrow its box (wider than the page, or capped by
 * `maxHeight`): a native scroller that is itself a focusable, labelled region, so the keyboard can
 * reach and scroll it (axe: scrollable-region-focusable) and assistive technology names it. `label`
 * is the table's own accessible name. Never reach for a bare `Table.ScrollContainer`.
 */
export default function ScrollRegion({
  label,
  minWidth,
  maxHeight,
  className = classes.tableScroll,
  children,
}: {
  label: string;
  minWidth: number;
  maxHeight?: number | string;
  /** Overrides the default focus ring class (the cost matrix carries its own sticky-column styling). */
  className?: string;
  children: ReactNode;
}) {
  return (
    <Table.ScrollContainer type="native" minWidth={minWidth} maxHeight={maxHeight} className={className} tabIndex={0} role="region" aria-label={label}>
      {children}
    </Table.ScrollContainer>
  );
}
