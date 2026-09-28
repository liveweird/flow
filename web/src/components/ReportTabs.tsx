import { useTranslation } from "react-i18next";
import { Tabs } from "@mantine/core";
import { useLocation, useNavigate } from "react-router-dom";
import { reportHref, type ReportTabDef } from "../utils/reportLinks";

/**
 * Switches between the reports of one nav group (Delivery: velocity · throughput · …). The
 * filter query travels with the switch, so the period and team stay put. A group with a single
 * report has nothing to switch to and renders nothing.
 */
export default function ReportTabs({ tabs }: { tabs: ReadonlyArray<ReportTabDef> }) {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  if (tabs.length < 2) return null;
  return (
    <Tabs value={pathname} onChange={(to) => to && navigate(reportHref(to, search))}>
      <Tabs.List aria-label={t("reports.tabs.label")}>
        {tabs.map((tab) => (
          <Tabs.Tab key={tab.to} value={tab.to}>
            {t(tab.label)}
          </Tabs.Tab>
        ))}
      </Tabs.List>
    </Tabs>
  );
}
