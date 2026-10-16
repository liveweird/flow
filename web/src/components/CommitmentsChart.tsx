import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip, CompositeChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CATEGORY_PALETTE, CHART_COLORS, DEEP_DIVE_FILL_ALPHA, FINAL_COLOR } from "../utils/chartColors";
import { formatMd } from "../utils/reportFormat";
import { hasCapacity, personLabel, type CommitmentsChartRow, type CommitmentsChartUser, type CommitmentsUserRow } from "../utils/commitmentsReport";
import ChartLegendList, { PersonSwatch } from "./ChartLegendList";

// Blue = plan/committed: initial is the committed blue, final the per-scheme deeper/lighter step
// (utils/chartColors.ts has the contrast table). Known limit: two same-hue blues side by side are
// ≥ 3:1 against the surface but only ΔE ≈ 13 from each other — the legend, the tooltip and the
// table beside the chart carry the identity, and the sprint-consistency charts avoid the pair.
const INITIAL_COLOR = CHART_COLORS.committed;

/** The committed stack of user mode is the Deep dive's idiom: a translucent fill inside a solid 2px edge (the 3:1 mark). */
const COMMITTED_FILL_ALPHA = DEEP_DIVE_FILL_ALPHA;
const MAX_BAR = 28;

/**
 * Team mode: the sprints' committed and final bars and, per sprint, the team's capacity. User mode: the picked people's
 * contribution, two stacked columns per sprint (committed, final) cut into one segment per person.
 */
export type CommitmentsChartProps =
  | { mode: "team"; rows: CommitmentsChartRow[] }
  | { mode: "user"; users: CommitmentsChartUser[]; rows: CommitmentsUserRow[] };

/** Commitments' chart — a lazy chunk: recharts never enters the main bundle. */
export default function CommitmentsChart(props: CommitmentsChartProps) {
  const { t } = useTranslation();
  return (
    <Box role="group" aria-label={t(props.mode === "team" ? "reports.commitments.chartLabel" : "reports.commitments.userChartLabel")}>
      {props.mode === "team" ? <TeamChart rows={props.rows} /> : <UserChart users={props.users} rows={props.rows} />}
    </Box>
  );
}

const tiltedAxis = (rows: readonly unknown[]) =>
  rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end" as const, height: 80 } : undefined;

/**
 * Committed + final bars and the capacity as a dashed gray line with dots (one marker per sprint, ≥ 8 px): ONE axis, all
 * MD. A sprint with no capacity has no marker (`connectNulls` off — a gap, never a zero), and the whole series stays out
 * when no sprint has one. Where the capacity comes from (the calendar, the team default, members × working days) is told by words,
 * not colour: the tooltip names the source (and the table beside the chart carries a "default"/"team default" badge).
 */
function TeamChart({ rows }: { rows: CommitmentsChartRow[] }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const series = [
    { name: "initialMd", type: "bar" as const, label: t("reports.commitments.series.initial"), color: INITIAL_COLOR },
    { name: "finalMd", type: "bar" as const, label: t("reports.commitments.series.final"), color: FINAL_COLOR[scheme] },
    ...(hasCapacity(rows)
      ? [
          {
            name: "capacityMd",
            type: "line" as const,
            label: t("reports.commitments.series.capacity"),
            color: CHART_COLORS.capacity,
            strokeDasharray: "6 4",
          },
        ]
      : []),
  ];
  return (
    <CompositeChart
      h={320}
      data={rows}
      dataKey="label"
      series={series}
      curveType="linear"
      connectNulls={false}
      withDots
      dotProps={{ r: 4 }}
      withLegend
      legendProps={{ verticalAlign: "top", height: 36 }}
      yAxisLabel={t("reports.unitMd")}
      valueFormatter={formatMd}
      tickLine="y"
      barProps={{ maxBarSize: MAX_BAR }}
      xAxisProps={tiltedAxis(rows)}
      tooltipProps={{
        content: ({ label, payload }) => {
          const row = payload?.[0]?.payload as CommitmentsChartRow | undefined;
          const note = row?.capacitySource ? t(`reports.commitments.capacitySource.${row.capacitySource}`) : null;
          return (
            <ChartTooltip
              label={note === null ? label : `${String(label)} · ${note}`}
              payload={(payload ?? []).filter((entry) => entry.name !== "capacityMd" || row?.capacityMd != null)}
              series={series}
              valueFormatter={formatMd}
            />
          );
        },
      }}
    />
  );
}

/**
 * Two stacks per sprint, `committed` (left; translucent fill inside a solid edge) and `final` (right; solid, ringed in the
 * surface so stacked segments never touch), one segment per person. People are arbitrary names, so they wear the categorical
 * `CATEGORY_PALETTE` by position (never a semantic hue). Known limit: it has seven entries, so the 8th person (the cap allows eight)
 * repeats the first colour. Identity therefore never
 * rests on colour: each person is NUMBERED by stack position (bottom first) — "2. Bob" — in the legend, the
 * tooltip ("2. Bob · initial") and the per-person tables. Committed vs final is told by fill, position and the legend's
 * neutral glyphs, never by colour. No capacity here: it is a team figure.
 */
function UserChart({ users, rows }: { users: CommitmentsChartUser[]; rows: CommitmentsUserRow[] }) {
  const { t } = useTranslation();
  const colour = (index: number) => CATEGORY_PALETTE[index % CATEGORY_PALETTE.length];
  const series = [
    ...users.map((user, index) => ({
      name: user.committedKey,
      label: t("reports.commitments.userSeries.committed", { name: personLabel(user.position, user.label) }),
      color: colour(index),
      stackId: "committed",
    })),
    ...users.map((user, index) => ({
      name: user.finalKey,
      label: t("reports.commitments.userSeries.final", { name: personLabel(user.position, user.label) }),
      color: colour(index),
      stackId: "final",
    })),
  ];
  return (
    <>
      <UserLegend users={users} colour={colour} />
      <BarChart
        h={320}
        data={rows}
        dataKey="label"
        series={series}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={(item) =>
          item.stackId === "committed"
            ? { maxBarSize: MAX_BAR, fillOpacity: COMMITTED_FILL_ALPHA, strokeOpacity: 1, strokeWidth: 2 }
            : { maxBarSize: MAX_BAR, stroke: "var(--mantine-color-body)", strokeOpacity: 1, strokeWidth: 2 }
        }
        xAxisProps={tiltedAxis(rows)}
      />
    </>
  );
}

/**
 * The glyph of a stack treatment: a tall column in the TEXT colour (no chart hue, so it can never be mistaken for a person's
 * swatch), outlined for the committed column and solid for the final one.
 */
function TreatmentGlyph({ solid }: { solid: boolean }) {
  return (
    <Box
      aria-hidden
      w={8}
      h={16}
      style={{ border: "2px solid currentColor", background: solid ? "currentColor" : "transparent", marginInline: 3 }}
    />
  );
}

/** The legend of user mode: one numbered entry per person (their colour), then the two stacks' treatments as neutral glyphs. */
function UserLegend({ users, colour }: { users: CommitmentsChartUser[]; colour: (index: number) => string }) {
  const { t } = useTranslation();
  return (
    <ChartLegendList
      label={t("reports.commitments.userLegendLabel")}
      entries={[
        ...users.map((user, index) => ({
          key: user.accountId,
          glyph: <PersonSwatch colour={colour(index)} />,
          text: personLabel(user.position, user.label),
        })),
        { key: "committed", glyph: <TreatmentGlyph solid={false} />, text: t("reports.commitments.userLegend.committed") },
        { key: "final", glyph: <TreatmentGlyph solid />, text: t("reports.commitments.userLegend.final") },
      ]}
    />
  );
}
