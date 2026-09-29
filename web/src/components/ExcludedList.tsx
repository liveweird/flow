import { useTranslation } from "react-i18next";
import { List, Stack, Text, Title } from "@mantine/core";

export interface ExcludedItem {
  label: string;
  count: number;
}

/**
 * The accounting of ONE distribution: how many items were in the population, how many made it into
 * the distribution, and one counted line per reason the rest did not — each item sits in exactly
 * one (the server's per-view partition), so the closing line reconciles: `n + Σ reasons =
 * population`. Zero-count reasons still show, so the list reads as the whole accounting rather than
 * only the bad news. Rendered under the distribution it describes — two views never share a list,
 * because their partitions differ.
 */
export default function ExcludedList({
  population,
  measured,
  items,
}: {
  population: number;
  /** The distribution's own `n`. */
  measured: number;
  items: ReadonlyArray<ExcludedItem>;
}) {
  const { t } = useTranslation();
  const equation = `${[measured, ...items.map((item) => item.count)].join(" + ")} = ${population}`;
  return (
    <Stack gap="xs">
      <Title order={5} size="h6">
        {t("reports.excluded.title")}
      </Title>
      <Text size="sm">{t("reports.excluded.population", { count: population })}</Text>
      <List size="sm" spacing={2}>
        <List.Item>
          <Text component="span" fw={600}>
            {measured}
          </Text>{" "}
          {t("reports.excluded.measured")}
        </List.Item>
        {items.map((item) => (
          <List.Item key={item.label}>
            <Text component="span" fw={600}>
              {item.count}
            </Text>{" "}
            {item.label}
          </List.Item>
        ))}
      </List>
      <Text size="xs" c="dimmed">
        {equation}
      </Text>
    </Stack>
  );
}
