import { lazy, Suspense } from "react";
import { ActionIcon, AppShell, Box, Burger, Divider, Group, NavLink, ScrollArea, Text, Tooltip } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from "@tabler/icons-react";
import { Link as RouterLink, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RedirectIfAuthed, RequireAdmin, RequireAuth, useAdmin } from "./auth";
import BrandLogo from "./components/BrandLogo";
import CommandPalette from "./components/CommandPalette";
import LoadingBlock from "./components/LoadingBlock";
import UserMenu from "./components/UserMenu";
import VersionStamp from "./components/VersionStamp";
import { RouteErrorBoundary } from "./components/ErrorBoundary";
import { activeNavPath, visibleSections, type NavLeaf } from "./utils/navigation";
import { isBoolean, useStoredState } from "./hooks/useStoredState";
import classes from "./theme.module.css";

const Login = lazy(() => import("./pages/Login"));
const Home = lazy(() => import("./pages/Home"));
const Teams = lazy(() => import("./pages/Teams"));
const TeamDetails = lazy(() => import("./pages/TeamDetails"));
const ResetPassword = lazy(() => import("./pages/ResetPassword"));
const Users = lazy(() => import("./pages/Users"));
const UserFeatures = lazy(() => import("./pages/UserFeatures"));
const FeatureFlags = lazy(() => import("./pages/FeatureFlags"));
const DataSources = lazy(() => import("./pages/DataSources"));
const DataSourceDetails = lazy(() => import("./pages/DataSourceDetails"));
const DataSourceProfile = lazy(() => import("./pages/DataSourceProfile"));
const RawIssueInspector = lazy(() => import("./pages/RawIssueInspector"));
const DataSourceMetricsConfig = lazy(() => import("./pages/DataSourceMetricsConfig"));
const MetricsSettings = lazy(() => import("./pages/MetricsSettings"));
const ReportVelocity = lazy(() => import("./pages/ReportVelocity"));
const ReportThroughput = lazy(() => import("./pages/ReportThroughput"));
const ReportSprintConsistency = lazy(() => import("./pages/ReportSprintConsistency"));
const ReportTaskAccuracy = lazy(() => import("./pages/ReportTaskAccuracy"));
const ReportEpicAccuracy = lazy(() => import("./pages/ReportEpicAccuracy"));
const ReportEstimateAdjustments = lazy(() => import("./pages/ReportEstimateAdjustments"));
const ReportCycleTime = lazy(() => import("./pages/ReportCycleTime"));
const ReportReportedTime = lazy(() => import("./pages/ReportReportedTime"));
const ReportWip = lazy(() => import("./pages/ReportWip"));
const ReportBacklog = lazy(() => import("./pages/ReportBacklog"));
const ReportAgingWip = lazy(() => import("./pages/ReportAgingWip"));
const ReportBlockedTime = lazy(() => import("./pages/ReportBlockedTime"));
const ReportEpicProgress = lazy(() => import("./pages/ReportEpicProgress"));
const ReportDataQuality = lazy(() => import("./pages/ReportDataQuality"));
const CreateUser = lazy(() => import("./pages/CreateUser"));
const EditUser = lazy(() => import("./pages/EditUser"));
const ChangePassword = lazy(() => import("./pages/ChangePassword"));
const Changelog = lazy(() => import("./pages/Changelog"));
const NotFound = lazy(() => import("./pages/NotFound"));

function RouteFallback() {
  return (
    <LoadingBlock mih={200} />
  );
}

function Shell() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const [opened, { toggle, close }] = useDisclosure();
  const { pathname } = useLocation();
  // Desktop navbar collapse — device-level, persisted like the other view settings; the
  // mobile overlay keeps its own `opened` disclosure above and always shows full labels.
  const [navCollapsed, setNavCollapsed] = useStoredState("appShell.navCollapsed", false, isBoolean);
  const rail = navCollapsed && !opened;

  // The nav model lives in utils/navigation.ts (shared with the command palette): sections
  // of always-present leaves, admin-only ones filtered per session (the routes are guarded
  // too), an empty section disappearing with them.
  const sections = visibleSections(admin);
  const activeTo = activeNavPath(
    pathname,
    sections.flatMap((section) => section.items),
  );

  const renderLeaf = (leaf: NavLeaf) => {
    const active = leaf.to === activeTo;
    const Icon = leaf.icon;
    const label = t(leaf.label);
    if (rail) {
      // Icon-only: the accessible NAME stays the label (aria-label), so every locator that
      // finds the link by name works in both modes.
      return (
        <Tooltip key={leaf.to} label={label} position="right" withinPortal>
          <NavLink
            component={RouterLink}
            to={leaf.to}
            active={active}
            aria-current={active ? "page" : undefined}
            aria-label={label}
            leftSection={<Icon size={20} stroke={1.5} />}
            className={classes.railLink}
            onClick={close}
          />
        </Tooltip>
      );
    }
    return (
      <NavLink
        key={leaf.to}
        component={RouterLink}
        to={leaf.to}
        active={active}
        aria-current={active ? "page" : undefined}
        label={label}
        leftSection={<Icon size={18} stroke={1.5} />}
        onClick={close}
      />
    );
  };

  return (
    <AppShell
      header={{ height: 48 }}
      navbar={{ width: { base: 240, sm: rail ? 64 : 240 }, breakpoint: "sm", collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <a href="#main-content" className="skip-link">
          {t("appShell.skipToContent")}
        </a>
        <Group h={48} px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            {/* Sits beside the Burger, where the nav it toggles lives. */}
            <Tooltip label={t("appShell.toggleNav")} position="right" withinPortal>
              <ActionIcon
                variant="subtle"
                visibleFrom="sm"
                onClick={() => setNavCollapsed(!navCollapsed)}
                aria-label={t("appShell.toggleNav")}
                data-expanded={!navCollapsed || undefined}
              >
                {navCollapsed ? (
                  <IconLayoutSidebarLeftExpand size={18} />
                ) : (
                  <IconLayoutSidebarLeftCollapse size={18} />
                )}
              </ActionIcon>
            </Tooltip>
            <BrandLogo />
            <Text fw={600} size="md" className={classes.brandText}>
              {t("appShell.brand")}
            </Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <CommandPalette />
            <UserMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {/* The link list scrolls when it outgrows the viewport; the version stamp stays pinned. */}
        <AppShell.Section grow component={ScrollArea} type="hover" scrollbarSize={6} offsetScrollbars>
          {sections.map((section, index) => (
            // A labelled, always-open block — never a toggle, so every leaf stays in the DOM
            // for tests and deep links.
            <Box key={section.label} role="group" aria-label={t(section.label)}>
              {rail ? (
                index > 0 && <Divider my={4} mx={8} />
              ) : (
                <Text component="div" className={classes.navSectionLabel}>
                  {t(section.label)}
                </Text>
              )}
              {section.items.map(renderLeaf)}
            </Box>
          ))}
        </AppShell.Section>
        <AppShell.Section pt="xs" style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
          <VersionStamp to="/changelog" ta="center" pt={4} compact={rail} />
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main id="main-content" tabIndex={-1}>
        {/* A page crash stays inside the main area — header/nav keep working, and navigating
            anywhere remounts the boundary (see components/ErrorBoundary.tsx). The inner
            Suspense keeps the shell mounted while a lazy page chunk loads. */}
        <RouteErrorBoundary>
          <Suspense fallback={<RouteFallback />}>
            <Outlet />
          </Suspense>
        </RouteErrorBoundary>
      </AppShell.Main>
    </AppShell>
  );
}

export default function App() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route
          path="/login"
          element={
            <RedirectIfAuthed>
              <Login />
            </RedirectIfAuthed>
          }
        />
        <Route
          path="/reset-password"
          element={
            <RedirectIfAuthed>
              <ResetPassword />
            </RedirectIfAuthed>
          }
        />
        <Route element={<RequireAuth />}>
          <Route element={<Shell />}>
            <Route index element={<Home />} />
            <Route path="teams" element={<Teams />} />
            <Route path="teams/:id" element={<TeamDetails />} />
            {/* Reports: any signed-in user (D12) — never inside the RequireAdmin group. */}
            <Route path="reports/velocity" element={<ReportVelocity />} />
            <Route path="reports/throughput" element={<ReportThroughput />} />
            <Route path="reports/sprint-consistency" element={<ReportSprintConsistency />} />
            <Route path="reports/task-estimation-accuracy" element={<ReportTaskAccuracy />} />
            <Route path="reports/epic-estimation-accuracy" element={<ReportEpicAccuracy />} />
            <Route path="reports/estimate-adjustments" element={<ReportEstimateAdjustments />} />
            <Route path="reports/cycle-time" element={<ReportCycleTime />} />
            <Route path="reports/reported-time-ratio" element={<ReportReportedTime />} />
            <Route path="reports/wip" element={<ReportWip />} />
            <Route path="reports/backlog" element={<ReportBacklog />} />
            <Route path="reports/aging-wip" element={<ReportAgingWip />} />
            <Route path="reports/blocked-time" element={<ReportBlockedTime />} />
            <Route path="reports/epic-progress" element={<ReportEpicProgress />} />
            <Route path="reports/data-quality" element={<ReportDataQuality />} />
            {/* The management surface — ADMIN only, guarded once here (the pages no longer redirect themselves). */}
            <Route element={<RequireAdmin />}>
              <Route path="users" element={<Users />} />
              <Route path="users/new" element={<CreateUser />} />
              <Route path="users/:id/edit" element={<EditUser />} />
              <Route path="users/:id/features" element={<UserFeatures />} />
              <Route path="feature-flags" element={<FeatureFlags />} />
              <Route path="data-sources" element={<DataSources />} />
              <Route path="data-sources/:id" element={<DataSourceDetails />} />
              <Route path="data-sources/:id/profile" element={<DataSourceProfile />} />
              <Route path="data-sources/:id/inspect" element={<RawIssueInspector />} />
              <Route path="data-sources/:id/metrics-config" element={<DataSourceMetricsConfig />} />
              <Route path="metrics-settings" element={<MetricsSettings />} />
            </Route>
            <Route path="change-password" element={<ChangePassword />} />
            <Route path="changelog" element={<Changelog />} />
            {/* The authenticated catch-all — LAST child, never feature-gated. */}
            <Route path="*" element={<NotFound />} />
          </Route>
        </Route>
      </Routes>
    </Suspense>
  );
}
