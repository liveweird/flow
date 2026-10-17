import type { ReactNode } from "react";
import DrillFigure from "../components/DrillFigure";
import type { ColumnDef } from "../components/ColumnTable";

/**
 * What makes a column's figure drillable. `label` builds the button's accessible name from the row, the figure as the cell shows it
 * and the column's header (`reports.drill.figureAria`: "20 — Initial (MD): show the items of Alpha 2 · Committed"): it starts with the
 * visible figure (WCAG 2.5.3 label in name) and the column tells two figures of one bucket (MD, items) apart.
 */
interface DrillFigureSpec<T> {
  label: (row: T, figure: string, column: string) => string;
  count: (row: T) => number;
  onOpen?: (row: T, trigger: HTMLElement) => void;
  disabled?: boolean;
}

/** The text of a figure cell: our renders return a string or a number, anything else has no text to put in a name. */
function figureText(node: ReactNode): string {
  return typeof node === "string" || typeof node === "number" ? String(node) : "";
}

/** The column with its figure wrapped in a `DrillFigure` (the column's own `render` still draws the figure). */
export function drillFigureColumn<T>(column: ColumnDef<T>, spec: DrillFigureSpec<T>): ColumnDef<T> {
  const header = typeof column.header === "string" ? column.header : column.key;
  return {
    ...column,
    render: (row) => {
      const { onOpen } = spec;
      const figure = column.render(row);
      return (
        <DrillFigure
          label={spec.label(row, figureText(figure), header)}
          count={spec.count(row)}
          disabled={spec.disabled}
          onOpen={onOpen && ((trigger) => onOpen(row, trigger))}
        >
          {figure}
        </DrillFigure>
      );
    },
  };
}
