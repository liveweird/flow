import { memo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Group, Stack, Text } from "@mantine/core";
import { LAYER_VARS } from "../utils/deepDiveCell";
import classes from "../theme.module.css";

function Item({ mark, children }: { mark: ReactNode; children: string }) {
  return (
    <Group gap={6} wrap="nowrap" align="center">
      <span className={classes.ddSwatch} aria-hidden>
        {mark}
      </span>
      <Text size="xs">{children}</Text>
    </Group>
  );
}

/**
 * The matrix's key: the three layers with their mark shapes (the width is the cue colour alone must not be), the
 * one-scale sentence, the ◆ done marker, the dashed planned window and the hatching. Marks are decorative
 * (`aria-hidden`) — each item's words say the same.
 */
const DeepDiveLegend = memo(function DeepDiveLegend() {
  const { t } = useTranslation();
  return (
    <Stack gap={4} role="group" aria-label={t("reports.deepDive.matrix.legend.label")} style={LAYER_VARS}>
      <Group gap="md" wrap="wrap">
        <Item mark={<span className={`${classes.ddBar} ${classes.ddBarPv}`} style={{ height: "100%" }} />}>
          {t("reports.deepDive.matrix.legend.pv")}
        </Item>
        <Item mark={<span className={`${classes.ddBar} ${classes.ddBarExec}`} style={{ height: "100%" }} />}>
          {t("reports.deepDive.matrix.legend.exec")}
        </Item>
        <Item mark={<span className={`${classes.ddBar} ${classes.ddBarCost}`} style={{ height: "100%" }} />}>
          {t("reports.deepDive.matrix.legend.cost")}
        </Item>
        <Item mark={<span className={classes.ddDoneMark}>◆</span>}>{t("reports.deepDive.matrix.legend.done")}</Item>
        <Item
          mark={
            <span className={`${classes.ddOutline} ${classes.ddOutlineStart} ${classes.ddOutlineEnd}`} />
          }
        >
          {t("reports.deepDive.matrix.legend.window")}
        </Item>
        <Item mark={<span className={classes.ddHatchSwatch} />}>{t("reports.deepDive.matrix.legend.hatched")}</Item>
      </Group>
      <Text size="xs" c="dimmed">
        {t("reports.deepDive.matrix.legend.scale")}
      </Text>
    </Stack>
  );
});

export default DeepDiveLegend;
