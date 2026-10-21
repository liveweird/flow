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
 * Where the reader is in the domain → epic drill. The page is never read for the whole unit (a team or a domain is required), so there
 * is no unit crumb: the one ancestor is the epic's domain, a real link that keeps the period. The epic's domain is not part of the
 * report, so its crumb appears only when the reader came down through it (and only on an epic) — otherwise there is nothing to lead
 * back to and no breadcrumb.
 */
export default function EpicProgressBreadcrumb({ report }: { report: EpicProgressReport }) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const { state } = useLocation();
  const domain = drillDomain(state);
  if (report.level !== "EPIC" || report.scope === null || domain === undefined) return null;
  const label = report.scope.key && report.scope.name !== report.scope.key ? `${report.scope.key} · ${report.scope.name}` : report.scope.name;
  return (
    <Box component="nav" aria-label={t("reports.epicProgress.breadcrumb.label")}>
      <Breadcrumbs>
        <Anchor component={RouterLink} to={{ pathname: epicProgressPath, search: scopedSearch(params, { domain: domain.key }) }} size="sm">
          {domain.name}
        </Anchor>
        <Text size="sm" aria-current="page">
          {label}
        </Text>
      </Breadcrumbs>
    </Box>
  );
}
