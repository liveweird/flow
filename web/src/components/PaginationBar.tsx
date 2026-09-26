import { useTranslation } from "react-i18next";
import { Group, Pagination, Select, Text } from "@mantine/core";
import { PAGE_SIZE_OPTIONS } from "../hooks/usePagedSort";

/** The shared list footer: total count, rows-per-page picker, and the pager. */
export default function PaginationBar({
  total,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
}: {
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
}) {
  const { t } = useTranslation();
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <Group justify="space-between" align="center">
      <Text size="sm" c="dimmed">
        {t("common.table.total", { count: total })}
      </Text>
      <Group gap="sm" align="center">
        <Select
          size="xs"
          aria-label={t("common.table.rowsPerPage")}
          data={PAGE_SIZE_OPTIONS.map((n) => ({
            value: String(n),
            label: t("common.table.perPage", { count: n }),
          }))}
          value={String(pageSize)}
          onChange={(v) => {
            if (!v) return;
            onPageSizeChange(Number(v));
          }}
          allowDeselect={false}
          w={110}
        />
        <Pagination
          value={page}
          onChange={onPageChange}
          total={totalPages}
          siblings={1}
          withEdges
          // The edge/arrow controls are icon-only; without names they fail WCAG 4.1.2
          // (the axe e2e spec pins this).
          getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })}
        />
      </Group>
    </Group>
  );
}
