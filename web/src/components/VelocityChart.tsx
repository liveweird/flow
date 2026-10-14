import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip, CompositeChart } from "@mantine/charts";
import type { ReactNode } from "react";
import { Box, Text, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { BAND_CYCLE, CHART_COLORS, DEEP_DIVE_FILL_ALPHA, FINAL_COLOR } from "../utils/chartColors";
import { formatMd } from "../utils/reportFormat";
import { hasCapacity, personLabel, type VelocityChartRow, type VelocityChartUser, type VelocityUserRow } from "../utils/velocityReport";

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
export type VelocityChartProps =
  | { mode: "team"; rows: VelocityChartRow[] }
  | { mode: "user"; users: VelocityChartUser[]; rows: VelocityUserRow[] };

/** Velocity's chart — a lazy chunk: recharts never enters the main bundle. */
export default function VelocityChart(props: VelocityChartProps) {
  const { t } = useTranslation();
  return (
    <Box role="group" aria-label={t(props.mode === "team" ? "reports.velocity.chartLabel" : "reports.velocity.userChartLabel")}>
      {props.mode === "team" ? <TeamChart rows={props.rows} /> : <UserChart users={props.users} rows={props.rows} />}
    </Box>
  );
}

const tiltedAxis = (rows: readonly unknown[]) =>
  rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end" as const, height: 80 } : undefined;

/**
 * Committed + final bars and the capacity as a dashed gray line with dots (one marker per sprint, ≥ 8 px): ONE axis, all
 * MD. A sprint with no capacity has no marker (`connectNulls` off — a gap, never a zero), and the whole series stays out
 * when no sprint has one. A DEFAULT capacity is told from a configured one by words, not colour: the tooltip says it is
 * members × working days (and the table beside the chart carries a "default" badge).
 */
function TeamChart({ rows }: { rows: VelocityChartRow[] }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const series = [
    { name: "initialMd", type: "bar" as const, label: t("reports.velocity.series.initial"), color: INITIAL_COLOR },
    { name: "finalMd", type: "bar" as const, label: t("reports.velocity.series.final"), color: FINAL_COLOR[scheme] },
    ...(hasCapacity(rows)
      ? [
          {
            name: "capacityMd",
            type: "line" as const,
            label: t("reports.velocity.series.capacity"),
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
          const row = payload?.[0]?.payload as VelocityChartRow | undefined;
          const note = row?.capacitySource ? t(`reports.velocity.capacitySource.${row.capacitySource}`) : null;
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
 * surface so stacked segments never touch), one segment per person. People are arbitrary names, so they wear the neutral
 * blue/gray cycle by position (`BAND_CYCLE`, neighbours alternate hue) — never a semantic hue. Known limit: that cycle has
 * three distinct shades, so from the fourth person on a colour repeats (and the 8-person cap allows up to eight). Identity
 * therefore never rests on colour: each person is NUMBERED by stack position (bottom first) — "2. Bob" — in the legend, the
 * tooltip ("2. Bob · initial") and the per-person tables. Committed vs final is told by fill, position and the legend's
 * neutral glyphs, never by colour. No capacity here: it is a team figure.
 */
function UserChart({ users, rows }: { users: VelocityChartUser[]; rows: VelocityUserRow[] }) {
  const { t } = useTranslation();
  const colour = (index: number) => BAND_CYCLE[index % BAND_CYCLE.length];
  const series = [
    ...users.map((user, index) => ({
      name: user.committedKey,
      label: t("reports.velocity.userSeries.committed", { name: personLabel(user.position, user.label) }),
      color: colour(index),
      stackId: "committed",
    })),
    ...users.map((user, index) => ({
      name: user.finalKey,
      label: t("reports.velocity.userSeries.final", { name: personLabel(user.position, user.label) }),
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

/** A person's swatch: their cycle colour, solid. The numbered label next to it is what actually identifies them. */
function PersonSwatch({ colour }: { colour: string }) {
  const hue = `var(--mantine-color-${colour.replace(".", "-")})`;
  return <Box aria-hidden w={14} h={14} style={{ border: `2px solid ${hue}`, background: hue }} />;
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
function UserLegend({ users, colour }: { users: VelocityChartUser[]; colour: (index: number) => string }) {
  const { t } = useTranslation();
  const entry = (key: string, swatch: ReactNode, text: string) => (
    <Box component="li" key={key} display="flex" style={{ alignItems: "center", gap: 6 }}>
      {swatch}
      <Text size="xs">{text}</Text>
    </Box>
  );
  return (
    <Box
      component="ul"
      role="list"
      aria-label={t("reports.velocity.userLegendLabel")}
      display="flex"
      m={0}
      p={0}
      mb="xs"
      style={{ listStyle: "none", flexWrap: "wrap", gap: "4px 16px" }}
    >
      {users.map((user, index) => entry(user.accountId, <PersonSwatch colour={colour(index)} />, personLabel(user.position, user.label)))}
      {entry("committed", <TreatmentGlyph solid={false} />, t("reports.velocity.userLegend.committed"))}
      {entry("final", <TreatmentGlyph solid />, t("reports.velocity.userLegend.final"))}
    </Box>
  );
}
