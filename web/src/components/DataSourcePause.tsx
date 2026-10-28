import type { TFunction } from "i18next";
import { Alert, Badge } from "@mantine/core";
import { IconPlayerPause } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { DataSourceResponse } from "../api/dataSources";
import { formatEpochMillis } from "../utils/dataSourceState";

type Status = DataSourceResponse["status"];

/** The reason codes the server writes (`pauseReason`); anything else shows the generic wording. */
function reasonText(status: Status, t: TFunction): string {
  if (status.pauseReason === "LIMIT_EXCEEDED") return t("dataSources.paused.reason.LIMIT_EXCEEDED");
  if (status.pauseReason === "TIME_EXCEEDED") return t("dataSources.paused.reason.TIME_EXCEEDED");
  return t("dataSources.paused.reason.OTHER");
}

/**
 * The orange "Paused" badge beside a data source's state (list and details): scheduled syncing stopped after repeated
 * ceiling trips without progress (`status.pausedAt`). Orange is the app's attention colour, not the blocking red.
 */
export function DataSourcePausedBadge({ status }: { status: Status }) {
  const { t } = useTranslation();
  if (status.pausedAt == null) return null;
  return (
    <Badge color="orange" variant="light" title={reasonText(status, t)}>
      {t("dataSources.paused.badge")}
    </Badge>
  );
}

/**
 * The details page's explanation of a pause: since when, why, how many runs in a row, and how to resume ("Sync now" or
 * saving the connection). Nothing is rendered for a connection that is not paused.
 */
export function DataSourcePausedAlert({ status }: { status: Status }) {
  const { t } = useTranslation();
  if (status.pausedAt == null) return null;
  return (
    <Alert color="orange" icon={<IconPlayerPause size={18} />} title={t("dataSources.paused.title")}>
      {t("dataSources.paused.body", {
        since: formatEpochMillis(status.pausedAt, t),
        reason: reasonText(status, t),
        count: status.limitTrips ?? 0,
      })}
    </Alert>
  );
}
