import { Table } from "@mantine/core";
import LoadingBlock from "./LoadingBlock";

/** The shared first-load body for list tables (a single spinner row). */
export default function TableLoadingRow({ colSpan }: { colSpan: number }) {
  return (
    <Table.Tr>
      <Table.Td colSpan={colSpan}>
        <LoadingBlock py="md" />
      </Table.Td>
    </Table.Tr>
  );
}
