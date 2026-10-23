/**
 * The report charts' series colours — the app's colour vocabulary (blue = plan/committed,
 * teal = delivered, orange = carried over / added scope, red = dropped (commitments) or blocked (the WIP and aging charts; one shade, `red.7`),
 * gray = removed/neutral; no new hue) as concrete Mantine theme shades.
 *
 * Every mark must clear WCAG 1.4.11 (≥ 3:1) on every surface a chart sits on: white and the
 * `#f5f7fb` canvas in the light scheme, the `#2e2e2e` paper and the `#1f1f1f` canvas in the dark
 * one. `chartColors.test.ts` recomputes these ratios from the theme, so the table below cannot
 * drift from the code (white / canvas | dark paper / dark canvas):
 *
 *   committed  flow.6    3.56 / 3.32 | 3.82 / 4.63
 *   delivered  teal.8    3.95 / 3.68 | 3.44 / 4.18
 *   carried    orange.8  3.58 / 3.34 | 3.79 / 4.60   (added scope uses the same hue: never both in one chart)
 *   dropped    red.7     3.84 / 3.58 | 3.53 / 4.29   (also the WIP stage band of blocked work, `stageBlocked`)
 *   removed    gray.6    3.32 / 3.10 | 4.09 / 4.96
 *   waiting    grape.6   4.02 / 3.75 | 3.37 / 4.10   (the WIP band of started work with nothing active)
 *   final      flow.8    5.02 / 4.68 (light)   ·   flow.4  5.49 / 6.66 (dark; flow.8 is 2.70:1 on dark paper)
 *
 * Adjacent series in one chart must also be tellable apart with full colour vision (the dataviz
 * skill's ΔE ≥ 15 floor), which a same-hue pair of blues is not — so no chart here plots
 * committed and final side by side except commitments' (see the note there); stacked partitions
 * order their segments so orange and red never touch. The seven neutral hues for arbitrary names (statuses, columns, people)
 * are `CATEGORY_PALETTE` below, with their own figures.
 */
export const CHART_COLORS = {
  committed: "flow.6",
  /** Estimation distributions: blue is the plan side — the estimate the actual cost is measured against. */
  estimate: "flow.6",
  delivered: "teal.8",
  carriedOver: "orange.8",
  dropped: "red.7",
  added: "orange.8",
  removed: "gray.6",
  /** Trend lines: the median is the blue primary, the p90 tail the neutral gray — and it is dashed. */
  trendMedian: "flow.6",
  trendTail: "gray.6",
  /**
   * A histogram's final open bucket ("≥ X", the long tail): neutral gray against the estimate-blue body, so it reads as
   * "everything beyond" rather than another range. Never the only cue: its x label starts "≥", the legend and tooltip name it
   * "Long tail", and its table row says so.
   */
  longTail: "gray.6",
  /** The Home overview's previous-window bar beside the current one: the neutral gray, told from the current bar by position and the legend, never colour alone. */
  previousWindow: "gray.6",
  /** The Aging WIP scatter's one series (the dots themselves wear the server's band, `bandTone`): the neutral gray. */
  agingSeries: "gray.6",
  /** A sprint's capacity: a neutral reference line (gray, DASHED, with dots) over the bars of Commitments (plan blue) and Costs (the actual-cost gray). */
  capacity: "gray.6",
  /** The backlog is planned work nobody has started: the plan blue. */
  backlog: "flow.6",
  /** EVM curves: planned value is the plan blue (and so is the original plan, told apart by its dash), earned value is delivery teal, actual cost the neutral gray. */
  evmPlanned: "flow.6",
  evmOriginalPlan: "flow.6",
  evmEarned: "teal.8",
  evmActual: "gray.6",
  /** Actual cost wherever it is a bar -- Throughput's beside Delivered, Costs' per sprint against its capacity: the same neutral gray as AC in the EVM curves and the Deep dive's cost layer (`evmActual`, `deepDiveCost`) — no new hue. Told from teal (and from Costs' same-gray capacity line) by position, form and the legend, never colour alone. */
  actualCost: "gray.6",
  /** Costs' work-category deltas (actual share minus target, a diverging bar): ONE neutral hue, the cost gray again — the sign is told by the side of the zero line and the signed figure, never by colour (red means blocking in this app, teal done). */
  categoryDelta: "gray.6",
  /** The EVM index trend: SPI is the schedule (plan) blue, CPI the cost gray told apart by its dash — the curves' own shades, no new hue. */
  evmTrendSpi: "flow.6",
  evmTrendCpi: "gray.6",
  /** WIP bands by stage: gray = not started, blue = in progress (active work), grape = waiting (started, nothing active, a queue), red = blocked (started, held by an impediment), teal = done, orange = unmapped (a soft configuration finding). */
  stageNotStarted: "gray.6",
  stageInProgress: "flow.6",
  /** Waiting = started, nothing actively worked on (A30): grape (purple), a hue none of the other stages or semantic states wears. */
  stageWaiting: "grape.6",
  /** Blocked = started, held by an impediment (A36): red, which already means blocked in the vocabulary (the same shade as `dropped`, so no new hue). */
  stageBlocked: "red.7",
  stageDone: "teal.8",
  stageUnmapped: "orange.8",
  /**
   * The Deep dive matrix's three layers, stacked semi-transparent in one cell: plan (PV) is the plan blue, earned value
   * (the Approximate EV bar, the ◆ done marker and the burn-up's EV lines) the delivery teal and cost (AC) the neutral gray —
   * the same shades as above, so no new hue. Each bar carries a solid 2px edge in its colour (the 3:1 mark, with a 1px
   * surface-coloured halo above it so a neighbouring fill never sits against it) and its own width (full, two thirds, one
   * third): colour is never the only cue. The "worked, nothing earned" mark is the earned-value teal again, hollow with a
   * dashed edge. The epic's planned window is the plan blue (dashed).
   */
  deepDivePlan: "flow.6",
  deepDiveEv: "teal.8",
  deepDiveCost: "gray.6",
  deepDiveDone: "teal.8",
  deepDiveWindow: "flow.6",
  /** The non-working-day hatch: a neutral gray line, at least 3:1 against the cell surface in both schemes. */
  deepDiveHatch: "gray.6",
  /**
   * The Deep dive burn-up: its three lines are the EVM curves' colours (`evm*` above — plan blue, delivery teal, neutral
   * gray) and the epics' budget plan is a plan too, so the plan blue again, told apart by its dash (the original-plan idiom).
   */
  deepDiveBurnupBudget: "flow.6",
} as const;

/**
 * How much of its hue a Deep dive bar's translucent fill carries (the rest is the cell's own surface). The bar's solid
 * 2px edge is the 3:1 mark; `chartColors.test.ts` blends this fill over every chart surface and checks the edge against it.
 */
export const DEEP_DIVE_FILL_ALPHA = 0.32;

/**
 * The categorical palette: the colour of the Nth band/segment of a chart whose series are arbitrary NAMES — a WIP chart keyed by
 * status or board column, and the per-person segments of commitments, throughput and the per-person histograms. Such a name
 * must not wear a colour that MEANS something (teal = done, orange = unmapped/carried, red = blocked/dropped, grape = waiting),
 * so those four hues are excluded; what is left is the plan blue (`flow`), cyan, indigo, violet, pink, lime and gray. Each
 * entry is the ONE shade of its hue that clears 3:1 on all four chart surfaces (a hue with no such shade is out: yellow, and
 * green, whose passing shade sits 9 CIEDE2000 from the done teal), so there is no per-scheme pair. Bands are coloured by
 * position among those shown (`palette[index % length]`), and the order is the point: it maximises the CIEDE2000 distance
 * between NEIGHBOURS (dataviz floor ≥ 15; the closest neighbours here are 22.9 apart, the wrap-around lime → blue 60.8),
 * and never puts the near-neighbour cool hues (blue, indigo, violet) next to each other. `chartColors.test.ts`
 * recomputes every ratio and distance from the theme (white / canvas | dark paper / dark canvas | ΔE to the next entry):
 *
 *   flow.6    3.56 / 3.32 | 3.82 / 4.63   ΔE 22.9
 *   violet.5  4.26 / 3.98 | 3.18 / 3.87   ΔE 30.4
 *   cyan.7    3.43 / 3.20 | 3.96 / 4.80   ΔE 25.4
 *   indigo.5  3.67 / 3.42 | 3.70 / 4.49   ΔE 35.4
 *   pink.6    3.73 / 3.48 | 3.64 / 4.42   ΔE 29.9
 *   gray.6    3.32 / 3.10 | 4.09 / 4.96   ΔE 33.1
 *   lime.9    3.69 / 3.44 | 3.68 / 4.47   ΔE 60.8   (to the first entry again)
 *
 * Every entry is also at least ΔE 15 from the four semantic colours (teal.8, orange.8, red.7, grape.6) with ONE accepted exception:
 * violet.5 is 11.9 from the waiting grape (grape.6). That is fine because the palette and the stage keyings never share a chart
 * (`paintBands` uses `STAGE_COLORS` only for by=STAGE and the palette for status/column; only an UNKNOWN stage key falls back to the palette) — a future chart mixing a stage
 * colour with palette colours must revisit it. `chartColors.test.ts` lists the exception explicitly.
 *
 * Seven is all the passing hues give; commitments allow up to eight people, so from the 8th person on the first colour repeats.
 * A chart on this palette therefore still carries identity without colour: the legend, the tooltip and the tables beside the
 * chart, and — where bands are people — a position NUMBER in every label ("2. Bob", `personLabel`). Stage keyings do not use
 * this: a stage has a meaning, and wears its vocabulary colour (`stage*` above).
 */
export const CATEGORY_PALETTE: ReadonlyArray<string> = [
  "flow.6",
  "violet.5",
  "cyan.7",
  "indigo.5",
  "pink.6",
  "gray.6",
  "lime.9",
];

/**
 * The by-epic charts (Commitments' and Throughput's sprint bars split by epic) colour their segments by position:
 * the top epics wear the palette WITHOUT its gray (six hues for the server's six, `EPIC_SEGMENT_TOP`), in segment order, so an epic
 * keeps its colour across the sprints of a chart; "Other epics" — the folded rest — wears the palette's neutral gray; "No epic" wears a
 * second neutral, picked per scheme like `FINAL_COLOR` because no single gray shade clears 3:1 on both (white / canvas | dark paper /
 * dark canvas | ΔE2000 to gray.6): gray.8 11.51 / 10.73 (light, ΔE 31.0); gray.4 9.09 / 11.03 (dark, ΔE 19.8). Identity never rests
 * on colour: the legend, the tooltip and the table name every segment. `chartColors.test.ts` pins the figures.
 */
export const OTHER_EPICS_COLOR = "gray.6";
export const NO_EPIC_COLOR = { light: "gray.8", dark: "gray.4" } as const;
/** The colours of the top epics, in segment order: the categorical palette minus its gray (which is "Other epics"'). */
export const EPIC_PALETTE: ReadonlyArray<string> = CATEGORY_PALETTE.filter((color) => color !== OTHER_EPICS_COLOR);

/** The final-scope blue: the deeper step in the light scheme, a lighter one in the dark. */
export const FINAL_COLOR = { light: "flow.8", dark: "flow.4" } as const;

/**
 * The burn-up's as-of marker (a labelled dashed vertical line): its label is TEXT, so it must clear 4.5:1, which no single
 * shade does on both schemes — a deep gray on the light surfaces, a light gray on the dark ones (picked per scheme like
 * `FINAL_COLOR`). Ratios (white / canvas | dark paper / dark canvas): gray.7 8.18 / 7.62 (light); gray.4 9.09 / 11.03 (dark).
 */
export const AS_OF_COLOR = { light: "gray.7", dark: "gray.4" } as const;
