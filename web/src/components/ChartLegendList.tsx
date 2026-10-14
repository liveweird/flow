import type { ReactNode } from "react";
import { Box, Text } from "@mantine/core";

/** One legend entry: a glyph (a swatch, or a neutral treatment glyph) and the words that actually identify it. */
export interface LegendEntry {
  key: string;
  glyph: ReactNode;
  text: string;
}

/** A person's swatch: their cycle colour, solid. The numbered label next to it is what actually identifies them. */
export function PersonSwatch({ colour }: { colour: string }) {
  const hue = `var(--mantine-color-${colour.replace(".", "-")})`;
  return <Box aria-hidden w={14} h={14} style={{ border: `2px solid ${hue}`, background: hue }} />;
}

/**
 * The page's own chart legend, a list (used where the chart's own legend cannot carry the identity — people wear a
 * repeating neutral cycle, so they are numbered): a glyph and a label per entry, wrapping.
 */
export default function ChartLegendList({ label, entries }: { label: string; entries: readonly LegendEntry[] }) {
  return (
    <Box
      component="ul"
      role="list"
      aria-label={label}
      display="flex"
      m={0}
      p={0}
      mb="xs"
      style={{ listStyle: "none", flexWrap: "wrap", gap: "4px 16px" }}
    >
      {entries.map((entry) => (
        <Box component="li" key={entry.key} display="flex" style={{ alignItems: "center", gap: 6 }}>
          {entry.glyph}
          <Text size="xs">{entry.text}</Text>
        </Box>
      ))}
    </Box>
  );
}
