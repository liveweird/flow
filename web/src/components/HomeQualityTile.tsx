import { useTranslation } from "react-i18next";
import { Badge, Group, List, Text } from "@mantine/core";
import type { DataQualityReport } from "../api/reports";
import { HIGHLIGHT_LIMIT, qualityHighlights } from "../utils/homeOverview";
import HomeTile from "./HomeTile";

/**
 * Tile 4 — data-quality warnings: the few finding kinds with the highest counts (each an orange
 * badge, a soft finding), or a teal "none found" when the checks all came back clean. The tile
 * title and "All findings" both open the full report, where every card explains its finding.
 */
export default function HomeQualityTile({
  query,
  to,
}: {
  query: { data?: DataQualityReport; isPending: boolean; error: unknown };
  to: string;
}) {
  const { t } = useTranslation();
  const highlights = query.data ? qualityHighlights(query.data) : [];
  const shown = highlights.slice(0, HIGHLIGHT_LIMIT);
  const more = highlights.length - shown.length;
  return (
    <HomeTile
      title={t("home.quality.title")}
      to={to}
      caption={t("home.quality.caption")}
      isPending={query.isPending}
      error={query.error}
      links={[{ to, label: t("home.quality.all") }]}
    >
      {query.data &&
        (shown.length === 0 ? (
          <Badge color="teal" variant="light">
            {t("reports.dataQuality.state.none")}
          </Badge>
        ) : (
          <>
            <List listStyleType="none" spacing="xs" aria-label={t("home.quality.listLabel")}>
              {shown.map((entry) => (
                <List.Item key={entry.card} styles={{ itemWrapper: { width: "100%" }, itemLabel: { width: "100%" } }}>
                  <Group justify="space-between" wrap="nowrap" gap="sm">
                    <Text size="sm">{t(`reports.dataQuality.cards.${entry.card}.title`)}</Text>
                    <Badge color="orange" variant="light">
                      {entry.count}
                    </Badge>
                  </Group>
                </List.Item>
              ))}
            </List>
            {more > 0 && (
              <Text size="xs" c="dimmed" mt="xs">
                {t("home.quality.more", { count: more })}
              </Text>
            )}
          </>
        ))}
    </HomeTile>
  );
}
