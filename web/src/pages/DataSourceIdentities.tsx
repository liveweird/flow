import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams, useSearchParams } from "react-router-dom";
import { Button, Group, Stack, Table, Tabs, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconCheck, IconUsersGroup } from "@tabler/icons-react";
import {
  confirmCodeIdentityProposals,
  decideCodeIdentity,
  listCodeIdentities,
  removeCodeIdentityDecision,
  type CodeIdentityDecisionRequest,
  type CodeIdentityResponse,
  type CodeIdentityStatus,
} from "../api/codeIdentities";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import ClearableTextInput from "../components/ClearableTextInput";
import CodeIdentityLinkModal from "../components/CodeIdentityLinkModal";
import CodeIdentityRow from "../components/CodeIdentityRow";
import ConfirmActionModal from "../components/ConfirmActionModal";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EditPageLoadState from "../components/EditPageLoadState";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { identityName } from "../utils/codeIdentityLabels";
import { dataSourcePath } from "../utils/dataSourceLinks";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["displayName"] as const;
const SETTINGS_KEY = "dataSourceIdentities";
const STATUSES: readonly CodeIdentityStatus[] = ["PROPOSED", "UNMAPPED", "LINKED", "IGNORED_OR_BOT"];
const COLUMN_COUNT = 6;
/** The server caps `q` at 100 characters (a longer one is a 400). */
const MAX_QUERY_LENGTH = 100;
const ACTION_ERROR_KEYS = {
  notFound: "codeIdentities.error.notFound",
  invalid: "codeIdentities.error.invalid",
  conflict: "codeIdentities.error.conflict",
  failedStatus: "codeIdentities.error.failedStatus",
  failed: "codeIdentities.error.failed",
} as const;

type DecideVariables = {
  identity: CodeIdentityResponse;
  body: CodeIdentityDecisionRequest;
  toast: "codeIdentities.toast.linked" | "codeIdentities.toast.ignored" | "codeIdentities.toast.bot";
};

/**
 * The GitLab source's identities (`/data-sources/:id/identities`, ADMIN only): every GitLab user and commit address the sync found,
 * by status tab (Proposed / Unmapped / Linked / Ignored & bots — the `status` search param, so a tab is a link; the search box is the
 * `q` param beside it, so a link carries the search and another source never inherits it), with row-by-row decisions and one
 * "Confirm all proposals". The full addresses are shown (the admin decides on them). A 404 or 409 (a Jira source) is the not-found state.
 */
export default function DataSourceIdentities() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const idIsValid = Number.isFinite(id) && id > 0;
  const admin = useAdmin();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const statusParam = searchParams.get("status");
  const status = STATUSES.find((candidate) => candidate === statusParam) ?? "PROPOSED";

  const filter = searchParams.get("q") ?? "";
  const [debouncedFilter] = useDebouncedValue(filter, 300);
  const q = debouncedFilter.trim().slice(0, MAX_QUERY_LENGTH);
  const setFilter = (value: string) =>
    setSearchParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        if (value) next.set("q", value);
        else next.delete("q");
        return next;
      },
      { replace: true },
    );
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } = usePagedSort<(typeof SORT_FIELDS)[number]>(
    "displayName",
    [status, q],
    { key: SETTINGS_KEY, sortFields: SORT_FIELDS },
  );

  const list = useQuery({
    queryKey: ["dataSources", "identities", id, status, page, pageSize, sortParam, q],
    queryFn: () => listCodeIdentities(id, { page, pageSize, sort: sortParam, status, q: q || undefined }),
    placeholderData: keepPreviousData,
    enabled: idIsValid && admin,
  });

  // A page past the end (the last row of the last page was just decided or removed) answers an empty list with the unchanged total:
  // step back to the last page instead of showing an empty state over rows that still exist. Adjusting state while rendering, guarded.
  if (list.data && !list.isPlaceholderData && page > 1 && list.data.items.length === 0 && list.data.total > 0) {
    setPage(Math.max(1, Math.ceil(list.data.total / pageSize)));
  }

  const refresh = () => refreshQueriesAfterMutation(queryClient, ["dataSources", "identities"]);
  const [linking, setLinking] = useState<CodeIdentityResponse | null>(null);
  const [confirmAllOpen, setConfirmAllOpen] = useState(false);

  const decide = useMutation({
    mutationFn: ({ identity, body }: DecideVariables) => decideCodeIdentity(id, identity.identityKey, body),
    onSuccess: async (_row, variables) => {
      showSuccessToast(t(variables.toast));
      await refresh();
    },
  });
  const confirmAll = useMutation({
    mutationFn: () => confirmCodeIdentityProposals(id),
    onSuccess: async (result) => {
      showSuccessToast(t("codeIdentities.toast.confirmed", { linked: result.linked, bots: result.bots }));
      await refresh();
    },
    onSettled: () => setConfirmAllOpen(false),
  });
  const removal = useDeleteConfirm<CodeIdentityResponse>({
    mutationFn: (row) => removeCodeIdentityDecision(id, row.identityKey),
    onSuccess: refresh,
    successMessage: t("codeIdentities.toast.removed"),
  });

  const notFoundStatus = list.error instanceof ApiError && (list.error.status === 404 || list.error.status === 409);
  if (!idIsValid || (list.isError && !list.data && notFoundStatus)) {
    return (
      <EditPageLoadState
        isLoading={false}
        message={t("codeIdentities.notFound")}
        backTo={dataSourcePath(id)}
        backLabel={t("codeIdentities.back")}
        title={t("codeIdentities.title")}
      />
    );
  }

  const total = list.data?.total ?? 0;
  const searching = q !== "";
  // A new action clears the last one's failure (and a tab change does too): only the mutation that ran LAST can show an error.
  const startAction = () => {
    decide.reset();
    confirmAll.reset();
  };
  const actionError = confirmAll.isError
    ? saveErrorMessage(confirmAll.error, t, { ...ACTION_ERROR_KEYS, notFound: "codeIdentities.error.sourceGone" })
    : decide.isError
      ? saveErrorMessage(decide.error, t, ACTION_ERROR_KEYS)
      : null;
  const actions = {
    confirm: (identity: CodeIdentityResponse) => {
      const proposal = identity.proposal;
      if (!proposal) return;
      startAction();
      decide.mutate({
        identity,
        body: proposal.decision === "BOT" ? { decision: "BOT" } : { decision: "LINKED", jiraAccountId: proposal.jiraAccountId },
        toast: proposal.decision === "BOT" ? "codeIdentities.toast.bot" : "codeIdentities.toast.linked",
      });
    },
    link: (identity: CodeIdentityResponse) => {
      startAction();
      setLinking(identity);
    },
    markBot: (identity: CodeIdentityResponse) => {
      startAction();
      decide.mutate({ identity, body: { decision: "BOT" }, toast: "codeIdentities.toast.bot" });
    },
    ignore: (identity: CodeIdentityResponse) => {
      startAction();
      decide.mutate({ identity, body: { decision: "IGNORED" }, toast: "codeIdentities.toast.ignored" });
    },
    remove: (identity: CodeIdentityResponse) => {
      startAction();
      removal.requestDelete(identity);
    },
  };

  const body = (
    <Stack gap="md" mt="md">
      <Group justify="space-between" align="flex-end" wrap="wrap">
        <ClearableTextInput
          label={t("codeIdentities.search")}
          value={filter}
          onChange={setFilter}
          clearLabel={t("codeIdentities.clearSearch")}
        />
        {status === "PROPOSED" && (
          <Group gap="sm" align="center">
            {searching && (
              <Text size="xs" c="dimmed">
                {t("codeIdentities.confirmAllFilterHint")}
              </Text>
            )}
            <Button
              leftSection={<IconCheck size={16} />}
              disabled={total === 0 || searching}
              onClick={() => {
                startAction();
                setConfirmAllOpen(true);
              }}
            >
              {t("codeIdentities.confirmAll", { count: total })}
            </Button>
          </Group>
        )}
      </Group>

      {actionError && (
        <FormErrorAlert title={t("codeIdentities.actionFailed")}>
          {actionError}
        </FormErrorAlert>
      )}
      {list.isError && <ErrorAlert error={list.error} title={t("codeIdentities.loadFailed")} />}

      <Table aria-label={t(`codeIdentities.tab.${status}`)}>
        <Table.Thead>
          <Table.Tr>
            <SortHeader
              field="displayName"
              label={t("codeIdentities.column.identity")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <Table.Th>{t("codeIdentities.column.emails")}</Table.Th>
            <Table.Th>{t("codeIdentities.column.state")}</Table.Th>
            <Table.Th>{t("codeIdentities.column.person")}</Table.Th>
            <Table.Th>{t("codeIdentities.column.decidedAt")}</Table.Th>
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(list.isLoading && !list.data) || (list.data && list.data.items.length === 0 && list.data.total > 0) ? (
            // The second case is a page past the end, already on its way back to the last page.
            <TableLoadingRow colSpan={COLUMN_COUNT} />
          ) : list.data && list.data.items.length > 0 ? (
            list.data.items.map((identity) => <CodeIdentityRow key={identity.identityKey} identity={identity} actions={actions} />)
          ) : !list.isError ? (
            <Table.Tr>
              <Table.Td colSpan={COLUMN_COUNT}>
                <EmptyState icon={IconUsersGroup} label={t(searching ? "codeIdentities.empty.search" : `codeIdentities.empty.${status}`)} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      <PaginationBar total={total} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />
    </Stack>
  );

  return (
    <Stack gap="md">
      <PageHeader
        title={t("codeIdentities.title")}
        description={t("codeIdentities.intro")}
        backTo={{ to: dataSourcePath(id), label: t("codeIdentities.back") }}
      />

      <Tabs
        value={status}
        onChange={(value) => {
          if (!value) return;
          startAction();
          setSearchParams(
            (previous) => {
              const next = new URLSearchParams(previous);
              next.set("status", value);
              return next;
            },
            { replace: true },
          );
        }}
        keepMounted={false}
      >
        <Tabs.List>
          {STATUSES.map((candidate) => (
            <Tabs.Tab key={candidate} value={candidate}>
              {t(`codeIdentities.tab.${candidate}`)}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        {STATUSES.map((candidate) => (
          <Tabs.Panel key={candidate} value={candidate}>
            {body}
          </Tabs.Panel>
        ))}
      </Tabs>

      {linking && (
        <CodeIdentityLinkModal
          dataSourceId={id}
          identity={linking}
          onClose={() => setLinking(null)}
          onLinked={async () => {
            setLinking(null);
            await refresh();
          }}
        />
      )}

      <ConfirmActionModal
        opened={confirmAllOpen}
        onClose={() => setConfirmAllOpen(false)}
        title={t("codeIdentities.confirmAllTitle")}
        message={t("codeIdentities.confirmAllBody", { count: total })}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("codeIdentities.confirmAllTitle")}
        confirmColor="flow"
        loading={confirmAll.isPending}
        onConfirm={() => confirmAll.mutate()}
      />

      <ConfirmDeleteModal
        confirm={removal}
        title={t("codeIdentities.removeTitle")}
        errorTitle={t("codeIdentities.removeFailed")}
        body={(target) => t("codeIdentities.removeBody", { name: identityName(target, t) })}
      />
    </Stack>
  );
}
