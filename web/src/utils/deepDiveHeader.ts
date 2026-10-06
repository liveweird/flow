import type { TimeColumn } from "./deepDiveCalendar";

const DEPTH = { month: 0, week: 1, day: 2 } as const;

/** One header cell: a leaf (a column of the grid, spanning down to the body) or a group (an open month/week spanning its children). */
export interface HeaderCell {
  role: "leaf" | "group";
  /** The column the cell speaks for: the leaf's own, or the open parent's. */
  column: TimeColumn;
  /** The grid row: the bottom header row is 0 and the rows above it are negative, so body rows (1…) never move when a drill adds a header row. */
  r: number;
  /** The first grid column it covers (0 is the item column, time columns are 1…). */
  c: number;
  colSpan: number;
  rowSpan: number;
}

export interface HeaderModel {
  /** How many header rows there are (1 for months only, 2 with weeks, 3 with days). */
  depth: number;
  /** The corner cell's grid row (the top header row). */
  cornerRow: number;
  /** Per header row, top to bottom, the cells it holds (the corner cell and cells covered by a rowSpan are not in it). */
  rows: HeaderCell[][];
}

/**
 * The header of a drilled partition as nested rows: an open month is a cell spanning its weeks and an open week one
 * spanning its days, so the thing that was opened stays on screen (and is the control that closes it) with the
 * columns it produced beneath. A column shallower than the deepest one spans down to the body.
 */
export function headerModel(
  columns: readonly TimeColumn[],
  lineage: ReadonlyMap<string, TimeColumn>,
): HeaderModel {
  const depth = columns.reduce((max, c) => Math.max(max, DEPTH[c.kind]), 0) + 1;
  const chains = columns.map((column) => {
    const chain: TimeColumn[] = [];
    let parent = column.parentId === null ? undefined : lineage.get(column.parentId);
    while (parent !== undefined) {
      chain.unshift(parent);
      parent = parent.parentId === null ? undefined : lineage.get(parent.parentId);
    }
    return chain;
  });
  const rows: HeaderCell[][] = [];
  for (let k = 0; k < depth; k += 1) {
    const cells: HeaderCell[] = [];
    columns.forEach((column, index) => {
      const own = DEPTH[column.kind];
      if (own < k) return;
      const r = k - (depth - 1);
      if (own === k) {
        cells.push({ role: "leaf", column, r, c: index + 1, colSpan: 1, rowSpan: depth - k });
        return;
      }
      const ancestor = chains[index][k];
      const last = cells[cells.length - 1];
      if (last !== undefined && last.role === "group" && last.column.id === ancestor.id) last.colSpan += 1;
      else cells.push({ role: "group", column: ancestor, r, c: index + 1, colSpan: 1, rowSpan: 1 });
    });
    rows.push(cells);
  }
  return { depth, cornerRow: 1 - depth, rows };
}
