import { useTranslation } from "react-i18next";
import { SimpleGrid } from "@mantine/core";
import type { HomeOverview, HomeTeam, HomeWip } from "../api/home";
import { CHART_COLORS } from "../utils/chartColors";
import { overviewLink, sectionAnchor, wipRows, windowFilter, type OpenDrill } from "../utils/homeOverview";
import { AGING_KIND_EPICS, AGING_KIND_PARAM, agingWipPath } from "../utils/reportLinks";
import { formatDays } from "../utils/reportFormat";
import HomeTeamBars from "./HomeTeamBars";
import HomeTile from "./HomeTile";

type WipKind = "epics" | "tasks";

const PERCENTILES = ["p50", "p85", "p95"] as const;

/**
 * Sections 4 (epics) and 5 (tasks) — the work in progress, waiting or blocked right now, per team: how many items, and how old
 * they are (the 50th, 85th and 95th percentile of the age in working days since each started) as four small multiples, one
 * question each (never three shades of one hue in one chart). It is a snapshot of now, so no trend. A percentile is hidden under the
 * minimum sample; the count is a fact and never is. Both kinds link to Aging WIP (the epics to its Epics switch).
 */
export default function HomeWipSection({ overview, open, kind }: { overview: HomeOverview; open: OpenDrill; kind: WipKind }) {
  const { t } = useTranslation();
  const anchor = sectionAnchor(kind === "epics" ? "wipEpics" : "wipTasks");
  const view = kind === "epics" ? "WIP_EPICS" : "WIP_TASKS";
  const wipOf = (team: HomeTeam): HomeWip => (kind === "epics" ? team.wipEpics : team.wipTasks);
  const common = { color: CHART_COLORS.stageInProgress, anchor, onOpen: open, withTrend: false };
  return (
    <HomeTile
      title={t(`home.wip.${kind}.title`)}
      to={overviewLink(agingWipPath, windowFilter(overview), kind === "epics" ? { [AGING_KIND_PARAM]: AGING_KIND_EPICS } : {})}
      caption={t(`home.wip.${kind}.caption`)}
      drillAnchor={anchor}
    >
      <SimpleGrid cols={{ base: 1, sm: 2, xl: 4 }} spacing="lg">
        <HomeTeamBars
          {...common}
          title={t(`home.wip.${kind}.count`)}
          rows={wipRows(overview.teams, view, wipOf, (wip) => wip.count)}
          format={String}
          integer
          valueHeader={t(`home.wip.${kind}.count`)}
          valueIsItems
        />
        {PERCENTILES.map((percentile) => (
          <HomeTeamBars
            key={percentile}
            {...common}
            title={t(`home.wip.${kind}.${percentile}`)}
            rows={wipRows(overview.teams, view, wipOf, (wip) => wip[percentile])}
            format={formatDays}
            valueHeader={t(`home.wip.${kind}.${percentile}`)}
            itemsHeader={t(`home.wip.${kind}.itemsHeader`)}
          />
        ))}
      </SimpleGrid>
    </HomeTile>
  );
}
