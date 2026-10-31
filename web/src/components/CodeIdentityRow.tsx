import { useTranslation } from "react-i18next";
import { Badge, Group, Menu, Stack, Table, Text } from "@mantine/core";
import { IconBan, IconCheck, IconLink, IconRobot, IconTrash } from "@tabler/icons-react";
import type { CodeIdentityResponse } from "../api/codeIdentities";
import { formatDateTime } from "../utils/formatDate";
import { identityName, identityStateLabel, proposalDiffers, proposalWhat } from "../utils/codeIdentityLabels";
import RowActionsMenu from "./RowActionsMenu";

export type CodeIdentityRowActions = {
  confirm: (identity: CodeIdentityResponse) => void;
  link: (identity: CodeIdentityResponse) => void;
  markBot: (identity: CodeIdentityResponse) => void;
  ignore: (identity: CodeIdentityResponse) => void;
  remove: (identity: CodeIdentityResponse) => void;
};

function IdentityCell({ identity }: { identity: CodeIdentityResponse }) {
  const { t } = useTranslation();
  const name = identityName(identity, t);
  return (
    <Stack gap={0}>
      <Text size="sm" fw={500} ff={identity.seen ? undefined : "monospace"}>
        {name}
      </Text>
      {identity.seen && identity.username && identity.username !== name && (
        <Text size="xs" c="dimmed">
          {identity.username}
        </Text>
      )}
      {identity.seen && identity.kind === "EMAIL" && (
        <Text size="xs" c="dimmed">
          {t("codeIdentities.kind.email")}
        </Text>
      )}
    </Stack>
  );
}

function PersonCell({ identity }: { identity: CodeIdentityResponse }) {
  const { t } = useTranslation();
  const { decision, proposal } = identity;
  if (decision) {
    return (
      <Stack gap={0}>
        {decision.decision === "LINKED" ? (
          <>
            <Text size="sm">{decision.jiraDisplayName ?? decision.jiraAccountId}</Text>
            {decision.jiraDisplayName && (
              <Text size="xs" c="dimmed">
                {decision.jiraAccountId}
              </Text>
            )}
          </>
        ) : (
          <Text size="sm">{decision.decision === "BOT" ? t("codeIdentities.person.bot") : t("codeIdentities.person.ignored")}</Text>
        )}
        {proposal && proposalDiffers(proposal, decision) && (
          <Text size="xs" c="dimmed">
            {t("codeIdentities.person.proposedInstead", { what: proposalWhat(proposal, t) })}
          </Text>
        )}
      </Stack>
    );
  }
  if (proposal) {
    return (
      <Stack gap={0}>
        <Text size="sm">{proposalWhat(proposal, t)}</Text>
        {proposal.decision === "LINKED" && (
          <Text size="xs" c="dimmed">
            {proposal.jiraAccountId}
          </Text>
        )}
        <Text size="xs" c="dimmed">
          {t("codeIdentities.person.proposedHint")}
        </Text>
      </Stack>
    );
  }
  return (
    <Text size="sm" c="dimmed">
      {t("codeIdentities.person.none")}
    </Text>
  );
}

/** One GitLab identity: who it is, its addresses and state, the Jira person it is (or would be) linked to, and the row's operations. */
export default function CodeIdentityRow({ identity, actions }: { identity: CodeIdentityResponse; actions: CodeIdentityRowActions }) {
  const { t } = useTranslation();
  const name = identityName(identity, t);
  const current = identity.decision?.decision;
  return (
    <Table.Tr>
      <Table.Td>
        <IdentityCell identity={identity} />
      </Table.Td>
      <Table.Td>
        <Stack gap={0}>
          {identity.emails.map((email) => (
            <Text key={email} size="xs">
              {email}
            </Text>
          ))}
        </Stack>
      </Table.Td>
      <Table.Td>
        <Stack gap={4}>
          <Group gap={4}>
            {!identity.seen && (
              <Badge size="sm" color="orange">
                {t("codeIdentities.state.unseen")}
              </Badge>
            )}
            {identity.state && identity.state !== "active" && (
              <Badge size="sm" color="gray">
                {identityStateLabel(identity.state, t)}
              </Badge>
            )}
            {identity.deleted && (
              <Badge size="sm" color="gray">
                {t("codeIdentities.state.deleted")}
              </Badge>
            )}
            {identity.bot && (
              <Badge size="sm" color="gray">
                {t("codeIdentities.state.bot")}
              </Badge>
            )}
          </Group>
          {/* Visible text, not a tooltip: a hover-only hint is out of a keyboard user's reach. */}
          {!identity.seen && (
            <Text size="xs" c="dimmed" maw={240}>
              {t("codeIdentities.state.unseenHint")}
            </Text>
          )}
        </Stack>
      </Table.Td>
      <Table.Td>
        <PersonCell identity={identity} />
      </Table.Td>
      <Table.Td>
        <Text size="sm">{identity.decision ? formatDateTime(Date.parse(identity.decision.decidedAt)) : t("codeIdentities.person.none")}</Text>
      </Table.Td>
      <Table.Td style={{ width: 1 }} ta="right">
        <RowActionsMenu label={t("common.table.operationsAria", { name })}>
          {/* An orphaned decision (no data source on this instance has it) can only be removed: there is no identity to decide on. */}
          {identity.seen && identity.status === "PROPOSED" && identity.proposal && (
            <Menu.Item leftSection={<IconCheck size={14} />} onClick={() => actions.confirm(identity)}>
              {t("codeIdentities.action.confirm")}
            </Menu.Item>
          )}
          {identity.seen && (
            <Menu.Item leftSection={<IconLink size={14} />} onClick={() => actions.link(identity)}>
              {t("codeIdentities.action.link")}
            </Menu.Item>
          )}
          {identity.seen && current !== "BOT" && (
            <Menu.Item leftSection={<IconRobot size={14} />} onClick={() => actions.markBot(identity)}>
              {t("codeIdentities.action.bot")}
            </Menu.Item>
          )}
          {identity.seen && current !== "IGNORED" && (
            <Menu.Item leftSection={<IconBan size={14} />} onClick={() => actions.ignore(identity)}>
              {t("codeIdentities.action.ignore")}
            </Menu.Item>
          )}
          {identity.decision && (
            <>
              {identity.seen && <Menu.Divider />}
              <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => actions.remove(identity)}>
                {t("codeIdentities.action.remove")}
              </Menu.Item>
            </>
          )}
        </RowActionsMenu>
      </Table.Td>
    </Table.Tr>
  );
}
