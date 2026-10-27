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

/**
 * The extra props a line's dots (and its active dot) get to make a point double-clickable: a pointer cursor and an `onDoubleClick`
 * naming the plotted row's string `id` and the [series]. recharts hands a dot handler its dot props and the event -- in that order for
 * a plain dot, and the library has changed the shape before -- so the handler takes whichever argument carries the `payload` (the data
 * row) instead of trusting a position. With no handler it adds nothing.
 */
export function dotDoubleClickProps(series: string, onHit: ((hit: ChartBarHit) => void) | undefined) {
  if (!onHit) return {};
  return {
    style: { cursor: "pointer" },
    onDoubleClick: (...args: unknown[]) => {
      const dot = args.find((arg): arg is { payload?: { id?: unknown } } => typeof arg === "object" && arg !== null && "payload" in arg);
      const rowId = dot?.payload?.id;
      if (typeof rowId === "string") onHit({ rowId, series });
    },
  };
}

/**
 * The extra props a TREND line's dots (and its active dot) get to make a point CLICKABLE: a pointer cursor and an `onClick` naming the
 * plotted row's `label` (a trend row's x label, the window's last day) and the [series]. The handler takes whichever argument carries the
 * `payload` (the data row), like [dotDoubleClickProps]. A gap draws no dot and is never active, so it cannot be clicked. With no handler it
 * adds nothing.
 */
export function dotClickProps(series: string, onHit: ((hit: ChartBarHit) => void) | undefined) {
  if (!onHit) return {};
  return {
    style: { cursor: "pointer" },
    onClick: (...args: unknown[]) => {
      const dot = args.find((arg): arg is { payload?: { label?: unknown } } => typeof arg === "object" && arg !== null && "payload" in arg);
      const rowId = dot?.payload?.label;
      if (typeof rowId === "string") onHit({ rowId, series });
    },
  };
}
