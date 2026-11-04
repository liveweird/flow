import { Group } from "@mantine/core";
import type { CodeActivityReport } from "../api/reports";
import type { ReportDrillItem } from "../api/reportDrill";
import GitLabKeyLink from "./GitLabKeyLink";
import JiraKeyLink from "./JiraKeyLink";

/**
 * The key cell of a Code activity list: the commit's or merge request's GitLab key, then — for a merge request — the Jira issues it
 * names (`linkedKeys`, within the connected Jira projects: the server's scope), each a `JiraKeyLink`. Tab order is the reading order,
 * GitLab key first; both kinds of link underline on hover. Each key sits in its own `span` so that a key that is not a link (no single
 * known site) is still one element of its own. A null or empty `linkedKeys` (a commit, a merge request naming nothing) adds nothing.
 */
export default function CodeItemKey({ item, sources }: { item: ReportDrillItem; sources: CodeActivityReport["sources"] }) {
  const linked = item.linkedKeys ?? [];
  return (
    <Group gap="xs" wrap="wrap" align="baseline">
      <span>
        <GitLabKeyLink issueKey={item.issueKey} sources={sources} />
      </span>
      {linked.map((key) => (
        <span key={key}>
          <JiraKeyLink issueKey={key} />
        </span>
      ))}
    </Group>
  );
}
