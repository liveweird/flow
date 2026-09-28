import { Anchor, Box, Stack } from "@mantine/core";
import { IconDatabaseOff } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAdmin } from "../auth";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import { dataSourcesPath } from "../utils/dataSourceLinks";
import { CONTENT_MAX_WIDTH } from "../utils/layout";

/**
 * The landing page (`/`) — a placeholder until data sources arrive: Flow has nothing to show
 * yet, so the page states that plainly instead of rendering an empty dashboard. Everyone gets
 * the same intro; an ADMIN additionally gets the hint that data sources are coming.
 */
export default function Home() {
  const { t } = useTranslation();
  const admin = useAdmin();

  return (
    <Stack gap="md">
      <PageHeader title={t("home.title")} description={t("home.intro")} />
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack align="center" gap="sm">
          <EmptyState
            icon={IconDatabaseOff}
            label={admin ? t("home.emptyAdmin") : t("home.empty")}
          />
          {admin && (
            <Anchor component={RouterLink} to={dataSourcesPath} size="sm">
              {t("home.emptyAdminLink")}
            </Anchor>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
