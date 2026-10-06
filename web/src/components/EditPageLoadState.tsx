import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Group, Stack } from "@mantine/core";
import LoadingBlock from "./LoadingBlock";
import PageHeader from "./PageHeader";

/**
 * The edit pages' shared load triage: a centered loader while fetching, else the load-failure
 * alert with a back-to-list button. The page owns the surrounding container/title and computes
 * the not-found vs status-tagged message itself — or, for a page whose real title only exists
 * once the entity has loaded, passes a static `title` so the load/failure state still opens with
 * the page's h2 (a page with no heading is a dead end for a screen reader).
 */
export default function EditPageLoadState({
  isLoading,
  message,
  backTo,
  backLabel,
  title,
}: {
  isLoading: boolean;
  message: string;
  backTo: string;
  backLabel: string;
  title?: string;
}) {
  const state = isLoading ? (
    <LoadingBlock />
  ) : (
    <>
      <Alert color="red" variant="light">
        {message}
      </Alert>
      <Group justify="flex-end">
        <Button component={RouterLink} to={backTo} variant="default">
          {backLabel}
        </Button>
      </Group>
    </>
  );
  return title === undefined ? (
    state
  ) : (
    <Stack gap="md">
      <PageHeader title={title} />
      {state}
    </Stack>
  );
}
