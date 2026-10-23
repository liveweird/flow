import { useTranslation } from "react-i18next";
import { loadErrorMessage } from "../utils/saveError";
import FormErrorAlert from "./FormErrorAlert";

/**
 * The inline load-failure alert every list, card and tile shares: the status-mapped
 * `loadErrorMessage` of the query error (never the raw `error.message`), red and light, optionally
 * titled. Mantine's `Alert` is already `role="alert"`.
 */
export default function ErrorAlert({ error, title }: { error: unknown; title?: string }) {
  const { t } = useTranslation();
  return (
    <FormErrorAlert title={title}>
      {loadErrorMessage(error, t)}
    </FormErrorAlert>
  );
}
