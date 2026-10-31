import type { TFunction } from "i18next";
import type { CodeIdentityResponse } from "../api/codeIdentities";

type Proposal = NonNullable<CodeIdentityResponse["proposal"]>;
type Decision = NonNullable<CodeIdentityResponse["decision"]>;

/** The name a row goes by: the GitLab display name, else the username, else the ghost's user id, else the identity key (an orphaned decision has nothing else). */
export function identityName(identity: CodeIdentityResponse, t: TFunction): string {
  if (identity.displayName) return identity.displayName;
  if (identity.username) return identity.username;
  if (identity.userId != null) return t("codeIdentities.ghost", { id: identity.userId });
  return identity.identityKey;
}

/** What a proposal says, in words: "Bot" or the Jira person's name (the account id when no active source lists the name). */
export function proposalWhat(proposal: Proposal, t: TFunction): string {
  return proposal.decision === "BOT" ? t("codeIdentities.person.bot") : (proposal.jiraDisplayName ?? proposal.jiraAccountId ?? "");
}

/** True when the stored decision is not what would be proposed now (the row then shows the proposal beside the decision). */
export function proposalDiffers(proposal: Proposal, decision: Decision): boolean {
  return proposal.decision !== decision.decision || (proposal.jiraAccountId ?? null) !== (decision.jiraAccountId ?? null);
}
