/** Man-days with at most two decimals and no trailing zeros ("12.5", "8", "0.33"). */
export function formatMd(value: number): string {
  return String(Math.round(value * 100) / 100);
}
