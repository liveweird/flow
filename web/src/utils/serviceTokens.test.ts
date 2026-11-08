import { describe, expect, test } from "vitest";
import { expiresAtFor, lastUsedAge, serviceTokenStatus } from "./serviceTokens";

const NOW = 1_800_000_000_000;
const DAY = 86_400_000;

describe("expiresAtFor", () => {
  test("never sends no expiry; the others are whole fixed days from now", () => {
    expect(expiresAtFor("never", NOW)).toBeUndefined();
    expect(expiresAtFor("90d", NOW)).toBe(NOW + 90 * DAY);
    expect(expiresAtFor("1y", NOW)).toBe(NOW + 365 * DAY);
    expect(expiresAtFor("2y", NOW)).toBe(NOW + 730 * DAY);
  });
});

describe("serviceTokenStatus", () => {
  test("revoked wins; an unrevoked token is expired from its expiry instant on; no expiry is always active", () => {
    expect(serviceTokenStatus({ revoked: true, expiresAt: NOW + DAY }, NOW)).toBe("revoked");
    expect(serviceTokenStatus({ revoked: true, expiresAt: NOW - DAY }, NOW)).toBe("revoked");
    expect(serviceTokenStatus({ revoked: false, expiresAt: NOW - 1 }, NOW)).toBe("expired");
    expect(serviceTokenStatus({ revoked: false, expiresAt: NOW }, NOW)).toBe("expired");
    expect(serviceTokenStatus({ revoked: false, expiresAt: NOW + 1 }, NOW)).toBe("active");
    expect(serviceTokenStatus({ revoked: false, expiresAt: null }, NOW)).toBe("active");
    expect(serviceTokenStatus({ revoked: false }, NOW)).toBe("active");
  });
});

describe("lastUsedAge", () => {
  test("under a minute (or a clock-skewed future stamp) is null; later is the telemetry units", () => {
    expect(lastUsedAge(NOW - 59_999, NOW)).toBeNull();
    expect(lastUsedAge(NOW + 5_000, NOW)).toBeNull();
    expect(lastUsedAge(NOW - 60_000, NOW)).toBe("1 min");
    expect(lastUsedAge(NOW - 3 * 3_600_000, NOW)).toBe("3 h");
    expect(lastUsedAge(NOW - 5 * DAY, NOW)).toBe("5 d");
  });
});
