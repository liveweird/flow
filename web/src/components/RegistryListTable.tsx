import type { ReactNode } from "react";
import type { Icon } from "@tabler/icons-react";
import { Table } from "@mantine/core";
import EmptyState from "./EmptyState";
import PaginationBar from "./PaginationBar";
import TableLoadingRow from "./TableLoadingRow";
import ErrorAlert from "./ErrorAlert";

/** Common load/error/empty/pagination shell for the small registry tables. */
export default function RegistryListTable({
  label,
  errorTitle,
  error,
  isError,
  isLoading,
  hasData,
  rowCount,
  columnCount,
  emptyIcon,
  emptyLabel,
  header,
  rows,
  total,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
}: {
  /** The table's accessible name — the same words as the page or section heading above it. */
  label: string;
  errorTitle: string;
  error: unknown;
  isError: boolean;
  isLoading: boolean;
  hasData: boolean;
  rowCount: number;
  columnCount: number;
  emptyIcon: Icon;
  emptyLabel: string;
  header: ReactNode;
  rows: ReactNode;
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
}) {
  return (
    <>
      {isError && <ErrorAlert error={error} title={errorTitle} />}
      <Table aria-label={label}>
        <Table.Thead>{header}</Table.Thead>
        <Table.Tbody>
          {isLoading && !hasData ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : rowCount > 0 ? (
            rows
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={emptyIcon} label={emptyLabel} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </>
  );
}
