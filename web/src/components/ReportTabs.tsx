import { useTranslation } from "react-i18next";
import { Tabs } from "@mantine/core";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { reportHref, type ReportTabDef } from "../utils/reportLinks";

/**
 * Switches between the reports of one nav group (Delivery: velocity · throughput · …). Each tab is a
 * real router link (`href` carries the filter query, so the period and team stay put): a plain click
 * navigates in place, middle-/cmd-click opens the report in a new tab. A group with a single report
 * has nothing to switch to and renders nothing.
 */
export default function ReportTabs({ tabs }: { tabs: ReadonlyArray<ReportTabDef> }) {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  if (tabs.length < 2) return null;
  return (
    <Tabs value={pathname}>
      <Tabs.List aria-label={t("reports.tabs.label")}>
        {tabs.map((tab) => (
          <Tabs.Tab key={tab.to} value={tab.to} renderRoot={(props) => <RouterLink {...props} to={reportHref(tab.to, search)} />}>
            {t(tab.label)}
          </Tabs.Tab>
        ))}
      </Tabs.List>
    </Tabs>
  );
}
