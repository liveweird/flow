import { useTranslation } from "react-i18next";
import { SimpleGrid } from "@mantine/core";
import type { EpicProgressReport } from "../api/reports";
import { figuresByBasis, indexVerdict } from "../utils/epicProgressReport";
import { formatIndex, formatMd, formatPercent, formatSignedMd } from "../utils/reportFormat";
import ReportTile from "./ReportTile";

const MISSING = "—";

const index = (value: number | null) => (value == null ? MISSING : formatIndex(value));

/**
 * The headline: PV, EV, AC, the two variances (signed MD) and the two indices (two decimals, a
 * dash plus the reason when there is no ratio), on the basis the report shows — the period or since
 * the start — with the other basis as one small line under each figure ("Since start: 0.94"). An
 * index is never coloured red or green: which side of 1 it sits on is said in words — under 1 is a
 * fact about the plan, not a blocking failure. A period index is hidden (a dash and "too little
 * planned/spent in this period") while its denominator is under one man-day, because earned value
 * arrives in lumps. At TEAM level the foreign-work share sits beside CPI — over the same window as
 * the CPI shown — because a team's cost index means little without knowing how much of the logged
 * time went into other teams' work (A20).
 */
export default function EpicProgressTiles({ report }: { report: EpicProgressReport }) {
  const { t } = useTranslation();
  const period = report.basis === "PERIOD";
  const { primary, other } = figuresByBasis(report);
  const secondary = (value: string) => t(period ? "reports.epicProgress.secondary.sinceStart" : "reports.epicProgress.secondary.thisPeriod", { value });
  const foreign = period ? report.foreignWorkSharePeriod : report.foreignWorkShare;
  const foreignOther = period ? report.foreignWorkShare : report.foreignWorkSharePeriod;
  const percent = (value: number | null | undefined) => (value == null ? MISSING : formatPercent(value));
  const noSpi = period ? "reports.epicProgress.spiThin" : "reports.epicProgress.spiNone";
  const noCpi = period ? "reports.epicProgress.cpiThin" : "reports.epicProgress.cpiNone";
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, sm: 3, lg: 4 }} spacing="md">
      <ReportTile label={t("reports.epicProgress.tile.pv")} value={formatMd(primary.pv)} secondary={secondary(formatMd(other.pv))} />
      <ReportTile label={t("reports.epicProgress.tile.ev")} value={formatMd(primary.ev)} secondary={secondary(formatMd(other.ev))} />
      <ReportTile label={t("reports.epicProgress.tile.ac")} value={formatMd(primary.ac)} secondary={secondary(formatMd(other.ac))} />
      <ReportTile label={t("reports.epicProgress.tile.sv")} value={formatSignedMd(primary.sv)} secondary={secondary(formatSignedMd(other.sv))} />
      <ReportTile label={t("reports.epicProgress.tile.cv")} value={formatSignedMd(primary.cv)} secondary={secondary(formatSignedMd(other.cv))} />
      <ReportTile
        label={t("reports.epicProgress.tile.spi")}
        value={index(primary.spi)}
        hint={primary.spi == null ? t(noSpi) : t(`reports.epicProgress.spi.${indexVerdict(primary.spi)}`)}
        secondary={secondary(index(other.spi))}
      />
      <ReportTile
        label={t("reports.epicProgress.tile.cpi")}
        value={index(primary.cpi)}
        hint={primary.cpi == null ? t(noCpi) : t(`reports.epicProgress.cpi.${indexVerdict(primary.cpi)}`)}
        secondary={secondary(index(other.cpi))}
      />
      {report.level === "TEAM" && (
        <ReportTile
          label={t("reports.epicProgress.tile.foreign")}
          value={percent(foreign)}
          hint={foreign == null ? t("reports.epicProgress.foreignNone") : t(period ? "reports.epicProgress.foreignHintPeriod" : "reports.epicProgress.foreignHint")}
          secondary={secondary(percent(foreignOther))}
        />
      )}
    </SimpleGrid>
  );
}
