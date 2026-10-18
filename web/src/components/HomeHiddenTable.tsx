import { useRovingButtons } from "../hooks/useRovingButtons";
import classes from "../theme.module.css";
import ColumnTable, { type ColumnDef } from "./ColumnTable";

/**
 * A Home chart's numbers as a table that is visually hidden until something inside it takes keyboard focus (the skip-link idiom,
 * `homeHiddenTable` in `theme.module.css`). Screen readers always read it; a keyboard user Tabs onto its drill figures -- ONE Tab
 * stop per table, arrow keys between the figures (`useRovingButtons`) -- which reveals it in place; a mouse user never sees it and
 * double-clicks the bars instead. `label` is the table's accessible name -- the words of the chart's title.
 */
export default function HomeHiddenTable<T>({
  label,
  columns,
  rows,
  rowKey,
}: {
  label: string;
  columns: ReadonlyArray<ColumnDef<T>>;
  rows: ReadonlyArray<T>;
  rowKey: (row: T, index: number) => string;
}) {
  const { ref, onKeyDown, onFocus } = useRovingButtons();
  return (
    // The wrapper only forwards the keys and focus of the buttons inside it; the interactive elements are those buttons.
    <div ref={ref} className={classes.homeHiddenTable} onKeyDown={onKeyDown} onFocus={onFocus}>
      <ColumnTable aria-label={label} verticalSpacing={4} columns={columns} rows={rows} rowKey={rowKey} />
    </div>
  );
}
