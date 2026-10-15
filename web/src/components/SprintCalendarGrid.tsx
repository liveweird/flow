import { useId } from "react";
import { Badge, Checkbox, Group, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  AVAILABILITY_MAX,
  COEFFICIENT_MAX,
  dayCapacityMd,
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

/**
 * The capacity grid for one sprint: rows = people, columns = the sprint's calendar days (ported from
 * Lettuce's `DaysOffMonthGrid` — the real `<table>`, the sticky name column, the legend — with
 * controlled inputs instead of read-only fills). Whole people and whole days are turned off with a
 * checkbox; a cell is 0..1 man-days, a person's coefficient 0..2. Off columns and rows are shaded
 * from the DATA (`enabled === false`), never from the weekday: the server decides the defaults.
 * A pure renderer — the caller owns the draft and the permission (`canEdit`); every edit goes up
 * through `onChange` as a new draft.
 */
export default function SprintCalendarGrid({
  draft,
  canEdit,
  onChange,
}: {
  draft: CalendarDraft;
  canEdit: boolean;
  onChange: (draft: CalendarDraft) => void;
}) {
  const { t, i18n } = useTranslation();
  const idPrefix = useId();
  const weekdayInitial = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { weekday: "narrow", timeZone: "UTC" }).format(new Date(`${iso}T00:00:00Z`));
  const tableAria = t("capacity.grid.tableAria");
  // The server withholds per-person coefficients from a caller who cannot edit: no column at all, not a column of blanks.
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
                <th key={day.day} scope="col" className={`${classes.dayHeader}${day.enabled ? "" : ` ${classes.offDay}`}`} title={day.day}>
                  {Number(day.day.slice(8))}
                  <br />
                  {weekdayInitial(day.day)}
                  <br />
                  <Checkbox
                    size="xs"
                    style={{ display: "inline-flex" }}
                    checked={day.enabled}
                    disabled={!canEdit}
                    aria-label={t("capacity.grid.countDayAria", { day: day.day })}
                    onChange={(event) => onChange(withDayEnabled(draft, day.day, event.currentTarget.checked))}
                  />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {draft.people.map((person) => {
              const nameId = `${idPrefix}-name-${person.accountId}`;
              const cellByDay = new Map(person.cells.map((c) => [c.day, c.availability]));
              return (
                <tr key={person.accountId} className={person.enabled ? undefined : classes.offRow}>
                  <th className={classes.nameCell} scope="row" aria-labelledby={nameId}>
                    <Group gap="xs" wrap="nowrap" align="flex-start">
                      <Checkbox
                        size="xs"
                        mt={2}
                        checked={person.enabled}
                        disabled={!canEdit}
                        aria-label={t("capacity.grid.countPersonAria", { name: person.displayName })}
                        onChange={(event) => onChange(withPersonEnabled(draft, person.accountId, event.currentTarget.checked))}
                      />
                      <Stack gap={2} style={{ minWidth: 0 }}>
                        <Text id={nameId} size="sm" fw={500} component="span">
                          {person.displayName}
                        </Text>
                        {!person.member && (
                          <Badge size="xs" color="gray" w="fit-content">
                            {t("capacity.grid.notMember")}
                          </Badge>
                        )}
                      </Stack>
                    </Group>
                  </th>
                  {showCoefficients && person.coefficient !== null && (
                    <td className={classes.coefficientCell}>
                      <CapacityNumberInput
                        size="xs"
                        w={68}
                        min={0}
                        max={COEFFICIENT_MAX}
                        step={0.1}
                        value={person.coefficient}
                        disabled={!person.enabled}
                        aria-label={t("capacity.grid.coefficientAria", { name: person.displayName })}
                        onCommit={(value) => onChange(withPersonCoefficient(draft, person.accountId, value))}
                      />
                    </td>
                  )}
                  {draft.days.map((day) => (
                    <td key={day.day} className={`${classes.dayCell}${day.enabled ? "" : ` ${classes.offDay}`}`}>
                      <CapacityNumberInput
                        size="xs"
                        min={0}
                        max={AVAILABILITY_MAX}
                        step={0.25}
                        hideControls
                        value={cellByDay.get(day.day) ?? 0}
                        disabled={!canEdit || !person.enabled || !day.enabled}
                        aria-label={t("capacity.grid.cellAria", { name: person.displayName, day: day.day })}
                        onCommit={(value) => onChange(withCell(draft, person.accountId, day.day, value))}
                      />
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr>
              <th className={classes.nameCell} scope="row">
                {t("capacity.grid.footer")}
              </th>
              {showCoefficients && <td />}
              {draft.days.map((day) => (
                <td key={day.day} className={`${classes.footerCell}${day.enabled ? "" : ` ${classes.offDay}`}`}>
                  {day.enabled ? formatMd(dayCapacityMd(draft.people, day.day)) : "–"}
                </td>
              ))}
            </tr>
          </tfoot>
        </table>
      </ScrollRegion>
      <Group gap="lg" wrap="wrap">
        <LegendItem swatch={classes.offDay} label={t("capacity.grid.legendOff")} />
      </Group>
    </Stack>
  );
}
