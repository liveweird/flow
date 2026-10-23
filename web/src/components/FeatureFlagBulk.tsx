import { Alert, Button, Stack, Text } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { FeatureFlagBulk } from "../hooks/useFeatureFlagBulk";
import ConfirmActionModal from "./ConfirmActionModal";

/** The two header buttons of the /feature-flags bulk run; `onStart` clears the page's stale notices first. */
export function FeatureFlagBulkButtons({
  state: { bulk },
  disabled,
  onStart,
}: {
  state: FeatureFlagBulk;
  disabled: boolean;
  onStart: (targetEnabled: boolean) => void;
}) {
  const { t } = useTranslation();
  return (
    <>
      <Button
        variant="light"
        loading={bulk.preparing === true}
        disabled={bulk.preparing !== null || disabled}
        onClick={() => onStart(true)}
      >
        {t("flowUsers.featureFlags.bulkEnable")}
      </Button>
      <Button
        variant="light"
        color="red"
        loading={bulk.preparing === false}
        disabled={bulk.preparing !== null || disabled}
        onClick={() => onStart(false)}
      >
        {t("flowUsers.featureFlags.bulkDisable")}
      </Button>
    </>
  );
}

/**
 * Everything the bulk run says back: the neutral "nothing to change" note (never a toast), the
 * partial-failure alert naming every row that failed (their switches kept their old state) with
 * a retry over exactly those rows, and the count-stating confirm modal.
 */
export function FeatureFlagBulkFeedback({
  state: { bulk, noChange },
  featureLabel,
}: {
  state: FeatureFlagBulk;
  featureLabel: string;
}) {
  const { t } = useTranslation();
  return (
    <>
      {noChange && (
        <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
          {t("flowUsers.featureFlags.bulkNoChange")}
        </Alert>
      )}
      {bulk.failed && (
        <Alert
          color="red"
          variant="light"
          title={t("flowUsers.featureFlags.bulkFailed", { count: bulk.failed.rows.length })}
        >
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{bulk.failed.rows.map((row) => row.name).join(", ")}</Text>
            <Button
              size="xs"
              color="red"
              variant="light"
              loading={bulk.running}
              onClick={() => void bulk.retry()}
            >
              {t("flowUsers.featureFlags.retryFailed")}
            </Button>
          </Stack>
        </Alert>
      )}
      <ConfirmActionModal
        opened={bulk.pending != null}
        onClose={bulk.cancel}
        title={t("flowUsers.featureFlags.bulkTitle")}
        message={t(
          bulk.pending?.target
            ? "flowUsers.featureFlags.bulkConfirmEnable"
            : "flowUsers.featureFlags.bulkConfirmDisable",
          { count: bulk.pending?.rows.length ?? 0, feature: featureLabel },
        )}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={
          bulk.pending?.target
            ? t("flowUsers.featureFlags.enableAction")
            : t("flowUsers.featureFlags.disableAction")
        }
        onConfirm={() => void bulk.run()}
        loading={bulk.running}
        confirmColor={bulk.pending?.target ? "flow" : "red"}
      />
    </>
  );
}
