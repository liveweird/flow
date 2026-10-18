import { useTranslation } from "react-i18next";
import { Anchor, VisuallyHidden } from "@mantine/core";
import { IconExternalLink } from "@tabler/icons-react";
import { useJiraConnections } from "../hooks/useJiraConnections";
import { jiraBrowseUrl, resolveJiraSite } from "../utils/jiraLinks";

export interface JiraKeyLinkProps {
  issueKey: string;
  /** The connection the key belongs to, when the row knows it; otherwise the site is the one every connection shares (`resolveJiraSite`). */
  connectionId?: number;
  /** An icon-only link (no key text) for a key that is already printed or already a link, e.g. a Deep dive row or an in-app link. */
  iconOnly?: boolean;
  /** Takes the link out of the tab order (a roving-tab-stop grid, where the keyboard has the same link elsewhere). */
  skipTab?: boolean;
  /** `always` where the key sits inline with other text ("KEY summary"), so the link is told apart without colour (axe `link-in-text-block`). */
  underline?: "hover" | "always";
}

/**
 * An issue or epic key as a link to the issue in Jira (`{siteUrl}/browse/{KEY}`) in a new tab — `rel="noopener noreferrer"`, an
 * external-link icon and a visually hidden "(opens in Jira)". The site comes from the report filters' connections, read from the
 * shared `["reports", "filters"]` cache entry (`useJiraConnections`: any signed-in user, loaded by the page that shows the keys);
 * while it is unknown, or the key's site is ambiguous, the key is plain text. Inherits the surrounding font size and weight.
 */
export default function JiraKeyLink({ issueKey, connectionId, iconOnly = false, skipTab = false, underline = "hover" }: JiraKeyLinkProps) {
  const { t } = useTranslation();
  const href = jiraBrowseUrl(resolveJiraSite(useJiraConnections(), connectionId), issueKey);
  if (href === undefined) return iconOnly ? null : <>{issueKey}</>;
  return (
    <Anchor href={href} target="_blank" rel="noopener noreferrer" inherit underline={underline} tabIndex={skipTab ? -1 : undefined}>
      {!iconOnly && issueKey}
      <IconExternalLink size="0.85em" aria-hidden style={{ marginInlineStart: "0.25em", verticalAlign: "-0.1em" }} />
      <VisuallyHidden component="span">
        {iconOnly ? t("common.jira.openKeyInJira", { key: issueKey }) : ` ${t("common.jira.opensInJira")}`}
      </VisuallyHidden>
    </Anchor>
  );
}
