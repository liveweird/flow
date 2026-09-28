import type { ParseKeys } from "i18next";
import {
  IconAdjustments,
  IconChartBar,
  IconHistory,
  IconHome2,
  IconKey,
  IconPlugConnected,
  IconToggleLeft,
  IconUsers,
  IconUsersGroup,
  type Icon,
} from "@tabler/icons-react";
import { dataSourcesPath } from "./dataSourceLinks";
import { velocityPath } from "./reportLinks";
import { teamsPath } from "./teamLinks";

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
};

/** A labelled, always-open block of leaves — a section, never a collapsible group. */
export type NavSection = {
  label: ParseKeys;
  items: ReadonlyArray<NavLeaf>;
};

/** The Home route — everyone lands here after sign-in. */
export const homePath = "/";

/**
 * The navigation model, shared by the sidebar and the command palette. Sections are labelled,
 * always-open blocks (never collapsible groups): every leaf is always in the DOM, so tests and
 * deep links address the links directly, and a static label costs less vertical space than a
 * toggle. Home and Reports are visible to everyone (every signed-in user sees every report, D12); the Administration section holds Teams (everyone reads
 * the flat teams list; only its create/edit/delete are ADMIN) alongside the ADMIN-only Users,
 * Feature flags, Data sources and Metrics settings leaves — a non-admin session sees just Teams
 * there.
 */
const NAV_SECTIONS: ReadonlyArray<NavSection> = [
  {
    label: "appShell.section.overview",
    items: [{ to: homePath, label: "appShell.nav.home", icon: IconHome2 }],
  },
  {
    label: "appShell.section.reports",
    items: [{ to: velocityPath, label: "appShell.nav.reportsDelivery", icon: IconChartBar }],
  },
  {
    label: "appShell.section.administration",
    items: [
      { to: teamsPath, label: "appShell.nav.teams", icon: IconUsersGroup },
      { to: "/users", label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
      { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
      { to: dataSourcesPath, label: "appShell.nav.dataSources", icon: IconPlugConnected, adminOnly: true },
      { to: "/metrics-settings", label: "appShell.nav.metricsSettings", icon: IconAdjustments, adminOnly: true },
    ],
  },
];

/** Account-scoped leaves: the header user menu and the palette render them, the sidebar never. */
export const ACCOUNT_NAV: ReadonlyArray<NavLeaf> = [
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
  { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory },
];

/** The sections a session may see: admin-only leaves filtered, empty sections dropped. */
export function visibleSections(admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/** Longest-matching-prefix active-link resolution — "/" only matches exactly. */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<NavLeaf>): string | null {
  const matches = (to: string) =>
    to === "/" ? pathname === "/" : pathname === to || pathname.startsWith(`${to}/`);
  return (
    leaves
      .map((leaf) => leaf.to)
      .filter(matches)
      .sort((a, b) => b.length - a.length)[0] ?? null
  );
}
