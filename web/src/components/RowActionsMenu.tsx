import { type ReactNode } from "react";
import { ActionIcon, Menu } from "@mantine/core";
import { IconDotsVertical } from "@tabler/icons-react";

/**
 * The per-row actions menu (v1.19.0): an icon-only kebab trigger whose accessible name the
 * caller supplies in full ("Operations for {{name}}") — unit tests and the e2e `rowOperation`
 * helper locate rows by that name and read the trigger's `aria-expanded`/`aria-controls`,
 * both of which Mantine's Menu.Target wires. The caller owns the items (`Menu.Item`,
 * `Menu.Divider`); this component owns only the trigger's shape, so every table's kebab is
 * the same control. The cell around it is `Table.Td style={{ width: 1 }} ta="right"`.
 */
export default function RowActionsMenu({
  label,
  loading,
  children,
}: {
  label: string;
  loading?: boolean;
  children: ReactNode;
}) {
  return (
    <Menu>
      <Menu.Target>
        <ActionIcon size="sm" aria-label={label} loading={loading}>
          <IconDotsVertical size={16} />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>{children}</Menu.Dropdown>
    </Menu>
  );
}
