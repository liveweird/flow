import { useTranslation } from "react-i18next";
import { Anchor, VisuallyHidden } from "@mantine/core";
import { IconExternalLink } from "@tabler/icons-react";
import type { CodeActivityReport } from "../api/reports";
import { gitlabItemUrl, resolveGitLabBase } from "../utils/gitlabLinks";

/**
 * A merge request or commit key (`group/app!12`, `group/app@3f9a1c2b4d5e`) as a link to its GitLab page in a new tab: `rel="noopener noreferrer"`,
 * an external-link icon and a visually hidden "(opens in GitLab)". The host comes from the report's sources (`resolveGitLabBase`); while it is
 * ambiguous (two different GitLab hosts), or the key is not a GitLab key, it is plain text. Inherits the surrounding font size and weight.
 */
export default function GitLabKeyLink({ issueKey, sources }: { issueKey: string; sources: CodeActivityReport["sources"] }) {
  const { t } = useTranslation();
  const href = gitlabItemUrl(resolveGitLabBase(sources), issueKey);
  if (href === undefined) return <>{issueKey}</>;
  return (
    <Anchor href={href} target="_blank" rel="noopener noreferrer" inherit underline="hover">
      {issueKey}
      <IconExternalLink size="0.85em" aria-hidden style={{ marginInlineStart: "0.25em", verticalAlign: "-0.1em" }} />
      <VisuallyHidden component="span">{` ${t("common.gitlab.opensInGitLab")}`}</VisuallyHidden>
    </Anchor>
  );
}
