import type { DrillState } from "./useReportDrill";

/** The `onDrill` callback of a distribution (`DistributionPanel`): the bar, the person's segment of it (if any) and the pressed element. */
type BarOpener = (bucketIndex: number, segmentAccountId?: string, trigger?: HTMLElement) => void;

export interface HistogramDrillOptions {
  /** The chart card the focus returns to when the opener names none of its own (default: the drill hook's). */
  anchor?: string;
  /**
   * How a person's segment is treated: `"optional"` (default) opens the bar of everyone or of the one person; `"none"` always opens the
   * whole bar (the report has no people to split by); `"required"` opens nothing for a bar with no person (grouped bars, one person's each).
   */
  segment?: "optional" | "none" | "required";
}

/**
 * The opener of a histogram drill, shared by every report page that lists the items behind a bar (`kind: "hist"`): `openBar(view)`
 * is the `onDrill` of one distribution, `view` being the drill's own name for it. A second argument names the chart card of that
 * one opener (a page with several cards). Pure wiring over `useReportDrill`'s `open` — the drill target it builds is what the URL
 * spells, so the links stay byte-identical across pages.
 */
export function useHistogramDrill(drill: DrillState, { anchor, segment = "optional" }: HistogramDrillOptions = {}) {
  return (view: string, cardAnchor: string | undefined = anchor): BarOpener =>
    (bucketIndex, segmentAccountId, trigger) => {
      const from = { trigger, anchor: cardAnchor };
      if (segment === "none" || segmentAccountId === undefined || segmentAccountId === "") {
        // A bar with no person (or a report without people): the whole bar — except where the person is the point.
        if (segment !== "required") drill.open({ kind: "hist", view, bucketIndex }, from);
        return;
      }
      drill.open({ kind: "hist", view, bucketIndex, segmentAccountId }, from);
    };
}
