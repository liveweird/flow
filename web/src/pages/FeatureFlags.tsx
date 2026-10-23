import { useState } from "react";
import { Stack, Table } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconUsers } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import { FeatureFlagBulkButtons, FeatureFlagBulkFeedback } from "../components/FeatureFlagBulk";
import FeatureFlagFilters from "../components/FeatureFlagFilters";
import FeatureFlagRow from "../components/FeatureFlagRow";
import FormErrorAlert from "../components/FormErrorAlert";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { listFlowUsers, updateFlowUserFeatures } from "../api/flowUsers";
import { useAdmin } from "../auth";
import { useFeatureFlagBulk } from "../hooks/useFeatureFlagBulk";
import { FEATURE_FLAGS_KEY, useFeatureFlagFilters } from "../hooks/useFeatureFlagFilters";
import { usePagedSort } from "../hooks/usePagedSort";
import { isFeatureEnabled, withFeatureEnabled, type FlowUserRow } from "../utils/featureFlags";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["id", "name", "email"] as const;
type SortField = (typeof SORT_FIELDS)[number];

/**
 * The per-feature admin screen (Lettuce's, ported): pick a feature, see every user with an
 * enabled/disabled switch, optionally filtered by state — the flag-first counterpart of the
 * per-user editor at /flow-users/:id/features. The state filter "any" deliberately sends NEITHER
 * list param (the server requires feature+featureEnabled as a pair); the switch state always
 * comes from each row's own disabledFeatures. Bulk enable/disable (`useFeatureFlagBulk`) acts on
 * EVERY row matching the current filters (not just the visible page), behind a count-stating
 * confirm — a client-side loop of the same per-user wholesale PUTs, no new endpoint.
 */
export default function FeatureFlags() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const queryClient = useQueryClient();
  const filters = useFeatureFlagFilters();
  const { feature, debounced } = filters;
  const [error, setError] = useState<string | null>(null);
  // The row whose toggle PUT is in flight — its switch disables until the refetch lands.
  const [pendingId, setPendingId] = useState<number | null>(null);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", filters.deps, { key: FEATURE_FLAGS_KEY, sortFields: SORT_FIELDS });

  const listQuery = (p: number, size: number) =>
    listFlowUsers({
      page: p,
      pageSize: size,
      sort: sortParam,
      name: debounced.name || undefined,
      email: debounced.email || undefined,
      feature: filters.state == null ? undefined : feature,
      featureEnabled: filters.state == null ? undefined : filters.state === "enabled",
    });

  const { data, isLoading, isError, error: loadError } = useQuery({
    queryKey: ["flowUsers", FEATURE_FLAGS_KEY, page, pageSize, sortParam, ...filters.deps],
    queryFn: () => listQuery(page, pageSize),
    placeholderData: keepPreviousData,
    enabled: admin,
  });

  const bulkState = useFeatureFlagBulk({
    feature,
    filterKey: filters.key,
    fetchPage: listQuery,
    onError: setError,
  });

  function startBulk(targetEnabled: boolean) {
    // Clear the stale notices at the interaction, not inside the hook's fetch callback.
    setError(null);
    bulkState.resetNotice();
    void bulkState.bulk.prepare(targetEnabled);
  }

  async function toggle(row: FlowUserRow) {
    const next = !isFeatureEnabled(row.disabledFeatures, feature);
    setError(null);
    bulkState.resetNotice();
    setPendingId(row.id);
    try {
      await updateFlowUserFeatures(row.id, withFeatureEnabled(row.disabledFeatures, feature, next));
      await refreshQueriesAfterMutation(queryClient, ["flowUsers"]);
      showSuccessToast(t("flowUsers.toast.featuresSaved"));
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "flowUsers.userNotFound",
          failedStatus: "flowUsers.featuresFailedStatus",
          failed: "flowUsers.featuresFailedNetwork",
        }),
      );
    } finally {
      setPendingId(null);
    }
  }

  const total = data?.total ?? 0;
  const columnCount = 3;
  const featureLabel = t(`common.feature.${feature}`);
  const sortProps = { activeField: sortField, activeDir: sortDir, onToggle: toggleSort };

  return (
    <Stack gap="md">
      <PageHeader
        title={t("flowUsers.featureFlags.title")}
        actions={
          <FeatureFlagBulkButtons state={bulkState} disabled={total === 0} onStart={startBulk} />
        }
      />
      <FeatureFlagFilters filters={filters} />

      {isError && <ErrorAlert error={loadError} title={t("flowUsers.loadFailed")} />}
      {error && <FormErrorAlert>{error}</FormErrorAlert>}
      <FeatureFlagBulkFeedback state={bulkState} featureLabel={featureLabel} />

      <Table aria-label={t("flowUsers.featureFlags.title")}>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} {...sortProps} />
            <SortHeader field="email" label={t("common.field.email")} {...sortProps} />
            <Table.Th style={{ width: 1, whiteSpace: "nowrap" }}>
              {t("flowUsers.featureFlags.enabledHeader")}
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((u) => (
              <FeatureFlagRow
                key={u.id}
                user={u}
                feature={feature}
                featureLabel={featureLabel}
                pending={pendingId === u.id}
                onToggle={() => void toggle(u)}
              />
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={IconUsers} label={t("flowUsers.noUsers")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
    </Stack>
  );
}
