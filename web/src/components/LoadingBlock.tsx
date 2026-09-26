import { Center, Loader, type MantineSize, type MantineSpacing } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * The one loading spinner (v1.19.0) — the route fallback, the edit pages' load state, the
 * registries, the Hierarchy/Graph and the history all render this instead of their own
 * `Center`/`Loader` pairing. Named for assistive tech; sized small by default so it reads as
 * an in-place state, not an interstitial.
 */
export default function LoadingBlock({
  py = "xl",
  size = "sm",
  mih,
}: {
  py?: MantineSpacing;
  size?: MantineSize;
  mih?: number;
}) {
  const { t } = useTranslation();
  return (
    <Center py={py} mih={mih}>
      <Loader size={size} role="status" aria-label={t("common.loading")} />
    </Center>
  );
}
