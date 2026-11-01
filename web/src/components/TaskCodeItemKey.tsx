import { Group } from "@mantine/core";
import type { CodeActivityReport } from "../api/reports";
import type { ReportDrillItem } from "../api/reportDrill";
import GitLabKeyLink from "./GitLabKeyLink";
import JiraKeyLink from "./JiraKeyLink";

/**
 * The key cell of a Tasks and code list: the task's Jira key, then the GitLab merge requests the figure counted for it (`linkedKeys`, each
 * `<project path>!<iid>`), each a `GitLabKeyLink` to the report's sources. Tab order is the reading order, Jira key first. Each key sits in its
 * own `span`, so a merge request that is not a link (two different GitLab hosts, or a project the server could not name) is still one element of
 * its own. A null or empty `linkedKeys` (a task nothing names) adds nothing.
 */
export default function TaskCodeItemKey({ item, sources }: { item: ReportDrillItem; sources: CodeActivityReport["sources"] }) {
  const linked = item.linkedKeys ?? [];
  return (
    <Group gap="xs" wrap="wrap" align="baseline">
      <span>
        <JiraKeyLink issueKey={item.issueKey} />
      </span>
      {linked.map((key) => (
        <span key={key}>
          <GitLabKeyLink issueKey={key} sources={sources} />
        </span>
      ))}
    </Group>
  );
}
