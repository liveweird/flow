// Display formatting for the telemetry panel: units, deterministic and language-neutral (symbols, no locale APIs), so a
// figure reads the same in a test, in CI and on a Polish browser.

const DASH = "—";

const KB = 1024;
const MB = KB * 1024;
const GB = MB * 1024;
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** `value` rounded to `digits` decimals with trailing zeros dropped ("512", "66.7"). */
function trim(value: number, digits: number): string {
  return String(Number(value.toFixed(digits)));
}

/** Whole bytes as B / KB / MB / GB (binary multiples, the way a container limit is written: 768 MB is 768 MiB). */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return DASH;
  const abs = Math.abs(bytes);
  if (abs >= GB) return `${trim(bytes / GB, 2)} GB`;
  if (abs >= MB) return `${trim(bytes / MB, 1)} MB`;
  if (abs >= KB) return `${trim(bytes / KB, 1)} KB`;
  return `${trim(bytes, 0)} B`;
}

/** Milliseconds as ms / s / min / h / d, one step up at each unit boundary. Also an age. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return DASH;
  const abs = Math.abs(ms);
  if (abs < SECOND) return `${trim(ms, abs < 10 ? 1 : 0)} ms`;
  if (abs < MINUTE) return `${trim(ms / SECOND, 1)} s`;
  if (abs < HOUR) return `${trim(ms / MINUTE, 1)} min`;
  if (abs < 2 * DAY) return `${trim(ms / HOUR, 1)} h`;
  return `${trim(ms / DAY, 1)} d`;
}

/** A percentage that is already in percent (the server's `percentOfLimit`), one decimal. */
export function formatPercent(percent: number | null | undefined): string {
  return percent == null ? DASH : `${trim(percent, 1)}%`;
}

/** A 0..1 ratio as a percentage. */
export function formatRatio(ratio: number | null | undefined): string {
  return ratio == null ? DASH : formatPercent(ratio * 100);
}

/** A count with a no-break space between thousands ("64 000"), readable in both languages. */
export function formatCount(count: number | null | undefined): string {
  if (count == null) return DASH;
  const digits = String(Math.round(Math.abs(count)));
  let grouped = "";
  for (let i = 0; i < digits.length; i += 1) {
    if (i > 0 && (digits.length - i) % 3 === 0) grouped += "\u00a0";
    grouped += digits[i];
  }
  return count < 0 ? `-${grouped}` : grouped;
}

/** The two memory warnings: a container's working set at 80 % of its limit is high, at 90 % critical. */
export type MemoryLevel = "high" | "critical";

const MEMORY_HIGH_PERCENT = 80;
const MEMORY_CRITICAL_PERCENT = 90;

export function memoryLevel(percentOfLimit: number | null | undefined): MemoryLevel | null {
  if (percentOfLimit == null) return null;
  if (percentOfLimit >= MEMORY_CRITICAL_PERCENT) return "critical";
  if (percentOfLimit >= MEMORY_HIGH_PERCENT) return "high";
  return null;
}
