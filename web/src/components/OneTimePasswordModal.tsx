import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Modal, Stack, Text } from "@mantine/core";
import RevealablePassword from "./RevealablePassword";

/**
 * One-time password reveal shared by the create-user and reset-password flows. Deliberate
 * close only (no click-outside / Escape) so the password can't be lost by accident — after
 * closing it is unrecoverable by design (the server stores only the bcrypt hash).
 */
export default function OneTimePasswordModal({
  reveal,
  title,
  onClose,
  secondaryAction,
}: {
  /** The revealed credentials, or null while the modal is closed. */
  reveal: { email: string; password: string } | null;
  title: string;
  onClose: () => void;
  /** Optional extra button rendered opposite Close (e.g. the onboarding-mail draft). */
  secondaryAction?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Modal
      opened={reveal !== null}
      onClose={onClose}
      title={title}
      centered
      closeOnClickOutside={false}
      closeOnEscape={false}
      // The footer Close is the ONE way out (a deliberate act); the header X would be a second,
      // accidental one — and unlabeled, the axe sweep's button-name finding.
      withCloseButton={false}
    >
      {reveal && (
        <Stack gap="md">
          <Text>{t("users.generatedPasswordNote", { email: reveal.email })}</Text>
          <RevealablePassword password={reveal.password} copyLabel={t("users.copyPassword")} />
          <Group justify={secondaryAction ? "space-between" : "flex-end"}>
            {secondaryAction}
            <Button onClick={onClose}>{t("common.action.close")}</Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
