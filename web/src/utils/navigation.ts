import type { ParseKeys } from "i18next";
import {
  IconAdjustments,
  IconChartAreaLine,
  IconChartBar,
  IconChecklist,
  IconCoin,
  IconTarget,
  IconHistory,
  IconHome2,
  IconKey,
  IconPlugConnected,
  IconToggleLeft,
  IconUsers,
  IconUsersGroup,
  IconZoomScan,
  type Icon,
} from "@tabler/icons-react";
import { dataSourcesPath } from "./dataSourceLinks";
import { costMatrixPath, dataQualityPath, deepDiveBasePath, DELIVERY_TABS, ESTIMATION_TABS, FLOW_TABS, taskAccuracyPath, velocityPath, wipPath } from "./reportLinks";
import { teamsPath } from "./teamLinks";
import { usersPath } from "./userLinks";

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
  /**
   * Further routes this leaf stays highlighted on (a report group's other tabs, each of which
   * is its own route). Prefix-matched like `to`; the leaf itself always links to `to`.
   */
  activeFor?: ReadonlyArray<string>;
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
    items: [
      {
        to: velocityPath,
        label: "appShell.nav.reportsDelivery",
        icon: IconChartBar,
        activeFor: DELIVERY_TABS.map((tab) => tab.to),
      },
      {
        to: taskAccuracyPath,
        label: "appShell.nav.reportsEstimation",
        icon: IconTarget,
        activeFor: ESTIMATION_TABS.map((tab) => tab.to),
      },
      {
        to: wipPath,
        label: "appShell.nav.reportsFlow",
        icon: IconChartAreaLine,
        activeFor: FLOW_TABS.map((tab) => tab.to),
      },
      // A group of one report: the leaf is the page, so it needs no tabs and no palette-only twin.
      { to: dataQualityPath, label: "appShell.nav.reportsDataQuality", icon: IconChecklist },
      // Also a group of one: the cost matrix is a different shape from the flow reports (a matrix, not a series).
      { to: costMatrixPath, label: "appShell.nav.reportsCost", icon: IconCoin },
      // And the Deep dive: one matrix over a selection the page's own panel makes, so it has no tabs either.
      { to: deepDiveBasePath, label: "appShell.nav.reportsDeepDive", icon: IconZoomScan },
    ],
  },
  {
    label: "appShell.section.administration",
    items: [
      { to: teamsPath, label: "appShell.nav.teams", icon: IconUsersGroup },
      { to: usersPath, label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
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

/**
 * Palette-only leaves: every report of a nav group is its own route and must be findable by name,
 * but the sidebar carries ONE leaf per group (Delivery, Estimation, Flow). Tabs never appear in a section.
 */
export const REPORT_PALETTE_LEAVES: ReadonlyArray<NavLeaf> = [...DELIVERY_TABS, ...ESTIMATION_TABS, ...FLOW_TABS].map((tab) => ({
  to: tab.to,
  label: tab.label,
  icon: IconChartBar,
}));

/** The sections a session may see: admin-only leaves filtered, empty sections dropped. */
export function visibleSections(admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/**
 * Longest-matching-prefix active-link resolution — "/" only matches exactly. A leaf matches on its
 * own `to` and on every `activeFor` route; the answer is always the leaf's `to`.
 */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<NavLeaf>): string | null {
  const matches = (path: string) =>
    path === "/" ? pathname === "/" : pathname === path || pathname.startsWith(`${path}/`);
  return (
    leaves
      .flatMap((leaf) => [leaf.to, ...(leaf.activeFor ?? [])].filter(matches).map((path) => ({ to: leaf.to, length: path.length })))
      .sort((a, b) => b.length - a.length)[0]?.to ?? null
  );
}
