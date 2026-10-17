import { Paper, Stack, Text } from "@mantine/core";

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
        <Text fz={28} fw={700} lh={1.2}>
          {value}
        </Text>
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
