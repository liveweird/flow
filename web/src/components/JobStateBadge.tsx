import { Badge } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { SyncJobStatus } from "../api/dataSources";

/**
 * The sync-job status badge (plan §10/§11): teal SUCCEEDED (the app's success colour), red
 * FAILED (blocking), gray PENDING/CANCELLED (neutral) and blue RUNNING — blue is the colour
 * vocabulary's "in progress" (`web/CLAUDE.md`, "The colour vocabulary is app-wide"), so a running
 * job reads as active rather than merely neutral. Shared by the current-job card and the jobs
 * history table.
 */
export default function JobStateBadge({ status }: { status: SyncJobStatus }) {
  const { t } = useTranslation();
  const color = status === "SUCCEEDED" ? "teal" : status === "FAILED" ? "red" : status === "RUNNING" ? "blue" : "gray";
  return (
    <Badge color={color} variant="light">
      {t(`dataSources.job.status.${status}`)}
    </Badge>
  );
}
