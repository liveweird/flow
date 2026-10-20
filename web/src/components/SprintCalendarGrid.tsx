import { memo, useCallback, useId, useMemo } from "react";
import { Badge, Checkbox, Group, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  AVAILABILITY_MAX,
  COEFFICIENT_MAX,
  dayCapacityTotals,
  isCounted,
  withCell,
  withDayEnabled,
  withPersonCoefficient,
  withPersonEnabled,
  type CalendarDraft,
} from "../utils/capacity";
import { formatMd } from "../utils/reportFormat";
import CapacityNumberInput from "./CapacityNumberInput";
import ScrollRegion from "./ScrollRegion";
import classes from "./SprintCalendarGrid.module.css";

/** Table width the scroller guards: the name and coefficient columns plus one narrow column per day. */
const FIXED_COLUMNS_WIDTH = 290;
const DAY_COLUMN_WIDTH = 56;

type Person = CalendarDraft["people"][number];
type Day = CalendarDraft["days"][number];
/** The caller's draft setter: every edit is a function of the PREVIOUS draft, so two edits in one tick both land. */
type Update = (change: (draft: CalendarDraft) => CalendarDraft) => void;

// One formatter per language for the whole page: constructing an Intl.DateTimeFormat per header cell per render is the slow part.
const weekdayFormatters = new Map<string, Intl.DateTimeFormat>();
function weekdayInitial(language: string, iso: string): string {
  let formatter = weekdayFormatters.get(language);
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat(language, { weekday: "narrow", timeZone: "UTC" });
    weekdayFormatters.set(language, formatter);
  }
  return formatter.format(new Date(`${iso}T00:00:00Z`));
}

/** Why a day cannot be switched on: a public holiday, or an earlier sprint of the team already counts it (a holiday wins when both). */
type Lock = "holiday" | "claimed" | null;
const lockOf = (day: Day): Lock => (day.holiday ? "holiday" : day.claimedBy ? "claimed" : null);

/** A column that is not counted wears the off shade; a locked day additionally wears its own hatch (holiday: one diagonal, claimed: the other). */
const offClass = (base: string, counted: boolean, lock: Lock = null) =>
  `${base}${counted ? "" : ` ${classes.offDay}`}${lock === "holiday" ? ` ${classes.holidayDay}` : ""}${lock === "claimed" ? ` ${classes.claimedDay}` : ""}`;

function LegendItem({ swatch, label }: { swatch: string; label: string }) {
  return (
    <Group gap={6} wrap="nowrap">
      <span className={`${classes.legendSwatch} ${swatch}`} aria-hidden />
      <Text size="xs" c="dimmed">
        {label}
      </Text>
    </Group>
  );
}

const DayHeader = memo(function DayHeader({
  day,
  canEdit,
  onToggle,
}: {
  day: Day;
  canEdit: boolean;
  onToggle: (day: string, enabled: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const lock = lockOf(day);
  const holiday = day.holiday ?? null;
  const claimedName = day.claimedBy?.sprintName ?? null;
  let title = day.day;
  let aria = t("capacity.grid.countDayAria", { day: day.day });
  if (lock === "holiday") {
    title = t("capacity.grid.holidayTooltip", { name: holiday });
    aria = t("capacity.grid.holidayDayAria", { day: day.day, name: holiday });
  } else if (lock === "claimed") {
    title = t("capacity.grid.claimedTooltip", { name: claimedName });
    aria = t("capacity.grid.claimedDayAria", { day: day.day, name: claimedName });
  }
  return (
    <th scope="col" className={offClass(classes.dayHeader, isCounted(day), lock)} title={title}>
      {Number(day.day.slice(8))}
      <br />
      {/* The checkbox below names the day in full; the initial is decoration. */}
      <span aria-hidden>{weekdayInitial(i18n.language, day.day)}</span>
      <br />
      <Checkbox
        size="xs"
        style={{ display: "inline-flex" }}
        checked={isCounted(day)}
        // A public holiday or a day an earlier sprint holds is locked off: unticked and disabled, named so the lock is explained.
        disabled={!canEdit || lock !== null}
        aria-label={aria}
        onChange={(event) => onToggle(day.day, event.currentTarget.checked)}
      />
    </th>
  );
});

const AvailabilityCell = memo(function AvailabilityCell({
  name,
  day,
  dayEnabled,
  lock,
  value,
  disabled,
  onCommit,
}: {
  name: string;
  day: string;
  dayEnabled: boolean;
  lock: Lock;
  value: number;
  disabled: boolean;
  onCommit: (day: string, value: number) => void;
}) {
  const { t } = useTranslation();
  const commit = useCallback((next: number) => onCommit(day, next), [onCommit, day]);
  return (
    <td className={offClass(classes.dayCell, dayEnabled, lock)}>
      <CapacityNumberInput
        size="xs"
        min={0}
        max={AVAILABILITY_MAX}
        step={0.25}
        hideControls
        value={value}
        disabled={disabled}
        aria-label={t("capacity.grid.cellAria", { name, day })}
        onCommit={commit}
      />
    </td>
  );
});

const PersonRow = memo(function PersonRow({
  person,
  days,
  canEdit,
  showCoefficient,
  idPrefix,
  update,
}: {
  person: Person;
  days: ReadonlyArray<Day>;
  canEdit: boolean;
  showCoefficient: boolean;
  idPrefix: string;
  update: Update;
}) {
  const { t } = useTranslation();
  const { accountId, displayName } = person;
  const nameId = `${idPrefix}-name-${accountId}`;
  // Cells indexed by day: one Map per person, rebuilt only when this person's cells change.
  const availabilityByDay = useMemo(() => new Map(person.cells.map((c) => [c.day, c.availability])), [person.cells]);
  const setEnabled = useCallback((enabled: boolean) => update((prev) => withPersonEnabled(prev, accountId, enabled)), [update, accountId]);
  const setCoefficient = useCallback((value: number) => update((prev) => withPersonCoefficient(prev, accountId, value)), [update, accountId]);
  const setCell = useCallback((day: string, value: number) => update((prev) => withCell(prev, accountId, day, value)), [update, accountId]);

  return (
    <tr className={person.enabled ? undefined : classes.offRow}>
      <th className={classes.nameCell} scope="row" aria-labelledby={nameId}>
        <Group gap="xs" wrap="nowrap" align="flex-start">
          <Checkbox
            size="xs"
            mt={2}
            checked={person.enabled}
            disabled={!canEdit}
            aria-label={t("capacity.grid.countPersonAria", { name: displayName })}
            onChange={(event) => setEnabled(event.currentTarget.checked)}
          />
          <Stack gap={2} style={{ minWidth: 0 }}>
            <Text id={nameId} size="sm" fw={500} component="span">
              {displayName}
            </Text>
            {!person.member && (
              <Badge size="xs" color="gray" w="fit-content">
                {t("capacity.grid.notMember")}
              </Badge>
            )}
          </Stack>
        </Group>
      </th>
      {showCoefficient && person.coefficient !== null && (
        <td className={classes.coefficientCell}>
          <CapacityNumberInput
            size="xs"
            w={68}
            min={0}
            max={COEFFICIENT_MAX}
            step={0.1}
            value={person.coefficient}
            disabled={!person.enabled}
            aria-label={t("capacity.grid.coefficientAria", { name: displayName })}
            onCommit={setCoefficient}
          />
        </td>
      )}
      {days.map((day) => (
        <AvailabilityCell
          key={day.day}
          name={displayName}
          day={day.day}
          dayEnabled={isCounted(day)}
          lock={lockOf(day)}
          value={availabilityByDay.get(day.day) ?? 0}
          disabled={!canEdit || !person.enabled || !isCounted(day)}
          onCommit={setCell}
        />
      ))}
    </tr>
  );
});

/**
 * The capacity grid for one sprint: rows = people, columns = the sprint's calendar days (ported from
 * Lettuce's `DaysOffMonthGrid` — the real `<table>`, the sticky name column, the legend — with
 * controlled inputs instead of read-only fills). Whole people and whole days are turned off with a
 * checkbox; a cell is 0..1 man-days, a person's coefficient 0..2. Off columns and rows are shaded
 * from the DATA (`enabled === false`), never from the weekday: the server decides the defaults. A public
 * holiday (`day.holiday`, the dictionary's name) and a day an earlier sprint of the team already holds
 * (`day.claimedBy`, A42) cannot be switched on: the column is hatched (each its own way), the checkbox
 * unticked and disabled, and the name is the tooltip and part of the checkbox's label.
 * A pure renderer — the caller owns the draft and the permission (`canEdit`); every edit goes up
 * through `onChange` as a function of the previous draft, and each row and cell is memoised on
 * its own slice (an edit replaces only the person it touches), so a keystroke re-renders one cell.
 */
export default function SprintCalendarGrid({ draft, canEdit, onChange }: { draft: CalendarDraft; canEdit: boolean; onChange: Update }) {
  const { t } = useTranslation();
  const idPrefix = useId();
  const tableAria = t("capacity.grid.tableAria");
  const toggleDay = useCallback((day: string, enabled: boolean) => onChange((prev) => withDayEnabled(prev, day, enabled)), [onChange]);
  const totals = useMemo(() => dayCapacityTotals(draft.people), [draft.people]);
  // The server withholds per-person coefficients from a caller who cannot edit: no column at all, not a column of blanks.
  const hasHoliday = draft.days.some((day) => lockOf(day) === "holiday");
  const hasClaimed = draft.days.some((day) => lockOf(day) === "claimed");
  const showCoefficients = canEdit && draft.people.every((person) => person.coefficient !== null);

  return (
    <Stack gap="sm">
      <ScrollRegion label={tableAria} minWidth={FIXED_COLUMNS_WIDTH + draft.days.length * DAY_COLUMN_WIDTH}>
        <table className={classes.grid} aria-label={tableAria}>
          <thead>
            <tr>
              <th className={classes.nameCell} scope="col">
                {t("capacity.grid.personColumn")}
              </th>
              {showCoefficients && (
                <th className={classes.coefficientCell} scope="col">
                  {t("capacity.grid.coefficientColumn")}
                </th>
              )}
              {draft.days.map((day) => (
                <DayHeader key={day.day} day={day} canEdit={canEdit} onToggle={toggleDay} />
              ))}
            </tr>
          </thead>
          <tbody>
            {draft.people.map((person) => (
              <PersonRow
                key={person.accountId}
                person={person}
                days={draft.days}
                canEdit={canEdit}
                showCoefficient={showCoefficients}
                idPrefix={idPrefix}
                update={onChange}
              />
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th className={classes.nameCell} scope="row">
                {t("capacity.grid.footer")}
              </th>
              {showCoefficients && <td />}
              {draft.days.map((day) => (
                <td key={day.day} className={offClass(classes.footerCell, isCounted(day), lockOf(day))}>
                  {isCounted(day) ? formatMd(totals.get(day.day) ?? 0) : "–"}
                </td>
              ))}
            </tr>
          </tfoot>
        </table>
      </ScrollRegion>
      <Group gap="lg" wrap="wrap">
        <LegendItem swatch={classes.offDay} label={t("capacity.grid.legendOff")} />
        {hasHoliday && <LegendItem swatch={`${classes.offDay} ${classes.holidayDay}`} label={t("capacity.grid.legendHoliday")} />}
        {hasClaimed && <LegendItem swatch={`${classes.offDay} ${classes.claimedDay}`} label={t("capacity.grid.legendClaimed")} />}
      </Group>
    </Stack>
  );
}
