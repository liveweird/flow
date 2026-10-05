import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Loader, Modal, Select, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { createTeamJiraMembership, listJiraUsers } from "../api/metrics";
import { isValidIsoDate, isoDateToEpochMillisInZone } from "../utils/isoDate";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import RegistryEditorActions from "./RegistryEditorActions";
import ErrorAlert from "./ErrorAlert";

type JiraMemberFormValues = {
  accountId: string | null;
  validFrom: string;
  validTo: string;
};

const PICKER_PAGE_SIZE = 20;

const EMPTY_FORM: JiraMemberFormValues = { accountId: null, validFrom: "", validTo: "" };

/**
 * "Add Jira member" (D1's dated team membership, ADMIN only): a searchable person picker over
 * `GET /api/v1/jira-users?scope=SITE` (server-side search by name or account id, debounced — a
 * brand-new team member may not yet be UNIT-relevant, and the directory is never cut to a fixed
 * first page: anyone is findable by typing) plus a required valid-from date and an optional valid-to
 * date, both calendar days in the configured metrics zone (stored as that zone's midnights). An overlapping interval for the picked account is a `409`, shown inline (never a toast).
 */
export default function JiraMemberModal({
  teamId,
  timeZone,
  excludeAccountIds,
  onClose,
  onCreated,
}: {
  teamId: number;
  /** The configured metrics zone the picked days are read in; `null` while it loads (submit waits). */
  timeZone: string | null;
  /** Accounts already CURRENTLY on this team — excluded from the picker. */
  excludeAccountIds: Set<string>;
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  // The picked person, kept apart from the search results: a new term replaces the options, and
  // the selection must keep its label (Mantine drops a value that is no longer in `data`).
  const [picked, setPicked] = useState<{ value: string; label: string } | null>(null);
  // Choosing an option makes Mantine put its label into the search box — that is not a search term.
  const term = picked !== null && search === picked.label ? "" : search.trim();
  const [debounced] = useDebouncedValue(term, 300);
  const settling = term !== debounced;

  const people = useQuery({
    queryKey: ["jira-users", "picker", "SITE", debounced],
    queryFn: () => listJiraUsers({ page: 1, pageSize: PICKER_PAGE_SIZE, sort: "displayName", q: debounced || undefined, scope: "SITE" }),
    placeholderData: keepPreviousData,
  });
  // `isPlaceholderData`: the previous term's rows stay on screen while the new term loads.
  const loading = people.isLoading || people.isPlaceholderData;
  const options = (people.data?.items ?? [])
    .filter((person) => !excludeAccountIds.has(person.accountId))
    .map((person) => ({ value: person.accountId, label: `${person.displayName} (${person.accountId})` }));
  if (picked !== null && !options.some((option) => option.value === picked.value)) options.unshift(picked);
  // Counted AFTER the exclusion filter (the picked row, re-added above, is not a result), so the
  // "showing the first N" hint never contradicts an empty list's "No matching people".
  const shown = (people.data?.items ?? []).filter((person) => !excludeAccountIds.has(person.accountId)).length;
  const total = people.data?.total ?? 0;

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

  const personInput = form.getInputProps("accountId");

  async function save(values: JiraMemberFormValues) {
    if (timeZone === null) return;
    setError(null);
    setSubmitting(true);
    try {
      await createTeamJiraMembership(teamId, {
        accountId: values.accountId as string,
        validFrom: isoDateToEpochMillisInZone(values.validFrom, timeZone),
        validTo: values.validTo === "" ? undefined : isoDateToEpochMillisInZone(values.validTo, timeZone),
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
              loading || people.isError || settling ? undefined : t("metrics.teamMembers.noMatchingPeople")
            }
            aria-busy={loading}
            rightSection={loading ? <Loader size="xs" role="status" aria-label={t("metrics.teamMembers.loadingPeople")} /> : undefined}
            {...personInput}
            onChange={(value, option) => {
              personInput.onChange(value);
              setPicked(option ? { value: option.value, label: option.label } : null);
            }}
          />
          {people.isError && <ErrorAlert error={people.error} />}
          {!loading && !settling && total > shown && shown > 0 && (
            <Text size="xs" c="dimmed">
              {t("metrics.teamMembers.peopleCut", { shown, total })}
            </Text>
          )}
          <TextInput
            label={t("metrics.teamMembers.field.validFrom")}
            placeholder={t("common.dateFormatHint")}
            {...form.getInputProps("validFrom")}
          />
          <TextInput
            label={t("metrics.teamMembers.field.validTo")}
            description={t("metrics.teamMembers.field.validToHint")}
            placeholder={t("common.dateFormatHint")}
            {...form.getInputProps("validTo")}
          />
          <RegistryEditorActions error={error} submitting={submitting} isEdit={false} onClose={onClose} gap="sm" submitDisabled={timeZone === null} />
        </Stack>
      </form>
    </Modal>
  );
}
