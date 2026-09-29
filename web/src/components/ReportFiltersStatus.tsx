import type { UseQueryResult } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Alert } from "@mantine/core";
import type { ReportFilters } from "../api/reports";
import { loadErrorMessage } from "../utils/saveError";
import LoadingBlock from "./LoadingBlock";

/** The reference-data query's own load/failure state, shown above a report until it resolves. */
export default function ReportFiltersStatus({ query }: { query: UseQueryResult<ReportFilters> }) {
  const { t } = useTranslation();
  if (query.isError) {
    return (
      <Alert color="red" variant="light" role="alert">
        {loadErrorMessage(query.error, t)}
      </Alert>
    );
  }
  return query.isPending ? <LoadingBlock /> : null;
}
