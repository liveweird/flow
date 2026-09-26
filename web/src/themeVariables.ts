import type { CSSVariablesResolver } from "@mantine/core";

/**
 * The colour tokens Flow owns outside Mantine's defaults (Lettuce's v3.3.0 pass, ported) — every value here
 * is chosen for WCAG AA (≥ 4.5:1) on the surface it sits on, in BOTH schemes, and
 * `theme.test.ts` guards the ratios. Mantine 9.5's stock light-variant ink in the LIGHT scheme
 * is the hue's 9-shade over the solid 1-shade tint (orange 3.6:1, green 3.8, teal 4.3, yellow
 * 2.7 — see `get-css-color-variables.mjs`), and its `dimmed` is gray-6 (3.3:1) — the reason
 * the axe colour-contrast rule used to be waived. In the DARK scheme Mantine paints the
 * 0-shade over a 50%-darkened 9-shade, which clears 8:1 for every hue, so only the light
 * inks are overridden below.
 *
 * Callers: never hand a shade-suffixed colour (`"orange.8"`) to a `variant="light"` Badge or
 * Alert — the tint comes from the hue, the ink comes from this map.
 */
export const LIGHT_TOKENS = {
  text: "#1f2328",
  dimmed: "#5f6b76",
  // A faint blue cast (not Lettuce's neutral #f8f9fb) — dimmed ink stays 5.0:1 on it.
  canvas: "#f5f7fb",
  // The quiet tint under filter panels (FilterPanelBody) — dimmed ink stays ≥ 4.5:1.
  surfaceTint: "#f7f8fb",
  border: "#dee2e6",
  borderStrong: "#ced4da",
  error: "#c92a2a",
  inkWarning: "#b23a0a",
  inkError: "#c92a2a",
  // The brand hue where it must be PERCEIVED against the canvas (nav marker, hover underline,
  // focus) — the light primary here, the 4-shade in the dark scheme (see theme.ts).
  accentInk: "#1971c2",
} as const;

export const DARK_TOKENS = {
  text: "#e6e6e6",
  dimmed: "#a3a3a3",
  canvas: "#1f1f1f",
  // dark-6 — NOT Mantine's default-hover (dark-5), where dimmed (#a3a3a3) is only 4.44:1.
  surfaceTint: "#2e2e2e",
  border: "#424242",
  borderStrong: "#696969",
  error: "#ff8787",
  inkWarning: "#ffc078",
  inkError: "#ff8787",
  accentInk: "#4dabf7",
} as const;

/** Light-scheme inks for `variant="light"` surfaces, per hue — each replaces the stock 9-shade
 *  ink on the hue's solid 1-shade tint. The dark scheme keeps Mantine's own values (the
 *  0-shade over a 50%-darkened 9-shade, ≥ 8:1 for every hue — guarded in theme.test.ts). */
export const LIGHT_VARIANT_INKS = {
  flow: "#1864ab",
  teal: "#087255",
  orange: "#b23a0a",
  red: "#b91c1c",
  yellow: "#8a4800",
  gray: "#495057",
  blue: "#1864ab",
  grape: "#862e9c",
  cyan: "#0b7285",
  indigo: "#364fc7",
  green: "#236b34",
  violet: "#5f3dc4",
  pink: "#a61e4d",
  lime: "#3f6a05",
} as const;

function schemeVariables(tokens: typeof LIGHT_TOKENS | typeof DARK_TOKENS): Record<string, string> {
  return {
    "--mantine-color-text": tokens.text,
    "--mantine-color-dimmed": tokens.dimmed,
    "--flow-canvas": tokens.canvas,
    "--flow-surface-tint": tokens.surfaceTint,
    "--mantine-color-default-border": tokens.border,
    "--flow-border-strong": tokens.borderStrong,
    "--mantine-color-error": tokens.error,
    "--flow-ink-warning": tokens.inkWarning,
    "--flow-ink-error": tokens.inkError,
    "--flow-accent-ink": tokens.accentInk,
  };
}

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    ...schemeVariables(LIGHT_TOKENS),
    ...Object.fromEntries(
      Object.entries(LIGHT_VARIANT_INKS).map(([hue, ink]) => [`--mantine-color-${hue}-light-color`, ink]),
    ),
  },
  dark: schemeVariables(DARK_TOKENS),
});
