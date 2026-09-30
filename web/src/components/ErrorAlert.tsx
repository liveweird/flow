import { useTranslation } from "react-i18next";
import { Alert } from "@mantine/core";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The inline load-failure alert every list, card and tile shares: the status-mapped
 * `loadErrorMessage` of the query error (never the raw `error.message`), red and light, optionally
 * titled. Mantine's `Alert` is already `role="alert"`.
 */
export default function ErrorAlert({ error, title }: { error: unknown; title?: string }) {
  const { t } = useTranslation();
  return (
    <Alert color="red" variant="light" title={title}>
      {loadErrorMessage(error, t)}
    </Alert>
  );
}
