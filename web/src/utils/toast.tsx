import { notifications } from "@mantine/notifications";
import { IconCheck } from "@tabler/icons-react";

/**
 * The one success-toast entry point (host: `<Notifications />` in main.tsx). Success only —
 * errors stay inline (red Alerts next to the form/list). Teal is the semantic-success color
 * (stock green would impersonate a brand color).
 *
 * Content rule: pass fixed vocabulary (`t("<area>.toast.*")`) only — NEVER user-entered
 * names/values.
 */
export function showSuccessToast(message: string) {
  notifications.show({ message, color: "teal", icon: <IconCheck size={16} /> });
}
