import { Paper, Stack, Text } from "@mantine/core";
import KpiFigure from "./KpiFigure";

/**
 * One headline figure: a label, the value, and the sentence that says what it means (or why it is missing).
 * `secondary` is one small line for the same figure on the other basis ("Since start: 0.94").
 */
export default function ReportTile({
  label,
  value,
  hint,
  secondary,
}: {
  label: string;
  value: string;
  hint?: string;
  secondary?: string;
}) {
  return (
    <Paper withBorder p="md" role="group" aria-label={label}>
      <Stack gap={4}>
        <Text size="xs" c="dimmed">
          {label}
        </Text>
        <KpiFigure>{value}</KpiFigure>
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
