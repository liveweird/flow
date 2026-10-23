import { Center, Stack, Text, ThemeIcon } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";

/**
 * The shared zero-rows body for list tables (icon + dimmed one-liner). Takes the Tabler icon
 * COMPONENT and sizes/strokes it itself, so every empty state draws the same glyph weight. `hint` is the optional next step
 * under the label (what to change or do), for an empty state that is more than the absence.
 */
export default function EmptyState({ icon: Icon, label, hint }: { icon: Icon; label: string; hint?: string }) {
  return (
    <Center py={48}>
      <Stack align="center" gap="sm">
        <ThemeIcon variant="light" color="gray" size={64} radius="xl">
          <Icon size={32} stroke={1.2} />
        </ThemeIcon>
        <Text c="dimmed" size="sm">
          {label}
        </Text>
        {hint && (
          <Text c="dimmed" size="xs">
            {hint}
          </Text>
        )}
      </Stack>
    </Center>
  );
}
