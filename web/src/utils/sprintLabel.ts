import { formatDate } from "./formatDate";

/** What a sprint label needs of a sprint: its name and its dates (`endAt` is absent where the source does not carry it). */
export interface LabelledSprint {
  name: string;
  startAt: number | null;
  endAt?: number | null;
  completeAt: number | null;
}

/**
 * The ONE sprint picker label, `name · from → to`, the dates read in the configured `timeZone`; a sprint without a start
 * date is just its name, one without an end shows `—` there. The end is the planned `endAt`, else `completeAt`.
 */
export function sprintLabel(sprint: LabelledSprint, timeZone: string): string {
  if (sprint.startAt === null) return sprint.name;
  const end = sprint.endAt ?? sprint.completeAt;
  // The last day is the one holding the final millisecond (`end - 1`) — the grid's rule, so a sprint ending at midnight does not show the day after it.
  return `${sprint.name} · ${formatDate(sprint.startAt, "—", timeZone)} → ${formatDate(end === null ? null : end - 1, "—", timeZone)}`;
}
