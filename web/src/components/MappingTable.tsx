import type { ReactNode } from "react";
import { Checkbox, Select, Text, TextInput } from "@mantine/core";
import ColumnTable, { type ColumnDef } from "./ColumnTable";

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

/** The editable control of one field cell (nothing for a row that has fewer fields than the table has columns). */
function FieldControl({ field }: { field: MappingField | undefined }) {
  if (field === undefined) return null;
  if (field.type === "select") {
    return (
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
    );
  }
  if (field.type === "text") {
    return (
      <TextInput
        aria-label={field.ariaLabel}
        value={field.value}
        onChange={(event) => field.onChange(event.currentTarget.value)}
        placeholder={field.placeholder}
        error={field.error}
        maxLength={field.maxLength}
      />
    );
  }
  return (
    <Checkbox
      aria-label={field.ariaLabel}
      checked={field.checked}
      onChange={(event) => field.onChange(event.currentTarget.checked)}
    />
  );
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
  const columns: ColumnDef<MappingRow>[] = [
    { key: "id", header: idColumnLabel, render: (row) => row.label },
    ...fieldColumnLabels.map((header, index) => ({
      key: `field-${index}`,
      header,
      render: (row: MappingRow) => <FieldControl field={row.fields[index]} />,
    })),
  ];
  return <ColumnTable aria-label={label} columns={columns} rows={rows} rowKey={(row) => row.id} />;
}
