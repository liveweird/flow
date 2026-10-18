/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding: the probes are components exported with their mock object; fast-refresh is irrelevant under vitest
// recharts renders nothing under happy-dom (no layout): the Home charts are probes of their props. A bar chart exposes its row
// labels, its first series' values and its orientation, and carries one button per (row, series) firing the `onDoubleClick` recharts
// would forward to that series' rectangle; a line chart exposes its points and fires the dot handler the same way.
// Used as `vi.mock("@mantine/charts", () => homeChartsMock)` (the factory is hoisted, so the module is loaded lazily).

interface Series {
  name: string;
  label?: string;
  color: string;
  strokeDasharray?: string;
}

/** How many times a chart probe has rendered: a test asserts that opening a drill does not redraw the charts. */
let renders = 0;
const noteRender = () => {
  renders += 1;
};
export const chartRenders = {
  get count() {
    return renders;
  },
};

type Row = Record<string, unknown>;
type DoubleClick = (entry: { payload: unknown }, index: number, event: unknown) => void;
type TooltipContent = (props: { label: unknown; payload: { name: string; value: unknown; payload: Row }[] }) => unknown;

function BarChart(props: {
  data: Row[];
  dataKey: string;
  series: Series[];
  orientation?: string;
  barProps?: (series: Series) => { onDoubleClick?: DoubleClick };
  tooltipProps?: { content?: TooltipContent };
}) {
  const first = props.series[0].name;
  noteRender();
  return (
    <div
      data-testid="bar-chart"
      data-orientation={props.orientation ?? "horizontal"}
      data-labels={props.data.map((row) => String(row[props.dataKey])).join("|")}
      data-values={props.data.map((row) => String(row[first])).join(",")}
      data-series={props.series.map((series) => series.name).join(",")}
    >
      {props.data.flatMap((row, index) => [
        ...props.series.map((series) => (
          <button
            key={`${index}-${series.name}`}
            data-testid={`dbl-${series.name}-${index}`}
            onClick={() => props.barProps?.(series).onDoubleClick?.({ payload: row }, index, {})}
          />
        )),
        // The tooltip the real chart would show while hovering this row.
        <div key={`tip-${index}`} data-testid={`tooltip-${index}`}>
          {props.tooltipProps?.content?.({ label: row[props.dataKey], payload: [{ name: first, value: row[first], payload: row }] }) as never}
        </div>,
      ])}
    </div>
  );
}

function LineChart(props: {
  data: Row[];
  dataKey: string;
  series: Series[];
  connectNulls?: boolean;
  lineProps?: (series: Series) => {
    dot?: { onDoubleClick?: (...args: unknown[]) => void };
    activeDot?: { onDoubleClick?: (...args: unknown[]) => void };
  };
  tooltipProps?: { content?: TooltipContent };
}) {
  noteRender();
  return (
    <div
      data-testid="line-chart"
      data-connect-nulls={String(props.connectNulls)}
      data-labels={props.data.map((row) => String(row[props.dataKey])).join("|")}
      data-series={props.series.map((series) => `${series.name}${series.strokeDasharray ? ":dashed" : ""}`).join(",")}
      data-tasks={props.data.map((row) => String(row.tasks)).join(",")}
      data-epics={props.data.map((row) => String(row.epics)).join(",")}
    >
      {props.data.flatMap((row, index) => [
        ...props.series.flatMap((series) => [
          <button
            key={`${index}-${series.name}`}
            data-testid={`dot-${series.name}-${index}`}
            // recharts calls a plain dot's handler with (dot props, event) ...
            onClick={() => props.lineProps?.(series).dot?.onDoubleClick?.({ payload: row }, {})}
          />,
          <button
            key={`${index}-${series.name}-active`}
            data-testid={`active-${series.name}-${index}`}
            // ... and the shape has differed for the active dot (event first): the handler must not trust positions.
            onClick={() => props.lineProps?.(series).activeDot?.onDoubleClick?.({ type: "dblclick" }, { payload: row })}
          />,
        ]),
        <div key={`tip-${index}`} data-testid={`tooltip-${index}`}>
          {
            props.tooltipProps?.content?.({
              label: row[props.dataKey],
              payload: props.series.map((series) => ({ name: series.name, value: row[series.name], payload: row })),
            }) as never
          }
        </div>,
      ])}
    </div>
  );
}

/**
 * The tooltip body, as data attributes: its label and `series label=formatted value` per item. Like Mantine's own, it reads each
 * item's figure from `item.payload[item.name]` (the data row) -- so an item built by hand without a `payload` throws here as it does
 * in the browser (a real crash an e2e run once found), instead of passing because the mock trusted `item.value`.
 */
function ChartTooltip(props: {
  label?: string;
  payload?: { name: string; value: number; payload: Row }[];
  series: Series[];
  valueFormatter?: (value: number) => string;
}) {
  const format = props.valueFormatter ?? String;
  return (
    <span
      data-testid="tooltip-content"
      data-label={props.label}
      data-items={(props.payload ?? [])
        .map((item) => `${props.series.find((series) => series.name === item.name)?.label ?? item.name}=${format(item.payload[item.name] as number)}`)
        .join(" | ")}
    />
  );
}

export const homeChartsMock = { BarChart, LineChart, ChartTooltip };
