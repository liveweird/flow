import { describe, expect, test } from "vitest";
import { formatBytes, formatCount, formatDuration, formatPercent, formatRatio, memoryLevel } from "./telemetryFormat";

describe("telemetryFormat", () => {
  test("bytes climb B, KB, MB, GB in binary steps and drop trailing zeros", () => {
    expect(formatBytes(null)).toBe("—");
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1023)).toBe("1023 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(768 * 1024 * 1024)).toBe("768 MB");
    expect(formatBytes(1_610_612_736)).toBe("1.5 GB");
    expect(formatBytes(1_073_741_824 * 12.345)).toBe("12.35 GB");
  });

  test("durations climb ms, s, min, h, d, with a decimal only where it informs", () => {
    expect(formatDuration(undefined)).toBe("—");
    expect(formatDuration(4.25)).toBe("4.3 ms");
    expect(formatDuration(480)).toBe("480 ms");
    expect(formatDuration(1500)).toBe("1.5 s");
    expect(formatDuration(90_000)).toBe("1.5 min");
    expect(formatDuration(5_400_000)).toBe("1.5 h");
    expect(formatDuration(3 * 86_400_000)).toBe("3 d");
  });

  test("percentages, ratios and counts", () => {
    expect(formatPercent(null)).toBe("—");
    expect(formatPercent(83.34)).toBe("83.3%");
    expect(formatRatio(0.123)).toBe("12.3%");
    expect(formatRatio(null)).toBe("—");
    expect(formatCount(null)).toBe("—");
    expect(formatCount(12)).toBe("12");
    expect(formatCount(64_000)).toBe("64 000");
    expect(formatCount(1_234_567.4)).toBe("1 234 567");
    expect(formatCount(-1500)).toBe("-1 500");
  });

  test("memory levels start at 80 % (high) and 90 % (critical)", () => {
    expect(memoryLevel(null)).toBeNull();
    expect(memoryLevel(79.9)).toBeNull();
    expect(memoryLevel(80)).toBe("high");
    expect(memoryLevel(89.9)).toBe("high");
    expect(memoryLevel(90)).toBe("critical");
    expect(memoryLevel(120)).toBe("critical");
  });
});
