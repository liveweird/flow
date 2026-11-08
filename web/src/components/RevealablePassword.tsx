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
      {/* A generated secret is one unbreakable token with no word boundaries: `anywhere` lets a 52-character service token wrap
          inside a narrow panel instead of pushing the copy button out of it (the one place the house `break-word` rule is not enough). */}
      <Code fz="md" px="sm" py={6} style={{ flex: 1, minWidth: 0, overflowWrap: "anywhere", whiteSpace: "normal" }}>
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
            {copied ? t("flowUsers.passwordCopied") : copyLabel}
          </Button>
        )}
      </CopyButton>
    </Group>
  );
}
