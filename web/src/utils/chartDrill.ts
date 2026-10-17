/** What a double-click on a bar reports: the plotted row's `id` and the series (`name`) of the bar or stacked segment hit. */
export interface ChartBarHit {
  rowId: string;
  series: string;
}

/**
 * The extra props a drillable bar series gets from its chart's `barProps(series)` function: a pointer cursor and the
 * `onDoubleClick` recharts forwards to every rectangle of the `<Bar>` as `(entry, index, event)`, where `entry.payload` is the
 * plotted data row (every drillable chart's rows carry a string `id`). With no handler it adds nothing, so a chart that is
 * not drillable keeps its props exactly as they were. Single click and hover stay untouched.
 */
export function barDoubleClickProps(series: string, onHit: ((hit: ChartBarHit) => void) | undefined) {
  if (!onHit) return {};
  return {
    cursor: "pointer",
    onDoubleClick: (entry: { payload?: unknown }) => {
      const rowId = (entry.payload as { id?: unknown } | undefined)?.id;
      if (typeof rowId === "string") onHit({ rowId, series });
    },
  };
}
