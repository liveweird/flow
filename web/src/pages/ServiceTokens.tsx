import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Alert, Badge, Button, Group, Paper, Select, Stack, Text, TextInput, Tooltip, VisuallyHidden } from "@mantine/core";
import { IconKey, IconKeyOff, IconPlus } from "@tabler/icons-react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createServiceToken, listServiceTokens, revokeServiceToken, type ServiceToken, type ServiceTokenCreated } from "../api/serviceTokens";
import { useAdmin } from "../auth";
import ColumnTable, { type ColumnDef } from "../components/ColumnTable";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import RevealablePassword from "../components/RevealablePassword";
import ScrollRegion from "../components/ScrollRegion";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { charCountDescription } from "../utils/charCount";
import { formatDate, formatDateTime } from "../utils/formatDate";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import {
  EXPIRY_CHOICES,
  expiresAtFor,
  lastUsedAge,
  MAX_SERVICE_TOKEN_NAME,
  SERVICE_TOKEN_STATUS_COLOR,
  serviceTokenStatus,
  type ExpiryChoice,
} from "../utils/serviceTokens";

/**
 * The ADMIN service-token registry (`/service-tokens`, ported from Lettuce's integration clients): the create strip whose response
 * is the ONE moment the generated secret is visible (the reveal panel IS the confirmation, so no toast), the registry table and the
 * terminal Revoke. A token is Active, Revoked or Expired (unrevoked, `expiresAt` in the past: derived here, the API only says
 * `revoked`); an expired token keeps its name until it is revoked. The whole surface is ADMIN-only, reads included: the route sits
 * inside `RequireAdmin` and the queries stay `enabled: admin` (the server's `requireAdmin` is the rule).
 */
export default function ServiceTokens() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const [name, setName] = useState("");
  const [expiry, setExpiry] = useState<ExpiryChoice>("never");
  const [created, setCreated] = useState<ServiceTokenCreated | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const tokens = useQuery({ queryKey: ["serviceTokens"], queryFn: listServiceTokens, enabled: admin });
  const refresh = () => refreshQueriesAfterMutation(queryClient, ["serviceTokens"]);
  const revokeConfirm = useDeleteConfirm<ServiceToken>({
    mutationFn: (token) => revokeServiceToken(token.id),
    onSuccess: (token) => {
      // The one-time secret panel must not outlive its token.
      setCreated((current) => (current?.token.id === token.id ? null : current));
      return refresh();
    },
    successMessage: t("serviceTokens.toast.revoked"),
  });

  const nameValid = name.trim().length > 0 && name.trim().length <= MAX_SERVICE_TOKEN_NAME;

  async function add(event: FormEvent) {
    event.preventDefault();
    if (!nameValid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const response = await createServiceToken({ name: name.trim(), expiresAt: expiresAtFor(expiry, Date.now()) });
      setCreated(response);
      setName("");
      await refresh();
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          forbidden: "serviceTokens.error.forbidden",
          invalid: "serviceTokens.error.invalid",
          conflict: "serviceTokens.error.nameTaken",
          failedStatus: "serviceTokens.error.failedStatus",
          failed: "serviceTokens.error.failed",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  // "Now" is the moment the list was fetched, so the render stays pure and a refetch re-derives every status and age.
  const now = tokens.dataUpdatedAt;
  const columns: ColumnDef<ServiceToken>[] = [
    {
      key: "name",
      header: t("serviceTokens.name"),
      render: (token) => (
        <Text size="sm" fw={500}>
          {token.name}
        </Text>
      ),
    },
    {
      key: "status",
      header: t("serviceTokens.column.status"),
      render: (token) => {
        const status = serviceTokenStatus(token, now);
        return (
          <Badge variant="light" color={SERVICE_TOKEN_STATUS_COLOR[status]}>
            {t(`serviceTokens.status.${status}`)}
          </Badge>
        );
      },
    },
    { key: "createdBy", header: t("serviceTokens.column.createdBy"), render: (token) => <Text size="sm">{token.createdByName}</Text> },
    {
      key: "expires",
      header: t("serviceTokens.column.expires"),
      render: (token) => (
        <Text size="sm" c={token.expiresAt == null ? "dimmed" : undefined}>
          {token.expiresAt == null ? t("serviceTokens.never") : formatDate(token.expiresAt)}
        </Text>
      ),
    },
    { key: "lastUsed", header: t("serviceTokens.column.lastUsed"), render: (token) => <LastUsed token={token} now={now} /> },
    {
      key: "actions",
      header: <VisuallyHidden>{t("common.table.operations")}</VisuallyHidden>,
      align: "right",
      // Revoke is terminal: a revoked row has no action at all.
      render: (token) =>
        token.revoked ? null : (
          <Tooltip label={t("serviceTokens.revoke")}>
            <ActionIcon
              variant="subtle"
              color="red"
              size="sm"
              aria-label={t("serviceTokens.revokeAria", { name: token.name })}
              onClick={() => revokeConfirm.requestDelete(token)}
            >
              <IconKeyOff size={16} />
            </ActionIcon>
          </Tooltip>
        ),
    },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("serviceTokens.title")} description={t("serviceTokens.hint")} />

      <Paper withBorder p="md" maw={CONTENT_MAX_WIDTH}>
        <Stack gap="sm">
          <Group component="form" onSubmit={(event) => void add(event)} align="flex-start" gap="md" wrap="wrap">
            <TextInput
              label={t("serviceTokens.name")}
              value={name}
              onChange={(e) => setName(e.currentTarget.value)}
              maxLength={MAX_SERVICE_TOKEN_NAME}
              description={charCountDescription(name.length, MAX_SERVICE_TOKEN_NAME, "always")}
              inputWrapperOrder={["label", "input", "description", "error"]}
              w={300}
            />
            <Select
              label={t("serviceTokens.expiry.label")}
              data={EXPIRY_CHOICES.map((choice) => ({ value: choice, label: t(`serviceTokens.expiry.${choice}`) }))}
              value={expiry}
              onChange={(value) => value && setExpiry(value as ExpiryChoice)}
              allowDeselect={false}
              w={180}
            />
            <Button type="submit" mt={25} leftSection={<IconPlus size={16} />} loading={submitting} disabled={!nameValid}>
              {t("serviceTokens.create")}
            </Button>
          </Group>
          {error && <FormErrorAlert>{error}</FormErrorAlert>}
        </Stack>
      </Paper>

      {created && (
        <Alert
          // Keyed by token so a SECOND create remounts the panel: RevealablePassword's reveal state would otherwise
          // survive and show the next secret unmasked.
          key={created.token.id}
          color="teal"
          variant="light"
          maw={CONTENT_MAX_WIDTH}
          title={t("serviceTokens.created.title", { name: created.token.name })}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={() => setCreated(null)}
        >
          <Stack gap="xs">
            <Text size="sm">{t("serviceTokens.created.warning")}</Text>
            <RevealablePassword password={created.secret} copyLabel={t("serviceTokens.created.copy")} />
          </Stack>
        </Alert>
      )}

      {tokens.isError && <ErrorAlert error={tokens.error} title={t("serviceTokens.loadFailed")} />}

      {tokens.isLoading ? (
        <LoadingBlock />
      ) : tokens.data && tokens.data.items.length > 0 ? (
        <Stack maw={CONTENT_MAX_WIDTH}>
          <ScrollRegion label={t("serviceTokens.tableAria")} minWidth={720}>
            <ColumnTable
              columns={columns}
              rows={tokens.data.items}
              rowKey={(token) => String(token.id)}
              aria-label={t("serviceTokens.tableAria")}
              verticalSpacing="xs"
            />
          </ScrollRegion>
        </Stack>
      ) : tokens.data ? (
        <EmptyState icon={IconKey} label={t("serviceTokens.empty")} />
      ) : null}

      <ConfirmDeleteModal
        confirm={revokeConfirm}
        title={t("serviceTokens.revokeTitle")}
        errorTitle={t("serviceTokens.revokeFailed")}
        confirmLabel={t("serviceTokens.revoke")}
        body={(token) => t("serviceTokens.revokeBody", { name: token.name })}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            forbidden: "serviceTokens.error.forbidden",
            notFound: "serviceTokens.error.gone",
            conflict: "serviceTokens.error.alreadyRevoked",
            failedStatus: "serviceTokens.error.revokeFailedStatus",
            failed: "serviceTokens.error.revokeFailed",
          })
        }
      />
    </Stack>
  );
}

/** "Last used" as an age ("5 min ago"), the exact moment on hover; a token nobody presented yet reads "Never". */
function LastUsed({ token, now }: { token: ServiceToken; now: number }) {
  const { t } = useTranslation();
  if (token.lastUsedAt == null) {
    return (
      <Text size="sm" c="dimmed">
        {t("serviceTokens.never")}
      </Text>
    );
  }
  const age = lastUsedAge(token.lastUsedAt, now);
  return (
    <Text size="sm" title={formatDateTime(token.lastUsedAt)}>
      {age === null ? t("serviceTokens.justNow") : t("serviceTokens.ago", { age })}
    </Text>
  );
}
