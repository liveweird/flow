import type { HomeFigure, HomeOverview, HomePrevious, HomeTeam, HomeWip } from "../api/home";
import { COMMITMENTS_UNIT, CONSISTENCY_UNIT } from "./reportFixtures";

/** A current-window figure; `numerator` is the PRED items-within count. */
function figure(value: number | null, n: number, extra: Partial<HomeFigure> = {}): HomeFigure {
  return { value, n, hidden: false, numerator: null, previous: null, ...extra };
}

/** The same figure over the previous window, with the server's verdict and the difference (current − previous). */
export function previous(value: number | null, n: number, verdict: HomePrevious["verdict"], difference: number | null, hidden = false): HomePrevious {
  return { value, n, hidden, verdict, difference };
}

/** A figure under the minimum sample: no value. */
const HIDDEN = figure(null, 2, { hidden: true, previous: previous(null, 0, null, null) });

const wip = (count: number, p50: number | null, p85: number | null, p95: number | null, hidden = false): HomeWip => ({
  count,
  hidden,
  p50,
  p85,
  p95,
});

/** Fourteen Monday-start weeks from 2026-06-29, the delivery of one team (zero weeks included). */
function weeks(scale: number) {
  return Array.from({ length: 14 }, (_, index) => {
    const start = new Date(Date.UTC(2026, 5, 29 + index * 7));
    return {
      bucketStart: start.toISOString().slice(0, 10),
      deliveredMd: index % 4 === 0 ? 0 : index * scale,
      deliveredItems: index % 4 === 0 ? 0 : index,
    };
  });
}

const ALPHA: HomeTeam = {
  teamId: 1,
  name: "Alpha",
  lastSprint: {
    sprintId: 12,
    name: "Alpha 2",
    startAt: Date.UTC(2026, 8, 1),
    completedAt: Date.UTC(2026, 8, 10),
    capacityMd: 22,
    capacitySource: "CALENDAR",
    initialMd: 20,
    initialItems: 8,
    finalMd: 24.5,
    finalItems: 10,
    deliveredMd: 18,
    deliveredItems: 7,
    snapshot: CONSISTENCY_UNIT.sprints[0].snapshot,
    drift: true,
  },
  granularity: {
    estimateMd: figure(3.5, 12, { previous: previous(4, 10, "BETTER", -0.5) }),
    cycleWorkingDays: figure(5.5, 11, { previous: previous(4, 9, "WORSE", 1.5) }),
    costMd: figure(4, 9, { previous: previous(4.1, 8, null, -0.1) }),
  },
  accuracy: {
    tasks: {
      pred10: figure(0.3, 10, { numerator: 3, previous: previous(0.2, 10, "BETTER", 0.1) }),
      pred25: figure(0.6, 10, { numerator: 6, previous: previous(0.7, 10, "WORSE", -0.1) }),
      pred50: figure(0.8, 10, { numerator: 8, previous: previous(0.8, 10, null, 0) }),
    },
    epics: {
      pred10: figure(null, 3, { hidden: true, numerator: 1, previous: previous(null, 0, null, null) }),
      pred25: figure(null, 3, { hidden: true, numerator: 2, previous: previous(null, 0, null, null) }),
      pred50: figure(null, 3, { hidden: true, numerator: 3, previous: previous(null, 0, null, null) }),
    },
  },
  wipEpics: wip(2, null, null, null, true),
  wipTasks: wip(6, 4, 9, 12),
  throughput: {
    weeks: weeks(2.5),
    deliveredMd: figure(40.5, 14, { previous: previous(30, 12, "BETTER", 10.5) }),
    deliveredItems: figure(14, 14, { previous: previous(12, 12, "BETTER", 2) }),
    cycleP50: figure(5.5, 11, { previous: previous(4, 9, "WORSE", 1.5) }),
    cycleP85: figure(9, 11, { previous: previous(9, 9, null, 0) }),
  },
  quality: {
    tasksNoEstimate: figure(3, 3, { previous: previous(5, 5, "BETTER", -2) }),
    tasksUnassigned: figure(1, 1, { previous: previous(1, 1, null, 0) }),
    tasksEstimatedLate: figure(2, 2, { previous: previous(0, 0, "WORSE", 2) }),
    epicsNoEstimate: figure(1, 1),
    epicsNoDates: figure(0, 0),
    epicsEstimateGap: figure(2, 2),
  },
};

const BETA: HomeTeam = {
  teamId: 2,
  name: "Beta",
  lastSprint: {
    sprintId: 21,
    name: "Beta 1",
    startAt: null,
    completedAt: Date.UTC(2026, 8, 1),
    capacityMd: null,
    capacitySource: null,
    initialMd: 12,
    initialItems: 5,
    finalMd: 12,
    finalItems: 5,
    deliveredMd: 10,
    deliveredItems: 4,
    snapshot: null,
    drift: false,
  },
  granularity: {
    estimateMd: figure(2, 8, { previous: previous(2, 7, null, 0) }),
    cycleWorkingDays: figure(3, 8, { previous: previous(null, 2, null, null, true) }),
    costMd: figure(2.5, 6, { previous: previous(3, 6, "BETTER", -0.5) }),
  },
  accuracy: {
    tasks: {
      pred10: figure(0.5, 8, { numerator: 4, previous: previous(0.5, 7, null, 0) }),
      pred25: figure(0.75, 8, { numerator: 6, previous: previous(0.5, 7, "BETTER", 0.25) }),
      pred50: figure(1, 8, { numerator: 8, previous: previous(0.9, 7, "BETTER", 0.1) }),
    },
    epics: {
      pred10: figure(0.4, 5, { numerator: 2, previous: previous(0.4, 5, null, 0) }),
      pred25: figure(0.6, 5, { numerator: 3, previous: previous(0.8, 5, "WORSE", -0.2) }),
      pred50: figure(0.8, 5, { numerator: 4, previous: previous(0.8, 5, null, 0) }),
    },
  },
  wipEpics: wip(0, null, null, null),
  wipTasks: wip(3, 2, 5, 7),
  throughput: {
    weeks: weeks(1.5),
    deliveredMd: figure(22, 8, { previous: previous(25, 9, "WORSE", -3) }),
    deliveredItems: figure(8, 8, { previous: previous(9, 9, "WORSE", -1) }),
    cycleP50: figure(3, 8, { previous: previous(null, 2, null, null, true) }),
    cycleP85: figure(6, 8, { previous: previous(null, 2, null, null, true) }),
  },
  quality: {
    tasksNoEstimate: figure(0, 0, { previous: previous(0, 0, null, 0) }),
    tasksUnassigned: figure(0, 0, { previous: previous(1, 1, "BETTER", -1) }),
    tasksEstimatedLate: figure(1, 1, { previous: previous(1, 1, null, 0) }),
    epicsNoEstimate: figure(0, 0),
    epicsNoDates: figure(1, 1),
    epicsEstimateGap: figure(0, 0),
  },
};

/** A team with nothing in the window: no closed sprint, every sample under the minimum. */
const GAMMA: HomeTeam = {
  teamId: 3,
  name: "Gamma",
  lastSprint: null,
  granularity: { estimateMd: HIDDEN, cycleWorkingDays: HIDDEN, costMd: HIDDEN },
  accuracy: {
    tasks: { pred10: HIDDEN, pred25: HIDDEN, pred50: HIDDEN },
    epics: { pred10: HIDDEN, pred25: HIDDEN, pred50: HIDDEN },
  },
  wipEpics: wip(0, null, null, null),
  wipTasks: wip(1, null, null, null, true),
  throughput: {
    weeks: weeks(0).map((week) => ({ ...week, deliveredMd: 0, deliveredItems: 0 })),
    deliveredMd: figure(0, 0, { previous: previous(0, 0, null, 0) }),
    deliveredItems: figure(0, 0, { previous: previous(0, 0, null, 0) }),
    cycleP50: HIDDEN,
    cycleP85: HIDDEN,
  },
  quality: {
    tasksNoEstimate: figure(0, 0, { previous: previous(0, 0, null, 0) }),
    tasksUnassigned: figure(0, 0, { previous: previous(0, 0, null, 0) }),
    tasksEstimatedLate: figure(0, 0, { previous: previous(0, 0, null, 0) }),
    epicsNoEstimate: figure(0, 0),
    epicsNoDates: figure(0, 0),
    epicsEstimateGap: figure(0, 0),
  },
};

/** The overview as the server answers it: three active teams (Gamma with nothing in the window), one connection, the 90-day window of the meta. */
export const HOME: HomeOverview = {
  meta: { ...COMMITMENTS_UNIT.meta, from: "2026-07-02", to: "2026-09-29" },
  window: { from: "2026-07-02", to: "2026-09-29" },
  previousWindow: { from: "2026-04-03", to: "2026-07-01" },
  timeZone: "Europe/Warsaw",
  epicEstimateGapTolerancePercent: 25,
  freshness: [{ connectionId: 1, name: "Stub", lastSyncAt: Date.UTC(2026, 8, 29, 10, 15), lastDeriveAt: Date.UTC(2026, 8, 29, 10, 30) }],
  teams: [ALPHA, BETA, GAMMA],
  unassigned: { doneTasks: 2, openTasks: 1, epics: 0 },
};

/** Nothing derived yet: an empty overview with a null `derivedAt`. */
export const HOME_NOT_DERIVED: HomeOverview = {
  ...HOME,
  meta: { ...HOME.meta, derivedAt: null },
  freshness: [],
  teams: [],
  unassigned: { doneTasks: 0, openTasks: 0, epics: 0 },
};
