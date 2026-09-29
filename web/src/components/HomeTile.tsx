import { useId, type ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Box, Group, Paper, Skeleton, Stack, Text, Title } from "@mantine/core";
import { IconChevronRight } from "@tabler/icons-react";
import { loadErrorMessage } from "../utils/saveError";

/**
 * One tile of the Home overview: the title IS the link into the full report (an `Anchor` inside the
 * h3, so the link's name is the title), the caption states the period/scope the numbers are over,
 * and the body is triaged here — a skeleton while loading, a red-light Alert (`loadErrorMessage`,
 * never `error.message`) when THIS tile's request failed, else `children`. Tiles are independent: one
 * failure never takes the others down. `links` are extra report links under the body.
 */
export default function HomeTile({
  title,
  to,
  caption,
  isPending,
  error,
  links = [],
  children,
}: {
  title: string;
  to: string;
  caption: string;
  isPending: boolean;
  error?: unknown;
  links?: ReadonlyArray<{ to: string; label: string }>;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  let body: ReactNode;
  if (error) {
    body = (
      <Alert color="red" variant="light" role="alert">
        {loadErrorMessage(error, t)}
      </Alert>
    );
  } else if (isPending) {
    // Decorative: the page owns the ONE polite "Loading…" live region (five tiles must not announce five times).
    body = (
      <Stack gap="xs" aria-hidden>
        <Skeleton height={28} width="40%" />
        <Skeleton height={120} />
      </Stack>
    );
  } else {
    body = <Box>{children}</Box>;
  }
  return (
    <Paper component="section" withBorder p="md" aria-labelledby={titleId} aria-busy={isPending} h="100%">
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
        {body}
        {links.length > 0 && !error && !isPending && (
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
