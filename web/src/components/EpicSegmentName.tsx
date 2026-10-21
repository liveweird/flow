import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import { NO_EPIC, epicSegmentName, epicSummary, type EpicSegmentIdentity } from "../utils/epicSegments";
import JiraKeyLink from "./JiraKeyLink";

/** A by-epic segment's name in a table: an epic is its Jira key (a link) and summary, the two special segments their localised words. */
export default function EpicSegmentName({ segment }: { segment: EpicSegmentIdentity }) {
  const { t } = useTranslation();
  if (segment.epicKey === null) {
    return (
      <Text size="sm" c={segment.key === NO_EPIC ? "dimmed" : undefined}>
        {epicSegmentName(t, segment)}
      </Text>
    );
  }
  return (
    <Text size="sm">
      <JiraKeyLink issueKey={segment.epicKey} underline="always" /> {epicSummary(segment)}
    </Text>
  );
}
