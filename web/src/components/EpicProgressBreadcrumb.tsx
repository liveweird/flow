import { useTranslation } from "react-i18next";
import { Anchor, Box, Breadcrumbs, Text } from "@mantine/core";
import { Link as RouterLink, useLocation, useSearchParams } from "react-router-dom";
import type { EpicProgressReport } from "../api/reports";
import { scopedSearch } from "../utils/epicProgressReport";
import { epicProgressPath } from "../utils/reportLinks";
import type { EpicDrillState } from "./EpicProgressRows";

/** The domain an epic was reached from, when the drill link said so and the state is well-formed. */
function drillDomain(state: unknown): EpicDrillState["domain"] {
  const domain = (state as EpicDrillState | null)?.domain;
  return typeof domain?.key === "string" && typeof domain.name === "string" ? domain : undefined;
}

/**
 * Where the reader is in the unit → domain → epic drill (a team is one level below the unit). Every
 * ancestor is a real link that keeps the period; the current level is plain text. The epic's domain
 * is not part of the report, so its crumb appears only when the reader came down through it. The unit
 * crumb is the one that can be a BARE link (default period, no team), which the remembered team would turn
 * back into a team view — so following it forgets the team, like clearing the Team control does.
 */
export default function EpicProgressBreadcrumb({
  report,
  onUnit,
}: {
  report: EpicProgressReport;
  /** Called when the unit crumb is followed — the page clears the remembered team, or the bare link would re-apply it. */
  onUnit: () => void;
}) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const { state } = useLocation();
  if (report.level === "UNIT" || report.scope === null) return null;
  const domain = report.level === "EPIC" ? drillDomain(state) : undefined;
  const label = report.level === "EPIC" && report.scope.key && report.scope.name !== report.scope.key
    ? `${report.scope.key} · ${report.scope.name}`
    : report.scope.name;
  return (
    <Box component="nav" aria-label={t("reports.epicProgress.breadcrumb.label")}>
      <Breadcrumbs>
        <Anchor component={RouterLink} to={{ pathname: epicProgressPath, search: scopedSearch(params, {}) }} size="sm" onClick={onUnit}>
          {t("reports.epicProgress.breadcrumb.unit")}
        </Anchor>
        {domain && (
          <Anchor
            component={RouterLink}
            to={{ pathname: epicProgressPath, search: scopedSearch(params, { domain: domain.key }) }}
            size="sm"
          >
            {domain.name}
          </Anchor>
        )}
        <Text size="sm" aria-current="page">
          {label}
        </Text>
      </Breadcrumbs>
    </Box>
  );
}
