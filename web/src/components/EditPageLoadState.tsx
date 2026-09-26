import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Group } from "@mantine/core";
import LoadingBlock from "./LoadingBlock";

/**
 * The edit pages' shared load triage: a centered loader while fetching, else the load-failure
 * alert with a back-to-list button. The page owns the surrounding container/title and computes
 * the not-found vs status-tagged message itself.
 */
export default function EditPageLoadState({
  isLoading,
  message,
  backTo,
  backLabel,
}: {
  isLoading: boolean;
  message: string;
  backTo: string;
  backLabel: string;
}) {
  return isLoading ? (
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
}
