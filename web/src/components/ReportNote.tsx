import { useTranslation } from "react-i18next";
import { Alert } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";

/** How the server's "not derived yet" note begins (`SnapshotSupport.kt`, sent alone, never joined). */
const NOT_DERIVED_PREFIX = "Not derived yet";

/**
 * The server's own explanation of an empty or partial answer (`note`: not derived yet, the USER
 * level has no daily figures, a connection left out of the cut-off, …). It is plain prose written
 * by the server, shown as written under a translated title — a gray fact about the selection,
 * never a warning. The one note the SPA already says in the viewer's language — nothing has been
 * derived yet, which the meta line above the report states — is not repeated in English.
 */
export default function ReportNote({ note, derivedAt }: { note: string; derivedAt: number | null }) {
  const { t } = useTranslation();
  if (derivedAt === null && note.startsWith(NOT_DERIVED_PREFIX)) return null;
  return (
    <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} title={t("reports.note.title")} role="note">
      {note}
    </Alert>
  );
}
