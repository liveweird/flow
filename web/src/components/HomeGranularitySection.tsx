import { useTranslation } from "react-i18next";
import { SimpleGrid } from "@mantine/core";
import type { HomeOverview } from "../api/home";
import { CHART_COLORS } from "../utils/chartColors";
import { overviewLink, sectionAnchor, teamFigureRows, windowFilter, type OpenDrill } from "../utils/homeOverview";
import { cycleTimePath } from "../utils/reportLinks";
import { formatDays, formatMd } from "../utils/reportFormat";
import HomeTeamBars from "./HomeTeamBars";
import HomeTile from "./HomeTile";

const ANCHOR = sectionAnchor("granularity");

/**
 * Section 2 — how big the work is: the median estimate, the median cycle time (working days) and the median logged cost of the
 * tasks each team finished in the window, three small multiples sharing one team order. A lower median is better (smaller
 * batches), so the arrow at the end of a bar is teal when it fell against the previous window.
 */
export default function HomeGranularitySection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const { teams } = overview;
  const common = { color: CHART_COLORS.estimate, withTrend: true, anchor: ANCHOR, onOpen: open };
  return (
    <HomeTile
      title={t("home.granularity.title")}
      to={overviewLink(cycleTimePath, windowFilter(overview))}
      caption={t("home.granularity.caption", {
        from: overview.window.from,
        to: overview.window.to,
        previousFrom: overview.previousWindow.from,
        previousTo: overview.previousWindow.to,
      })}
      drillAnchor={ANCHOR}
    >
      <SimpleGrid cols={{ base: 1, md: 3 }} spacing="lg">
        <HomeTeamBars
          {...common}
          title={t("home.granularity.estimate")}
          rows={teamFigureRows(teams, "TASK_ESTIMATE", (team) => team.granularity.estimateMd)}
          format={formatMd}
          valueHeader={t("home.granularity.estimateValue")}
        />
        <HomeTeamBars
          {...common}
          title={t("home.granularity.cycle")}
          rows={teamFigureRows(teams, "TASK_CYCLE", (team) => team.granularity.cycleWorkingDays)}
          format={formatDays}
          valueHeader={t("home.granularity.cycleValue")}
        />
        <HomeTeamBars
          {...common}
          title={t("home.granularity.cost")}
          rows={teamFigureRows(teams, "TASK_COST", (team) => team.granularity.costMd)}
          format={formatMd}
          valueHeader={t("home.granularity.costValue")}
        />
      </SimpleGrid>
    </HomeTile>
  );
}
