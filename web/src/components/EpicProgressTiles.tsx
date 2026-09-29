import { useTranslation } from "react-i18next";
import { SimpleGrid } from "@mantine/core";
import type { EpicProgressReport } from "../api/reports";
import { indexVerdict } from "../utils/epicProgressReport";
import { formatIndex, formatMd, formatPercent, formatSignedMd } from "../utils/reportFormat";
import ReportTile from "./ReportTile";

const MISSING = "—";

/**
 * The headline: PV, EV, AC, the two variances (signed MD) and the two indices (two decimals, a
 * dash plus the reason when there is no ratio). An index is never coloured red or green: which side
 * of 1 it sits on is said in words — under 1 is a fact about the plan, not a blocking failure. At
 * TEAM level the foreign-work share sits beside CPI, because a team's cost index means little
 * without knowing how much of the logged time went into other teams' work (A20).
 */
export default function EpicProgressTiles({ report }: { report: EpicProgressReport }) {
  const { t } = useTranslation();
  const { asOf } = report;
  const spi = asOf.spi;
  const cpi = asOf.cpi;
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, sm: 3, lg: 4 }} spacing="md">
      <ReportTile label={t("reports.epicProgress.tile.pv")} value={formatMd(asOf.pv)} />
      <ReportTile label={t("reports.epicProgress.tile.ev")} value={formatMd(asOf.ev)} />
      <ReportTile label={t("reports.epicProgress.tile.ac")} value={formatMd(asOf.ac)} />
      <ReportTile label={t("reports.epicProgress.tile.sv")} value={formatSignedMd(asOf.sv)} />
      <ReportTile label={t("reports.epicProgress.tile.cv")} value={formatSignedMd(asOf.cv)} />
      <ReportTile
        label={t("reports.epicProgress.tile.spi")}
        value={spi == null ? MISSING : formatIndex(spi)}
        hint={spi == null ? t("reports.epicProgress.spiNone") : t(`reports.epicProgress.spi.${indexVerdict(spi)}`)}
      />
      <ReportTile
        label={t("reports.epicProgress.tile.cpi")}
        value={cpi == null ? MISSING : formatIndex(cpi)}
        hint={cpi == null ? t("reports.epicProgress.cpiNone") : t(`reports.epicProgress.cpi.${indexVerdict(cpi)}`)}
      />
      {report.level === "TEAM" && (
        <ReportTile
          label={t("reports.epicProgress.tile.foreign")}
          value={report.foreignWorkShare == null ? MISSING : formatPercent(report.foreignWorkShare)}
          hint={report.foreignWorkShare == null ? t("reports.epicProgress.foreignNone") : t("reports.epicProgress.foreignHint")}
        />
      )}
    </SimpleGrid>
  );
}
