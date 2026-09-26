import type { ReactNode } from "react";
import { Alert, Button, Group, type GroupProps } from "@mantine/core";
import { useTranslation } from "react-i18next";

/** Shared save error and cancel/submit footer for registry editors. */
export default function RegistryEditorActions({
  error,
  submitting,
  isEdit,
  onClose,
  gap,
}: {
  error: ReactNode;
  submitting: boolean;
  isEdit: boolean;
  onClose: () => void;
  gap?: GroupProps["gap"];
}) {
  const { t } = useTranslation();

  return (
    <>
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}
      <Group justify="flex-end" gap={gap}>
        <Button type="button" variant="default" onClick={onClose} disabled={submitting}>
          {t("common.action.cancel")}
        </Button>
        <Button type="submit" loading={submitting}>
          {isEdit ? t("common.action.save") : t("common.action.create")}
        </Button>
      </Group>
    </>
  );
}
