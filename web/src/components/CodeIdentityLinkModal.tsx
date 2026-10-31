import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Loader, Modal, Select, Stack } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { decideCodeIdentity, type CodeIdentityResponse } from "../api/codeIdentities";
import { listJiraUsers } from "../api/metrics";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";

const PICKER_PAGE_SIZE = 20;

/**
 * "Link to a Jira person" for one GitLab identity (ADMIN only): a searchable person picker over
 * `GET /api/v1/jira-users?scope=SITE` (the whole site directory, server-side search, debounced — a brand-new person may not yet
 * be relevant to the unit), saved as a `LINKED` decision. The current link (or the proposal) is preselected. A `400`/`404`/`409`
 * is shown inline in the modal, never as a toast.
 */
export default function CodeIdentityLinkModal({
  dataSourceId,
  identity,
  onClose,
  onLinked,
}: {
  dataSourceId: number;
  identity: CodeIdentityResponse;
  onClose: () => void;
  onLinked: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const current = identity.decision?.decision === "LINKED" ? identity.decision : identity.proposal?.decision === "LINKED" ? identity.proposal : null;
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  // The picked person, kept apart from the search results: a new term replaces the options, and the selection must keep its label.
  const [picked, setPicked] = useState<{ value: string; label: string } | null>(
    current?.jiraAccountId ? { value: current.jiraAccountId, label: current.jiraDisplayName ?? current.jiraAccountId } : null,
  );
  // Choosing an option makes Mantine put its label into the search box — that is not a search term.
  const term = picked !== null && search === picked.label ? "" : search.trim();
  const [debounced] = useDebouncedValue(term, 300);
  const settling = term !== debounced;

  const people = useQuery({
    queryKey: ["jira-users", "picker", "SITE", debounced],
    queryFn: () => listJiraUsers({ page: 1, pageSize: PICKER_PAGE_SIZE, sort: "displayName", q: debounced || undefined, scope: "SITE" }),
    placeholderData: keepPreviousData,
  });
  const loading = people.isLoading || people.isPlaceholderData;
  const options = (people.data?.items ?? []).map((person) => ({ value: person.accountId, label: `${person.displayName} (${person.accountId})` }));
  if (picked !== null && !options.some((option) => option.value === picked.value)) options.unshift(picked);

  async function save() {
    if (picked === null) return;
    setError(null);
    setSubmitting(true);
    try {
      await decideCodeIdentity(dataSourceId, identity.identityKey, { decision: "LINKED", jiraAccountId: picked.value });
      showSuccessToast(t("codeIdentities.toast.linked"));
      await onLinked();
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "codeIdentities.error.notFound",
          invalid: "codeIdentities.error.invalid",
          conflict: "codeIdentities.error.conflict",
          failedStatus: "codeIdentities.error.failedStatus",
          failed: "codeIdentities.error.failed",
        }),
      );
      setSubmitting(false);
    }
  }

  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened onClose={() => { if (!submitting) onClose(); }} title={t("codeIdentities.modal.title")} centered>
      <Stack>
        <Select
          label={t("codeIdentities.modal.person")}
          description={t("codeIdentities.modal.hint")}
          placeholder={t("codeIdentities.modal.pick")}
          data={options}
          value={picked?.value ?? null}
          searchable
          searchValue={search}
          onSearchChange={setSearch}
          // "No matching people" states a COMPLETED search for the CURRENT term: never while loading, settling or after a failed load.
          nothingFoundMessage={loading || people.isError || settling ? undefined : t("codeIdentities.modal.nothingFound")}
          aria-busy={loading}
          rightSection={loading ? <Loader size="xs" role="status" aria-label={t("codeIdentities.modal.loading")} /> : undefined}
          allowDeselect={false}
          onChange={(_value, option) => setPicked(option ? { value: option.value, label: option.label } : null)}
        />
        {people.isError && <ErrorAlert error={people.error} />}
        {error && <FormErrorAlert>{error}</FormErrorAlert>}
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onClose} disabled={submitting}>
            {t("common.action.cancel")}
          </Button>
          <Button onClick={() => void save()} loading={submitting} disabled={picked === null}>
            {t("common.action.save")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
