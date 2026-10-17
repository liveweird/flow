import type { ReactNode } from "react";
import { UnstyledButton } from "@mantine/core";
import classes from "../theme.module.css";

/**
 * A table figure that a drill bucket stands behind, as a link-styled button: pressing it opens the items pane of that bucket (the
 * keyboard and screen-reader route to the chart's pointer-only double-click). `label` is its accessible name: it starts with the
 * visible figure (WCAG 2.5.3) and then names the column and the row (and bucket), "20 — Initial (MD): show the items of Alpha 2 ·
 * Committed", so two figures of one bucket (MD, items) read differently. Nothing to list (`count` 0), a missing handler or `disabled` (the rows are kept
 * data of a previous filter) renders the figure as the plain text it always was. `onOpen` gets the pressed button, so focus can return
 * to it even where a mouse click does not focus a button (Safari, Firefox).
 */
export default function DrillFigure({
  children,
  label,
  count,
  disabled = false,
  onOpen,
}: {
  children: ReactNode;
  label: string;
  /** How many items the figure stands for: 0 = nothing to list, so no button. */
  count: number;
  disabled?: boolean;
  onOpen?: (trigger: HTMLElement) => void;
}) {
  if (disabled || count === 0 || onOpen === undefined) return <>{children}</>;
  return (
    <UnstyledButton type="button" className={classes.drillFigure} aria-label={label} onClick={(event) => onOpen(event.currentTarget)}>
      {children}
    </UnstyledButton>
  );
}
