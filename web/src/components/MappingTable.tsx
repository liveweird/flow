import type { ReactNode } from "react";
import { Checkbox, Select, Table, Text, TextInput } from "@mantine/core";

/**
 * One editable cell in a `MappingRow` — a `Select` over a fixed option list, a free-text
 * `TextInput` (the activity-type/domain/work-category maps, whose target value has no
 * enumerable option set server-side), or a `Checkbox` (the Statuses tab's "Blocked" column).
 * `ariaLabel` names the control for tests/e2e — every field needs one, since a MappingTable row's
 * visual label lives in the row's OWN `label` cell, not attached to the control itself.
 */
export type MappingField =
  | {
      type: "select";
      ariaLabel: string;
      value: string;
      onChange: (value: string) => void;
      options: { value: string; label: string }[];
      placeholder?: string;
      clearable?: boolean;
      error?: string;
    }
  | {
      type: "text";
      ariaLabel: string;
      value: string;
      onChange: (value: string) => void;
      placeholder?: string;
      error?: string;
      /** The server's column width for this value (a longer one is a 400), mirrored on the input. */
      maxLength?: number;
    }
  | {
      type: "checkbox";
      ariaLabel: string;
      checked: boolean;
      onChange: (checked: boolean) => void;
    };

export interface MappingRow {
  /** A stable React key — the row's own identifying id (a status/board/project/sprint id, as text). */
  id: string;
  /** The row's fixed left-hand identity (name, optional badge) — never itself editable. */
  label: ReactNode;
  fields: MappingField[];
}

/**
 * The one generic id → editable-cell table every metrics-config tab reuses
 * (`.claude/docs/metrics.md`, `pages/DataSourceMetricsConfig.tsx`): a fixed identity column plus
 * N editable field columns, each independently a `Select`/`TextInput`/`Checkbox`. Callers own all
 * state — this component is a pure renderer over `rows`.
 */
export default function MappingTable({
  label,
  idColumnLabel,
  fieldColumnLabels,
  rows,
  emptyMessage,
}: {
  /** The table's accessible name — the tab it sits in. */
  label: string;
  idColumnLabel: string;
  fieldColumnLabels: string[];
  rows: MappingRow[];
  emptyMessage?: string;
}) {
  if (rows.length === 0) {
    return emptyMessage ? (
      <Text size="sm" c="dimmed">
        {emptyMessage}
      </Text>
    ) : null;
  }
  return (
    <Table aria-label={label}>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{idColumnLabel}</Table.Th>
          {fieldColumnLabels.map((label) => (
            <Table.Th key={label}>{label}</Table.Th>
          ))}
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row) => (
          <Table.Tr key={row.id}>
            <Table.Td>{row.label}</Table.Td>
            {row.fields.map((field, index) => (
              <Table.Td key={`${row.id}-${index}`}>
                {field.type === "select" && (
                  <Select
                    aria-label={field.ariaLabel}
                    value={field.value || null}
                    onChange={(value) => field.onChange(value ?? "")}
                    data={field.options}
                    placeholder={field.placeholder}
                    clearable={field.clearable ?? true}
                    error={field.error}
                    searchable
                  />
                )}
                {field.type === "text" && (
                  <TextInput
                    aria-label={field.ariaLabel}
                    value={field.value}
                    onChange={(event) => field.onChange(event.currentTarget.value)}
                    placeholder={field.placeholder}
                    error={field.error}
                    maxLength={field.maxLength}
                  />
                )}
                {field.type === "checkbox" && (
                  <Checkbox
                    aria-label={field.ariaLabel}
                    checked={field.checked}
                    onChange={(event) => field.onChange(event.currentTarget.checked)}
                  />
                )}
              </Table.Td>
            ))}
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
