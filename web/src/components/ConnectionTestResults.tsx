import type { TFunction } from "i18next";
import { Badge, Stack, Table, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ConnectionTestResult, ConnectionTestRow } from "../api/dataSources";

/** The detail cell: the upstream status/code, plus the scope a failed row likely needs — never rendered for an ok row. */
function rowDetail(row: ConnectionTestRow, t: TFunction): string {
  if (row.ok) return "—";
  const parts = [
    row.status != null ? String(row.status) : null,
    row.code,
    row.scopeHint ? t("dataSources.test.scopeHint", { scope: row.scopeHint }) : null,
  ].filter((part): part is string => Boolean(part));
  return parts.length > 0 ? parts.join(" · ") : "—";
}

/**
 * The Test-connection outcome table (v0.2.0 plan §6/§10): one row per probed Jira endpoint, in
 * the order the connector ran them. `ok` renders teal (the SUCCESS color), a failure red (the
 * BLOCKING color) — no new hue. Reused by the editor modal (ad-hoc/stored probe) and, later, the
 * data-source details page.
 */
export default function ConnectionTestResults({ result }: { result: ConnectionTestResult }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Table aria-label={t("dataSources.test.tableAria")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("dataSources.test.column.endpoint")}</Table.Th>
            <Table.Th>{t("dataSources.test.column.required")}</Table.Th>
            <Table.Th>{t("dataSources.test.column.result")}</Table.Th>
            <Table.Th>{t("dataSources.test.column.detail")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {result.rows.map((row) => (
            <Table.Tr key={row.name}>
              <Table.Td>
                <Text size="sm" fw={500}>
                  {row.name}
                </Text>
                <Text size="xs" c="dimmed">
                  {row.path}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{row.required ? t("dataSources.test.required") : t("dataSources.test.optional")}</Text>
              </Table.Td>
              <Table.Td>
                <Badge color={row.ok ? "teal" : "red"} variant="light">
                  {row.ok ? t("dataSources.test.ok") : t("dataSources.test.failed")}
                </Badge>
              </Table.Td>
              <Table.Td>
                <Text size="sm" c="dimmed">
                  {rowDetail(row, t)}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {result.cloudId && (
        <Text size="xs" c="dimmed">
          {t("dataSources.test.cloudId", { cloudId: result.cloudId })}
        </Text>
      )}
    </Stack>
  );
}
