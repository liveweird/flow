import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Loader, Modal, Select, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { createTeamJiraMembership, listJiraUsers } from "../api/metrics";
import { isValidIsoDate, isoDateToEpochMillis } from "../utils/isoDate";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import RegistryEditorActions from "./RegistryEditorActions";

type JiraMemberFormValues = {
  accountId: string | null;
  validFrom: string;
  validTo: string;
};

const EMPTY_FORM: JiraMemberFormValues = { accountId: null, validFrom: "", validTo: "" };

/**
 * "Add Jira member" (D1's dated team membership, ADMIN only): a searchable person picker over
 * `GET /api/v1/jira-users?scope=SITE` (server-side substring search, debounced — a brand-new team
 * member may not yet be UNIT-relevant) plus a required valid-from date and an optional valid-to
 * date. An overlapping interval for the picked account is a `409`, shown inline (never a toast).
 */
export default function JiraMemberModal({
  teamId,
  excludeAccountIds,
  onClose,
  onCreated,
}: {
  teamId: number;
  /** Accounts already CURRENTLY on this team — excluded from the picker. */
  excludeAccountIds: Set<string>;
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [debounced] = useDebouncedValue(search.trim(), 300);
  const settling = search.trim() !== debounced;

  const people = useQuery({
    queryKey: ["jira-users", "picker", "SITE", debounced],
    queryFn: () => listJiraUsers({ page: 1, pageSize: 20, sort: "displayName", q: debounced || undefined, scope: "SITE" }),
  });
  const options = (people.data?.items ?? [])
    .filter((person) => !excludeAccountIds.has(person.accountId))
    .map((person) => ({ value: person.accountId, label: `${person.displayName} (${person.accountId})` }));

  const form = useForm<JiraMemberFormValues>({
    initialValues: EMPTY_FORM,
    validate: {
      accountId: (value) => (value ? null : t("metrics.teamMembers.validation.accountIdRequired")),
      validFrom: (value) => (isValidIsoDate(value) ? null : t("metrics.teamMembers.validation.validFromInvalid")),
      validTo: (value, values) => {
        if (value === "") return null;
        if (!isValidIsoDate(value)) return t("metrics.teamMembers.validation.validToInvalid");
        return isValidIsoDate(values.validFrom) && value > values.validFrom
          ? null
          : t("metrics.teamMembers.validation.validToBeforeFrom");
      },
    },
  });

  async function save(values: JiraMemberFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await createTeamJiraMembership(teamId, {
        accountId: values.accountId as string,
        validFrom: isoDateToEpochMillis(values.validFrom),
        validTo: values.validTo === "" ? undefined : isoDateToEpochMillis(values.validTo),
      });
      showSuccessToast(t("metrics.teamMembers.toast.added"));
      await onCreated();
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          conflict: "metrics.teamMembers.addOverlap",
          notFound: "metrics.teamMembers.addAccountUnknown",
          invalid: "metrics.teamMembers.addAccountUnknown",
          failedStatus: "common.error.saveFailedStatus",
          failed: "common.error.saveFailedNetwork",
        }),
      );
      setSubmitting(false);
    }
  }

  return (
    <Modal
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      opened
      onClose={onClose}
      title={t("metrics.teamMembers.addTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <Select
            label={t("metrics.teamMembers.field.person")}
            placeholder={t("metrics.teamMembers.pickPerson")}
            data={options}
            searchable
            searchValue={search}
            onSearchChange={setSearch}
            // "No matching people" states a COMPLETED search for the CURRENT term: never while the
            // query is pending (options are empty), while the typed term is still inside the
            // debounce window (`settling`), or after a failed load (the Alert says so).
            nothingFoundMessage={
              people.isLoading || people.isError || settling ? undefined : t("metrics.teamMembers.noMatchingPeople")
            }
            aria-busy={people.isLoading}
            rightSection={
              people.isLoading ? <Loader size="xs" role="status" aria-label={t("metrics.teamMembers.loadingPeople")} /> : undefined
            }
            {...form.getInputProps("accountId")}
          />
          {people.isError && (
            <Alert color="red" variant="light" role="alert">
              {loadErrorMessage(people.error, t)}
            </Alert>
          )}
          <TextInput
            label={t("metrics.teamMembers.field.validFrom")}
            placeholder="YYYY-MM-DD"
            {...form.getInputProps("validFrom")}
          />
          <TextInput
            label={t("metrics.teamMembers.field.validTo")}
            description={t("metrics.teamMembers.field.validToHint")}
            placeholder="YYYY-MM-DD"
            {...form.getInputProps("validTo")}
          />
          <RegistryEditorActions error={error} submitting={submitting} isEdit={false} onClose={onClose} gap="sm" />
        </Stack>
      </form>
    </Modal>
  );
}
