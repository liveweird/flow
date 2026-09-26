import { type ReactNode } from "react";
import { Alert, Button, Group, Modal, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DeleteConfirm } from "../hooks/useDeleteConfirm";
import { saveErrorMessage } from "../utils/saveError";

/** The shared destructive-action modal driven by useDeleteConfirm; errors render inline. */
export default function ConfirmDeleteModal<T>({
  confirm,
  title,
  errorTitle,
  body,
  errorMessage,
}: {
  confirm: DeleteConfirm<T>;
  title: string;
  errorTitle: string;
  body: (target: T) => ReactNode;
  errorMessage?: (error: unknown) => string;
}) {
  const { t } = useTranslation();
  const { target, opened, cancelDelete, confirmDelete, mutation } = confirm;
  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened={opened} onClose={cancelDelete} title={title} centered>
      <Stack gap="md">
        {target && <Text>{body(target)}</Text>}
        {mutation.isError && (
          <Alert color="red" variant="light" title={errorTitle}>
            {errorMessage
              ? errorMessage(mutation.error)
              : saveErrorMessage(mutation.error, t, {
                  failedStatus: "common.error.actionFailedStatus",
                  failed: "common.error.actionFailed",
                })}
          </Alert>
        )}
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={cancelDelete} disabled={mutation.isPending}>
            {t("common.action.cancel")}
          </Button>
          <Button color="red" onClick={confirmDelete} loading={mutation.isPending}>
            {t("common.action.delete")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
