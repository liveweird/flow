import type { ServiceToken } from "../api/serviceTokens";
import { formatDuration } from "./telemetryFormat";

/** Mirrors the server's `MAX_SERVICE_TOKEN_NAME_LENGTH` (`integration/ServiceToken.kt`). */
export const MAX_SERVICE_TOKEN_NAME = 100;

const DAY_MS = 86_400_000;

/** The expiry choices of the create form: a fixed-24h-day span from now, or never. */
export const EXPIRY_CHOICES = ["never", "90d", "1y", "2y"] as const;
export type ExpiryChoice = (typeof EXPIRY_CHOICES)[number];

const EXPIRY_DAYS: Record<Exclude<ExpiryChoice, "never">, number> = { "90d": 90, "1y": 365, "2y": 730 };

/** The request's `expiresAt` (epoch millis) for a choice, or `undefined` = never expires. */
export function expiresAtFor(choice: ExpiryChoice, nowMs: number): number | undefined {
  return choice === "never" ? undefined : nowMs + EXPIRY_DAYS[choice] * DAY_MS;
}

export type ServiceTokenStatus = "active" | "expired" | "revoked";

/**
 * A token's status: the server's `revoked` flag wins; an unrevoked token whose `expiresAt` has passed is
 * "expired" (the API never says so itself, and an expired token keeps its name until it is revoked).
 */
export function serviceTokenStatus(token: Pick<ServiceToken, "revoked" | "expiresAt">, nowMs: number): ServiceTokenStatus {
  if (token.revoked) return "revoked";
  return token.expiresAt != null && token.expiresAt <= nowMs ? "expired" : "active";
}

/** The status badge colour: the app-wide vocabulary — teal success, orange a soft finding, gray neutral. */
export const SERVICE_TOKEN_STATUS_COLOR: Record<ServiceTokenStatus, "teal" | "orange" | "gray"> = {
  active: "teal",
  expired: "orange",
  revoked: "gray",
};

/** "Last used" as an age: the elapsed time in the telemetry panel's language-neutral units, or `null` under a minute (the caller says "just now"). */
export function lastUsedAge(lastUsedAt: number, nowMs: number): string | null {
  const elapsed = Math.max(0, nowMs - lastUsedAt);
  return elapsed < 60_000 ? null : formatDuration(elapsed);
}
