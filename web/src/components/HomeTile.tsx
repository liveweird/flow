import { useId, type ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { IconChevronRight } from "@tabler/icons-react";

/**
 * One section of the Home overview: the title IS the link into the full report (an `Anchor` inside the h3, so the link's name is
 * the title), the caption states the window the numbers are over and what a trend arrow compares with, and `links` are extra report
 * links under the body. The card is also the drill's focus anchor (`data-drill-anchor`, `tabIndex={-1}`): a pointer double-click on
 * one of its bars returns focus here when the pane closes. The page renders sections only once the overview has loaded (one
 * request), so a section has no loading or error state of its own.
 */
export default function HomeTile({
  title,
  to,
  caption,
  links = [],
  drillAnchor,
  children,
}: {
  title: string;
  to: string;
  caption: string;
  links?: ReadonlyArray<{ to: string; label: string }>;
  drillAnchor: string;
  children: ReactNode;
}) {
  const titleId = useId();
  return (
    <Paper component="section" withBorder p="md" aria-labelledby={titleId} data-drill-anchor={drillAnchor} tabIndex={-1}>
      <Stack gap="sm">
        <Stack gap={0}>
          <Title order={3} size="h4" id={titleId}>
            <Anchor component={RouterLink} to={to} inherit>
              <Group component="span" gap={4} wrap="nowrap" display="inline-flex">
                {title}
                <IconChevronRight size={16} aria-hidden />
              </Group>
            </Anchor>
          </Title>
          <Text size="xs" c="dimmed">
            {caption}
          </Text>
        </Stack>
        {children}
        {links.length > 0 && (
          <Group gap="md">
            {links.map((link) => (
              <Anchor key={link.to} component={RouterLink} to={link.to} size="sm">
                {link.label}
              </Anchor>
            ))}
          </Group>
        )}
      </Stack>
    </Paper>
  );
}
