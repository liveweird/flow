import type { ReactNode } from "react";
import { Stack } from "@mantine/core";
import type { Distribution } from "../api/reports";
import type { DistributionPerson } from "../utils/distributionPeople";
import type { ValueFormat } from "../utils/reportFormat";
import DistributionPanel from "./DistributionPanel";
import ExcludedList, { type ExcludedItem } from "./ExcludedList";

/**
 * One distribution with ITS OWN accounting beneath it: the panel, then the `n + Σ reasons =
 * population` list of that same view. Every distribution-shaped report composes this, so two views
 * with different partitions never share a list. `note` renders between the two (a reading hint
 * that belongs to this distribution, e.g. the reported-time outlier warning).
 */
export default function DistributionWithAccounting({
  title,
  caption,
  distribution,
  minSampleSize,
  format,
  axisLabel,
  population,
  items,
  note,
  people,
}: {
  title: string;
  caption?: string;
  distribution: Distribution;
  minSampleSize: number;
  format: ValueFormat;
  axisLabel: string;
  population: number;
  items: ReadonlyArray<ExcludedItem>;
  note?: ReactNode;
  /** The distribution's per-person split (numbered), forwarded to the panel; none = a plain histogram. */
  people?: readonly DistributionPerson[];
}) {
  return (
    <Stack gap="lg">
      <DistributionPanel
        title={title}
        caption={caption}
        distribution={distribution}
        minSampleSize={minSampleSize}
        format={format}
        axisLabel={axisLabel}
        people={people}
      />
      {note}
      <ExcludedList population={population} measured={distribution.n} items={items} />
    </Stack>
  );
}
