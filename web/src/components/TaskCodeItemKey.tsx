import { Badge, Group } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { CodeActivityReport } from "../api/reports";
import type { ReportDrillItem } from "../api/reportDrill";
import GitLabKeyLink from "./GitLabKeyLink";
import JiraKeyLink from "./JiraKeyLink";

/**
 * The key cell of a Tasks and code list: the task's Jira key, then the GitLab merge requests linked to it (`linkedKeys`, each
 * `<project path>!<iid>`), each a `GitLabKeyLink` to the report's sources. Tab order is the reading order, Jira key first. Each key sits in its
 * own `span`, so a merge request that is not a link (two different GitLab hosts, or a project the server could not name) is still one element of
 * its own. A null or empty `linkedKeys` (a task nothing names) adds nothing.
 *
 * With more than one linked key, the merge request the item's figure measured (`measuredKey`, the task's last merge) carries a neutral
 * "last merge" badge: text, so it reads without colour and aloud. It marks the FIRST entry equal to `measuredKey` only (two GitLab hosts can
 * hold the same key). With one key there is nothing to tell apart, so nothing is marked.
 */
export default function TaskCodeItemKey({ item, sources }: { item: ReportDrillItem; sources: CodeActivityReport["sources"] }) {
  const { t } = useTranslation();
  const linked = item.linkedKeys ?? [];
  const measuredIndex = linked.length > 1 && item.measuredKey != null ? linked.indexOf(item.measuredKey) : -1;
  return (
    <Group gap="xs" wrap="wrap" align="baseline">
      <span>
        <JiraKeyLink issueKey={item.issueKey} />
      </span>
      {linked.map((key, index) => (
        // Position-qualified: two GitLab hosts can hold the same `<path>!<iid>` (a mirror), so the key alone is not unique.
        <span key={`${index}:${key}`}>
          <GitLabKeyLink issueKey={key} sources={sources} />
          {index === measuredIndex && (
            <Badge variant="outline" color="gray" size="xs" ml={4} tt="none">
              {t("reports.taskCode.drill.lastMerge")}
            </Badge>
          )}
        </span>
      ))}
    </Group>
  );
}
