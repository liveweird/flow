import { useState } from "react";
import { ActionIcon, Button, Code, CopyButton, Group } from "@mantine/core";
import { IconEye, IconEyeOff } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";

/** The one-time password display: masked by default (shoulder-surfing), eye toggle, copy. */
export default function RevealablePassword({
  password,
  copyLabel,
}: {
  password: string;
  copyLabel: string;
}) {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  return (
    <Group gap="xs" wrap="nowrap">
      <Code fz="md" px="sm" py={6} style={{ flex: 1 }}>
        {visible ? password : "*".repeat(password.length)}
      </Code>
      <ActionIcon
        variant="subtle"
        color="gray"
        onClick={() => setVisible((v) => !v)}
        aria-pressed={visible}
        aria-label={visible ? t("common.action.hidePassword") : t("common.action.showPassword")}
      >
        {visible ? <IconEyeOff size={16} /> : <IconEye size={16} />}
      </ActionIcon>
      <CopyButton value={password}>
        {({ copied, copy }) => (
          <Button size="sm" variant="light" onClick={copy}>
            {copied ? t("users.passwordCopied") : copyLabel}
          </Button>
        )}
      </CopyButton>
    </Group>
  );
}
