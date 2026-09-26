import { Center, Stack, Text, ThemeIcon } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";

/**
 * The shared zero-rows body for list tables (icon + dimmed one-liner). Takes the Tabler icon
 * COMPONENT and sizes/strokes it itself, so every empty state draws the same glyph weight.
 */
export default function EmptyState({ icon: Icon, label }: { icon: Icon; label: string }) {
  return (
    <Center py={48}>
      <Stack align="center" gap="sm">
        <ThemeIcon variant="light" color="gray" size={64} radius="xl">
          <Icon size={32} stroke={1.2} />
        </ThemeIcon>
        <Text c="dimmed" size="sm">
          {label}
        </Text>
      </Stack>
    </Center>
  );
}
