import type { ReactNode } from "react";
import { Table, type TableProps } from "@mantine/core";

/** One column of a {@link ColumnTable}: its header text, how a row fills the cell, and its alignment. */
export interface ColumnDef<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  align?: "right";
}

/**
 * The plain header-row + body-rows table every report table shares: callers own the column list
 * (fixed identity columns are just the first defs), the row key and the table's own props (its
 * accessible name, spacing); this renders the `thead`/`tbody` scaffolding once.
 */
export default function ColumnTable<T>({
  columns,
  rows,
  rowKey,
  ...tableProps
}: {
  columns: ReadonlyArray<ColumnDef<T>>;
  rows: ReadonlyArray<T>;
  rowKey: (row: T, index: number) => string;
} & Omit<TableProps, "children">) {
  return (
    <Table {...tableProps}>
      <Table.Thead>
        <Table.Tr>
          {columns.map((column) => (
            <Table.Th key={column.key} ta={column.align}>
              {column.header}
            </Table.Th>
          ))}
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row, index) => (
          <Table.Tr key={rowKey(row, index)}>
            {columns.map((column) => (
              <Table.Td key={column.key} ta={column.align}>
                {column.render(row)}
              </Table.Td>
            ))}
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
