import { useTranslation } from "react-i18next";
import { Alert } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";

/**
 * At USER level an epic read is always empty by design — epics carry no user (measures.md) — so a
 * generic "No data" or a block of zeros would read as "this person's epics were fine". One line
 * says what is actually true instead.
 */
export default function EpicsPerPersonNote() {
  const { t } = useTranslation();
  return (
    <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
      {t("reports.epicsNotPerPerson")}
    </Alert>
  );
}
