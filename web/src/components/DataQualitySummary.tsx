import { useTranslation } from "react-i18next";
import { Anchor, Box, Group, Paper, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import type { DataQualityReport } from "../api/reports";
import { cardAnchor } from "../utils/dataQualityReport";
import { formatPercent } from "../utils/reportFormat";
import type { CardId } from "./DataQualityCard";

interface Tile {
  card: CardId;
  label: string;
  value: string;
  hint?: string;
}

/** Scrolls to a card and moves focus there (keyboard users land in it), without writing a hash into the report's URL. */
function goToCard(card: CardId) {
  const target = document.getElementById(cardAnchor(card));
  target?.scrollIntoView?.({ block: "start" });
  target?.focus({ preventScroll: true });
}

function SummaryTile({ tile }: { tile: Tile }) {
  return (
    <Paper withBorder p="sm">
      <Stack gap={2}>
        <Anchor
          size="xs"
          href={`#${cardAnchor(tile.card)}`}
          onClick={(event) => {
            event.preventDefault();
            goToCard(tile.card);
          }}
        >
          {tile.label}
        </Anchor>
        <Text fz={24} fw={700} lh={1.2}>
          {tile.value}
        </Text>
        {tile.hint && (
          <Text size="xs" c="dimmed">
            {tile.hint}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

/** One captioned row of tiles, each linking to the card that lists the cases. */
function TileGroup({ caption, tiles }: { caption: string; tiles: ReadonlyArray<Tile> }) {
  return (
    <Stack gap="xs">
      <Title order={4} size="h6">
        {caption}
      </Title>
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
        {tiles.map((tile) => (
          <SummaryTile key={tile.card + tile.label} tile={tile} />
        ))}
      </SimpleGrid>
    </Stack>
  );
}

/**
 * The overview: the populations the findings were counted over, then the headline counts — worklog
 * coverage and late logging, the missing data, the drift and the configuration findings — each linking
 * to its card. The counts are the same numbers the cards state (a capped list's `total`, not its length).
 */
export default function DataQualitySummary({ report }: { report: DataQualityReport }) {
  const { t } = useTranslation();
  const { populations, worklogCoverage, lateLogging, missing } = report;
  const doneOpen = (finding: { done: number; open: number }) =>
    t("reports.dataQuality.summary.doneOpen", { done: finding.done, open: finding.open });
  const populationItems: [string, number][] = [
    [t("reports.dataQuality.summary.populations.doneTasks"), populations.doneTasks],
    [t("reports.dataQuality.summary.populations.openStartedTasks"), populations.openStartedTasks],
    [t("reports.dataQuality.summary.populations.epics"), populations.epics],
    [t("reports.dataQuality.summary.populations.worklogs"), populations.worklogs],
  ];
  return (
    <Stack gap="md">
      <Group gap="lg" role="group" aria-label={t("reports.dataQuality.summary.populationsLabel")}>
        {populationItems.map(([label, value]) => (
          <Box key={label}>
            <Text size="xs" c="dimmed">
              {label}
            </Text>
            <Text fw={600}>{value}</Text>
          </Box>
        ))}
      </Group>
      <Stack gap="md" role="group" aria-label={t("reports.dataQuality.summary.tilesLabel")}>
        <TileGroup
          caption={t("reports.dataQuality.summary.groupLogging")}
          tiles={[
            {
              card: "coverage",
              label: t("reports.dataQuality.cards.coverage.title"),
              value: worklogCoverage.coverage === null ? "—" : formatPercent(worklogCoverage.coverage),
              hint:
                worklogCoverage.coverage === null
                  ? t("reports.dataQuality.summary.coverageNone")
                  : t("reports.dataQuality.summary.coverageHint", {
                      withWorklogs: worklogCoverage.withWorklogs,
                      done: worklogCoverage.doneTasks,
                    }),
            },
            {
              card: "late",
              label: t("reports.dataQuality.stat.over1"),
              value: String(lateLogging.over1Day),
              hint: t("reports.dataQuality.summary.lateHint", { count: lateLogging.measurable }),
            },
            {
              card: "late",
              label: t("reports.dataQuality.stat.over7"),
              value: String(lateLogging.over7Days),
              hint: t("reports.dataQuality.summary.lateHint", { count: lateLogging.measurable }),
            },
            {
              card: "authors",
              label: t("reports.dataQuality.cards.authors.title"),
              value: String(report.authorsWithoutTeam.total),
            },
          ]}
        />
        <TileGroup
          caption={t("reports.dataQuality.summary.groupMissing")}
          tiles={[
            {
              card: "noEstimate",
              label: t("reports.dataQuality.cards.noEstimate.title"),
              value: String(missing.noEstimate.total),
              hint: doneOpen(missing.noEstimate),
            },
            {
              card: "noEpic",
              label: t("reports.dataQuality.cards.noEpic.title"),
              value: String(missing.noEpic.total),
              hint: doneOpen(missing.noEpic),
            },
            {
              card: "noWorkCategory",
              label: t("reports.dataQuality.cards.noWorkCategory.title"),
              value: missing.workCategoryConfigured ? String(missing.noWorkCategory.total) : "—",
              hint: missing.workCategoryConfigured
                ? doneOpen(missing.noWorkCategory)
                : t("reports.dataQuality.summary.notConfigured"),
            },
            {
              card: "unassigned",
              label: t("reports.dataQuality.cards.unassigned.title"),
              value: String(missing.unassigned.total),
              hint: t("reports.dataQuality.summary.doneOnly", { count: missing.unassigned.done }),
            },
            {
              card: "epicsNoEstimate",
              label: t("reports.dataQuality.cards.epicsNoEstimate.title"),
              value: String(missing.epicsWithoutEstimate.total),
              hint: t("reports.dataQuality.summary.epicsHint"),
            },
            {
              card: "epicsNoDates",
              label: t("reports.dataQuality.cards.epicsNoDates.title"),
              value: String(missing.epicsWithoutDates.total),
              hint: t("reports.dataQuality.summary.epicsHint"),
            },
            {
              card: "epicsHorizon",
              label: t("reports.dataQuality.cards.epicsHorizon.title"),
              value: String(missing.epicsOutsidePvHorizon.total),
              hint: t("reports.dataQuality.summary.epicsHint"),
            },
          ]}
        />
        <TileGroup
          caption={t("reports.dataQuality.summary.groupSprint")}
          tiles={[
            {
              card: "outsideSprint",
              label: t("reports.dataQuality.cards.outsideSprint.title"),
              value: String(report.outsideSprint.total),
              hint: t("reports.dataQuality.summary.doneOnly", { count: report.outsideSprint.done }),
            },
            {
              card: "crossDomain",
              label: t("reports.dataQuality.cards.crossDomain.title"),
              value: String(report.crossDomain.total),
              hint: t("reports.dataQuality.summary.doneOnly", { count: report.crossDomain.done }),
            },
          ]}
        />
        <TileGroup
          caption={t("reports.dataQuality.summary.groupDrift")}
          tiles={[
            {
              card: "snapshotDrift",
              label: t("reports.dataQuality.cards.snapshotDrift.title"),
              value: String(report.snapshotDrift.total),
              hint: t("reports.dataQuality.summary.sprintFigures"),
            },
            {
              card: "epicDrift",
              label: t("reports.dataQuality.cards.epicDrift.title"),
              value: String(report.epicDrift.total),
              hint: t("reports.dataQuality.summary.epicsHint"),
            },
          ]}
        />
        <TileGroup
          caption={t("reports.dataQuality.summary.groupConfig")}
          tiles={[
            {
              card: "unmappedStatuses",
              label: t("reports.dataQuality.cards.unmappedStatuses.title"),
              value: String(report.unmappedStatuses.total),
              hint: t("reports.dataQuality.summary.statusesHint"),
            },
            {
              card: "unmappedBoards",
              label: t("reports.dataQuality.cards.unmappedBoards.title"),
              value: String(report.unmappedBoards.total),
              hint:
                report.unmappedBoards.unattributedDoneTasks > 0
                  ? t("reports.dataQuality.summary.boardsHintResidual", { count: report.unmappedBoards.unattributedDoneTasks })
                  : t("reports.dataQuality.summary.boardsHint"),
            },
            {
              card: "domainsNoOwner",
              label: t("reports.dataQuality.cards.domainsNoOwner.title"),
              value: String(report.domainsWithoutOwner.total),
              hint: t("reports.dataQuality.summary.domainsHint"),
            },
            {
              card: "deriveWarnings",
              label: t("reports.dataQuality.cards.deriveWarnings.title"),
              value: String(report.deriveWarnings.length),
              hint: t("reports.dataQuality.summary.warningsHint"),
            },
          ]}
        />
      </Stack>
    </Stack>
  );
}
