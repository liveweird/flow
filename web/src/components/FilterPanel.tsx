import { type ReactNode } from "react";
import { Badge, Button, Group, Paper } from "@mantine/core";
import { IconChevronDown, IconFilter } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { isBoolean, useStoredState } from "../hooks/useStoredState";

/**
 * The collapsible filter drawer above every list table: collapsed by default, open state
 * persisted per view, with an active-filter-count badge on the toggle.
 */
export default function FilterPanel({
  activeFilterCount,
  storageKey,
  children,
  aside,
  trailing,
}: {
  activeFilterCount: number;
  storageKey: string;
  children: ReactNode;
  /** Rendered in the header row beside the toggle — reachable with the panel collapsed
   *  (the LensPicker's slot). */
  aside?: ReactNode;
  /** Rendered at the END of the header row, pushed right (the Kind pills' slot, v1.20.0). */
  trailing?: ReactNode;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useStoredState(`${storageKey}.filtersOpen`, false, isBoolean);
  return (
    <div>
      <Group gap="xs" mb={open ? "sm" : 0} wrap="wrap">
        <Button
          variant="default"
          size="xs"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
          leftSection={<IconFilter size={16} />}
          rightSection={
            <Group gap={6} wrap="nowrap" component="span">
              {activeFilterCount > 0 && (
                <Badge size="sm" circle variant="filled">
                  {activeFilterCount}
                </Badge>
              )}
              <IconChevronDown
                size={16}
                style={{ transform: open ? "rotate(180deg)" : "none", transition: "transform 150ms ease" }}
              />
            </Group>
          }
        >
          {t("common.filter.title")}
        </Button>
        {aside}
        {trailing && <Group ml="auto">{trailing}</Group>}
      </Group>
      {open && (
        <Paper withBorder radius="md" p="sm" bg="var(--mantine-color-default-hover)">
          <Group align="flex-end" gap="sm">
            {children}
          </Group>
        </Paper>
      )}
    </div>
  );
}
