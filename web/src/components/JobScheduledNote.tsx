import { Text } from "@mantine/core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { SyncJobResponse } from "../api/dataSources";
import { formatEpochMillis } from "../utils/dataSourceState";

/** `setTimeout` overflows (fires at once) past this; a longer wait just re-checks at the cap. */
const MAX_TIMER_MS = 2 ** 31 - 1;

/**
 * "Waiting for further changes — starts after HH:mm": the note under a PENDING job's badge while the server holds it back
 * (`notBefore` still ahead — the re-derive a configuration edit caused waits out a quiet period, so a burst of edits becomes
 * one run). It is neutral dimmed text next to the gray Pending badge: a scheduled job is neither stuck nor failed. Renders
 * nothing for every other job — no `notBefore`, past PENDING, or a `notBefore` that has passed (a job a shutdown released back
 * to PENDING is simply due), which a timer notices without waiting for the next poll.
 */
export default function JobScheduledNote({ job }: { job: Pick<SyncJobResponse, "status" | "notBefore"> }) {
  const { t } = useTranslation();
  const notBefore = job.status === "PENDING" ? (job.notBefore ?? null) : null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (notBefore === null) return;
    const timer = setTimeout(() => setNow(Date.now()), Math.min(Math.max(notBefore - Date.now(), 0), MAX_TIMER_MS));
    return () => clearTimeout(timer);
  }, [notBefore]);
  if (notBefore === null || notBefore <= now) return null;
  return (
    <Text size="xs" c="dimmed">
      {t("dataSources.job.scheduled", { time: formatEpochMillis(notBefore, t) })}
    </Text>
  );
}
