import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Button, Group, Paper, Select, Stack, Text, TextInput, VisuallyHidden } from "@mantine/core";
import { IconCalendarOff, IconPlus, IconTrash } from "@tabler/icons-react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createPublicHoliday, deletePublicHoliday, listPublicHolidays, type PublicHoliday } from "../api/metrics";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import ColumnTable, { type ColumnDef } from "../components/ColumnTable";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { isValidIsoDate } from "../utils/isoDate";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { holidaysOfYear, holidayYears, localYear, weekdayName, yearOf } from "../utils/publicHolidays";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "../components/FormErrorAlert";

const MAX_NAME = 100;

/**
 * The global public-holiday dictionary (`/public-holidays`, ported from Lettuce's page): every signed-in
 * user reads it, only an ADMIN adds or deletes (the always-visible strip above the table and the row's
 * delete button). A holiday is always a non-working day — the capacity calendars lock it off and the
 * working calendar behind the default capacity and every report leaves it out — so a change here
 * re-derives the metrics. The dictionary spans many years, so the table shows ONE year at a time
 * (client-side: the endpoint is unpaged), defaulting to the current one.
 */
export default function PublicHolidays() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const [currentYear] = useState(localYear);
  const [year, setYear] = useState(currentYear);
  const [date, setDate] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const holidays = useQuery({ queryKey: ["publicHolidays"], queryFn: listPublicHolidays });
  const refresh = () => refreshQueriesAfterMutation(queryClient, ["publicHolidays"], ["capacity"]);
  const deleteConfirm = useDeleteConfirm<PublicHoliday>({
    mutationFn: (row) => deletePublicHoliday(row.id),
    onSuccess: refresh,
    successMessage: t("publicHolidays.toast.deleted"),
  });

  const dateInvalid = date !== "" && !isValidIsoDate(date);
  const canAdd = isValidIsoDate(date) && name.trim().length > 0 && name.trim().length <= MAX_NAME;

  async function add(event: FormEvent) {
    event.preventDefault();
    if (!canAdd || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await createPublicHoliday({ date, name: name.trim() });
      await refresh();
      showSuccessToast(t("publicHolidays.toast.added"));
      // Show where the new row landed, whatever year was open.
      setYear(yearOf(date));
      setName("");
      setDate("");
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 409
          ? t("publicHolidays.duplicateDate")
          : saveErrorMessage(err, t, {
              forbidden: "publicHolidays.error.forbidden",
              invalid: "publicHolidays.invalid",
              failedStatus: "publicHolidays.error.failedStatus",
              failed: "publicHolidays.error.failed",
            }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const columns: ColumnDef<PublicHoliday>[] = [
    { key: "date", header: t("publicHolidays.date"), render: (h) => h.date },
    {
      key: "weekday",
      header: t("publicHolidays.weekday"),
      render: (h) => (
        <Text size="sm" c="dimmed" component="span">
          {weekdayName(h.date, i18n.language)}
        </Text>
      ),
    },
    { key: "name", header: t("publicHolidays.name"), render: (h) => h.name },
    ...(admin
      ? [
          {
            key: "actions",
            header: <VisuallyHidden>{t("common.table.operations")}</VisuallyHidden>,
            align: "right" as const,
            render: (h: PublicHoliday) => (
              <ActionIcon
                variant="subtle"
                color="red"
                size="sm"
                aria-label={t("publicHolidays.deleteAria", { name: h.name })}
                onClick={() => deleteConfirm.requestDelete(h)}
              >
                <IconTrash size={16} />
              </ActionIcon>
            ),
          },
        ]
      : []),
  ];

  const rows = holidays.data ? holidaysOfYear(holidays.data, year) : [];

  return (
    <Stack gap="md">
      <PageHeader title={t("publicHolidays.title")} description={t(admin ? "publicHolidays.hint" : "publicHolidays.hintReadOnly")} />

      {admin && (
        <Paper withBorder p="md" maw={CONTENT_MAX_WIDTH}>
          <Stack gap="sm">
            <Group component="form" onSubmit={(event) => void add(event)} align="flex-end" gap="md" wrap="wrap">
              <TextInput
                label={t("publicHolidays.date")}
                placeholder={t("common.dateFormatHint")}
                value={date}
                onChange={(e) => setDate(e.currentTarget.value.trim())}
                error={dateInvalid ? t("publicHolidays.dateInvalid") : undefined}
                w={180}
              />
              <TextInput
                label={t("publicHolidays.name")}
                value={name}
                onChange={(e) => setName(e.currentTarget.value)}
                maxLength={MAX_NAME}
                w={300}
              />
              <Button type="submit" leftSection={<IconPlus size={16} />} loading={submitting} disabled={!canAdd}>
                {t("publicHolidays.addHoliday")}
              </Button>
            </Group>
            {error && (
              <FormErrorAlert>
                {error}
              </FormErrorAlert>
            )}
          </Stack>
        </Paper>
      )}

      {holidays.isError && <ErrorAlert error={holidays.error} />}

      {holidays.isLoading ? (
        <LoadingBlock />
      ) : holidays.data ? (
        <Stack gap="sm" maw={CONTENT_MAX_WIDTH}>
          <Select
            label={t("publicHolidays.year")}
            data={holidayYears(holidays.data, currentYear, year)}
            value={year}
            onChange={(value) => value && setYear(value)}
            allowDeselect={false}
            w={140}
          />
          {rows.length > 0 ? (
            <ColumnTable
              columns={columns}
              rows={rows}
              rowKey={(h) => String(h.id)}
              aria-label={t("publicHolidays.tableAria", { year })}
              verticalSpacing="xs"
            />
          ) : (
            <EmptyState icon={IconCalendarOff} label={t(admin ? "publicHolidays.empty" : "publicHolidays.emptyReadOnly", { year })} />
          )}
        </Stack>
      ) : null}

      <ConfirmDeleteModal
        confirm={deleteConfirm}
        title={t("publicHolidays.deleteTitle")}
        errorTitle={t("publicHolidays.deleteFailed")}
        body={(h) => t("publicHolidays.deleteBody", { date: h.date, name: h.name })}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            forbidden: "publicHolidays.error.forbidden",
            notFound: "publicHolidays.error.gone",
            failedStatus: "publicHolidays.error.deleteFailedStatus",
            failed: "publicHolidays.error.deleteFailed",
          })
        }
      />
    </Stack>
  );
}
