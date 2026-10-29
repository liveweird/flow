import type { TFunction } from "i18next";
import { Alert, Badge, Stack } from "@mantine/core";
import { IconPlayerPause } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { DataSourceResponse } from "../api/dataSources";
import { formatEpochMillis } from "../utils/dataSourceState";

type Status = DataSourceResponse["status"];
/** Which scheduled job a pause stops: the two pauses are independent (SYNC's `pausedAt…`, RECONCILE's `reconcilePausedAt…`). */
export type PausedJob = "SYNC" | "RECONCILE";

/** The pause of one job as the status carries it: its start, reason code and consecutive-trip count. */
function pauseOf(status: Status, job: PausedJob) {
  return job === "SYNC"
    ? { at: status.pausedAt, reason: status.pauseReason, trips: status.limitTrips }
    : { at: status.reconcilePausedAt, reason: status.reconcilePauseReason, trips: status.reconcileLimitTrips };
}

/** The reason codes the server writes (`pauseReason`/`reconcilePauseReason`); anything else shows the generic wording. */
function reasonText(code: string | null | undefined, t: TFunction): string {
  if (code === "LIMIT_EXCEEDED") return t("dataSources.paused.reason.LIMIT_EXCEEDED");
  if (code === "TIME_EXCEEDED") return t("dataSources.paused.reason.TIME_EXCEEDED");
  return t("dataSources.paused.reason.OTHER");
}

/**
 * The orange "Paused" badge beside a data source's state (list and details): scheduled syncing stopped after repeated
 * ceiling trips without progress (`status.pausedAt`); a RECONCILE pause (`status.reconcilePausedAt`) gets its own
 * "Reconcile paused" badge, and both show when both are on. Orange is the app's attention colour, not the blocking red.
 */
export function DataSourcePausedBadge({ status }: { status: Status }) {
  const { t } = useTranslation();
  const sync = pauseOf(status, "SYNC");
  const reconcile = pauseOf(status, "RECONCILE");
  return (
    <>
      {sync.at != null && (
        <Badge color="orange" variant="light" title={reasonText(sync.reason, t)}>
          {t("dataSources.paused.badge")}
        </Badge>
      )}
      {reconcile.at != null && (
        <Badge color="orange" variant="light" title={reasonText(reconcile.reason, t)}>
          {t("dataSources.reconcilePaused.badge")}
        </Badge>
      )}
    </>
  );
}

function PauseAlert({ status, job }: { status: Status; job: PausedJob }) {
  const { t } = useTranslation();
  const { at, reason, trips } = pauseOf(status, job);
  if (at == null) return null;
  const ns = job === "SYNC" ? "dataSources.paused" : "dataSources.reconcilePaused";
  return (
    <Alert color="orange" icon={<IconPlayerPause size={18} />} title={t(`${ns}.title`)}>
      {t(`${ns}.body`, { since: formatEpochMillis(at, t), reason: reasonText(reason, t), count: trips ?? 0 })}
    </Alert>
  );
}

/**
 * The details page's explanation of each pause: since when, why, how many runs in a row, and how to resume (SYNC: "Sync
 * now" or saving the connection; RECONCILE: "Reconcile now" or saving, SYNC keeps running). One alert per active pause,
 * both when both are on; nothing is rendered for a connection that is not paused.
 */
export function DataSourcePausedAlert({ status }: { status: Status }) {
  if (status.pausedAt == null && status.reconcilePausedAt == null) return null;
  return (
    <Stack gap="xs">
      <PauseAlert status={status} job="SYNC" />
      <PauseAlert status={status} job="RECONCILE" />
    </Stack>
  );
}
