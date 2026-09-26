import { useNavigate, Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import {
  Avatar,
  Badge,
  Box,
  Group,
  Indicator,
  Menu,
  SegmentedControl,
  Text,
  UnstyledButton,
  useMantineColorScheme,
} from "@mantine/core";
import { IconCheck, IconChevronDown, IconHistory, IconKey, IconLogout } from "@tabler/icons-react";
import { logout } from "../api/auth";
import { getUserId } from "../api/session";
import { getUser, setUserLanguage } from "../api/users";
import { flagSignedOut, useAdmin } from "../auth";
import { asSupportedLanguage, NATIVE_LANGUAGE_NAMES, SUPPORTED_LANGUAGES } from "../i18n";
import { useChangelogUnseen } from "../hooks/useChangelogSeen";

const IDENTITY_STALE_MS = 5 * 60_000;

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

/**
 * The header account menu (v1.19.0): the signed-in user's identity, and every account-scoped
 * control that used to be scattered over the shell — the language switcher, the colour
 * scheme, Change password, the Changelog (with the what's-new marker), Sign out.
 *
 * Identity comes from `GET /users/{id}` (self-or-admin; the session stores no name/email),
 * under the `["user", id]` key an admin's own edit invalidates. The menu never depends on
 * that fetch — Sign out works while it loads or fails. Language: the languages' NATIVE names
 * (readable before switching); a pick switches the UI and fire-and-forgets the server-side
 * user language (one synced language — it drives every email sent to the user). Theme: the
 * three real states (light / dark / system — the provider mounts `defaultColorScheme="auto"`),
 * as a SegmentedControl rather than items so the menu stays open. The what's-new dot rides
 * the TRIGGER (`title` = the accessible "What's new" e2e looks for) and the Changelog item
 * carries a text badge instead of a second title — two titled nodes would trip strict-mode
 * locators while the menu is open.
 */
export default function UserMenu() {
  const { t, i18n } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const changelogUnseen = useChangelogUnseen();
  const userId = getUserId();
  const current = asSupportedLanguage(i18n.resolvedLanguage);

  const identity = useQuery({
    queryKey: ["user", userId],
    queryFn: () => getUser(userId!),
    enabled: userId !== null,
    staleTime: IDENTITY_STALE_MS,
  });
  const name = identity.data?.name ?? "";

  function pickLanguage(lng: string) {
    void i18n.changeLanguage(lng);
    if (userId !== null) {
      setUserLanguage(userId, lng).catch((e: unknown) => console.error("Language sync failed", e));
    }
  }

  async function handleSignOut() {
    const revocation = logout();
    flagSignedOut();
    // The explicit navigation commits the cleared session and /login together. Broadcasting the
    // clear here can rerender RequireAuth on the old protected URL and save it as a login return.
    navigate("/login", { replace: true });
    await revocation;
  }

  return (
    <Menu width={260}>
      {/* The Indicator wraps the TARGET, not the other way round: Menu.Target clones its child
          with aria-expanded/aria-controls, which are only allowed on the button. */}
      <Indicator
        color="red"
        size={8}
        offset={4}
        disabled={!changelogUnseen}
        title={changelogUnseen ? t("changelog.whatsNew") : undefined}
      >
        <Menu.Target>
          <UnstyledButton aria-label={t("appShell.userMenu.open")} px={4} py={2}>
            <Group gap={6} wrap="nowrap">
              <Avatar size={28} radius="xl" color="flow" variant="light">
                {initials(name)}
              </Avatar>
              {name && (
                <Text size="sm" fw={500} visibleFrom="md" truncate maw={160}>
                  {name}
                </Text>
              )}
              <IconChevronDown size={14} />
            </Group>
          </UnstyledButton>
        </Menu.Target>
      </Indicator>
      <Menu.Dropdown>
        <Box px="sm" py="xs">
          <Text size="sm" fw={500} truncate>
            {name || "—"}
          </Text>
          <Group gap="xs" wrap="nowrap">
            <Text size="xs" c="dimmed" truncate>
              {identity.data?.email ?? "—"}
            </Text>
            {admin && (
              <Badge size="xs" variant="outline" color="gray">
                {t("common.role.ADMIN")}
              </Badge>
            )}
          </Group>
        </Box>
        <Menu.Divider />
        <Menu.Label>{t("appShell.userMenu.language")}</Menu.Label>
        {SUPPORTED_LANGUAGES.map((lng) => (
          <Menu.Item
            key={lng}
            onClick={() => pickLanguage(lng)}
            leftSection={lng === current ? <IconCheck size={14} /> : <Box w={14} />}
          >
            {NATIVE_LANGUAGE_NAMES[lng]}
          </Menu.Item>
        ))}
        <Menu.Label>{t("appShell.userMenu.theme")}</Menu.Label>
        <Box px="xs" pb="xs">
          <SegmentedControl
            size="xs"
            fullWidth
            value={colorScheme}
            onChange={(value) => setColorScheme(value as "light" | "dark" | "auto")}
            data={[
              { value: "light", label: t("appShell.userMenu.themeLight") },
              { value: "dark", label: t("appShell.userMenu.themeDark") },
              { value: "auto", label: t("appShell.userMenu.themeAuto") },
            ]}
          />
        </Box>
        <Menu.Divider />
        <Menu.Item component={RouterLink} to="/change-password" leftSection={<IconKey size={14} />}>
          {t("appShell.nav.changePassword")}
        </Menu.Item>
        <Menu.Item
          component={RouterLink}
          to="/changelog"
          leftSection={<IconHistory size={14} />}
          rightSection={
            changelogUnseen ? (
              <Badge size="xs" color="red" variant="filled">
                {t("changelog.newBadge")}
              </Badge>
            ) : undefined
          }
        >
          {t("appShell.nav.changelog")}
        </Menu.Item>
        <Menu.Divider />
        <Menu.Item color="red" leftSection={<IconLogout size={14} />} onClick={() => void handleSignOut()}>
          {t("appShell.userMenu.signOut")}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
