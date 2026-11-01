import { Paper, Stack, Text } from "@mantine/core";
import DrillFigure from "./DrillFigure";
import KpiFigure from "./KpiFigure";

/** What makes a tile's figure a button to the items behind it (`DrillFigure`): its accessible name, how many items it stands for, the handler. */
export interface TileDrill {
  label: string;
  count: number;
  disabled?: boolean;
  onOpen: (trigger: HTMLElement) => void;
}

/**
 * One headline figure: a label, the value, and the sentence that says what it means (or why it is missing).
 * `secondary` is one small line for the same figure on the other basis ("Since start: 0.94").
 */
export default function ReportTile({
  label,
  value,
  hint,
  secondary,
  drill,
}: {
  label: string;
  value: string;
  hint?: string;
  secondary?: string;
  /** The figure opens the items behind it (a pane the page mounts); absent, it is plain text. */
  drill?: TileDrill;
}) {
  return (
    <Paper withBorder p="md" role="group" aria-label={label}>
      <Stack gap={4}>
        <Text size="xs" c="dimmed">
          {label}
        </Text>
        <KpiFigure>
          {drill === undefined ? (
            value
          ) : (
            <DrillFigure label={drill.label} count={drill.count} disabled={drill.disabled} onOpen={drill.onOpen}>
              {value}
            </DrillFigure>
          )}
        </KpiFigure>
        {hint && (
          <Text size="xs" c="dimmed">
            {hint}
          </Text>
        )}
        {secondary && (
          <Text size="xs" c="dimmed">
            {secondary}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}
