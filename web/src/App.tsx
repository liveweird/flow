import { lazy, Suspense } from "react";
import { ActionIcon, AppShell, Box, Burger, Divider, Group, NavLink, ScrollArea, Text, Tooltip } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from "@tabler/icons-react";
import { Link as RouterLink, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RedirectIfAuthed, RequireAdmin, RequireAuth, useAdmin } from "./auth";
import BrandLogo from "./components/BrandLogo";
import CommandPalette from "./components/CommandPalette";
import LegacyCostMatrixRedirect from "./components/LegacyCostMatrixRedirect";
import LegacyUsersRedirect from "./components/LegacyUsersRedirect";
import LegacyVelocityRedirect from "./components/LegacyVelocityRedirect";
import LoadingBlock from "./components/LoadingBlock";
import ScrollToTop from "./components/ScrollToTop";
import TelemetryPanelCrash from "./components/TelemetryPanelCrash";
import UserMenu from "./components/UserMenu";
import VersionStamp from "./components/VersionStamp";
import ErrorBoundary, { RouteErrorBoundary } from "./components/ErrorBoundary";
import { activeNavPath, visibleSections, type NavLeaf } from "./utils/navigation";
import { usePrintLifecycle } from "./hooks/usePrintLifecycle";
import { isBoolean, useStoredState } from "./hooks/useStoredState";
import { setTelemetryPanelOpen, useTelemetryPanelOpen } from "./hooks/useTelemetryPanel";
import classes from "./theme.module.css";

// The ADMIN telemetry panel: a lazy chunk a non-admin never downloads (it is only mounted for an ADMIN with the panel open).
const TelemetryPanel = lazy(() => import("./components/TelemetryPanel"));
const Login = lazy(() => import("./pages/Login"));
const Home = lazy(() => import("./pages/Home"));
const Teams = lazy(() => import("./pages/Teams"));
const TeamWorkCategoryTargets = lazy(() => import("./pages/TeamWorkCategoryTargets"));
const TeamDetails = lazy(() => import("./pages/TeamDetails"));
const Capacity = lazy(() => import("./pages/Capacity"));
const ResetPassword = lazy(() => import("./pages/ResetPassword"));
const ConfirmPasswordReset = lazy(() => import("./pages/ConfirmPasswordReset"));
const FlowUsers = lazy(() => import("./pages/FlowUsers"));
const FlowUserFeatures = lazy(() => import("./pages/FlowUserFeatures"));
const FeatureFlags = lazy(() => import("./pages/FeatureFlags"));
const DataSources = lazy(() => import("./pages/DataSources"));
const DataSourceDetails = lazy(() => import("./pages/DataSourceDetails"));
const DataSourceProfile = lazy(() => import("./pages/DataSourceProfile"));
const DataSourceIdentities = lazy(() => import("./pages/DataSourceIdentities"));
const RawIssueInspector = lazy(() => import("./pages/RawIssueInspector"));
const DataSourceMetricsConfig = lazy(() => import("./pages/DataSourceMetricsConfig"));
const MetricsSettings = lazy(() => import("./pages/MetricsSettings"));
const PublicHolidays = lazy(() => import("./pages/PublicHolidays"));
const ReportCommitments = lazy(() => import("./pages/ReportCommitments"));
const ReportThroughput = lazy(() => import("./pages/ReportThroughput"));
const ReportSprintConsistency = lazy(() => import("./pages/ReportSprintConsistency"));
const ReportTaskAccuracy = lazy(() => import("./pages/ReportTaskAccuracy"));
const ReportTaskEstimates = lazy(() => import("./pages/ReportTaskEstimates"));
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
const ReportCosts = lazy(() => import("./pages/ReportCosts"));
const ReportComparePeople = lazy(() => import("./pages/ReportComparePeople"));
const ReportPersonTrend = lazy(() => import("./pages/ReportPersonTrend"));
const ReportDeepDive = lazy(() => import("./pages/ReportDeepDive"));
const CreateFlowUser = lazy(() => import("./pages/CreateFlowUser"));
const EditFlowUser = lazy(() => import("./pages/EditFlowUser"));
const ChangePassword = lazy(() => import("./pages/ChangePassword"));
const Changelog = lazy(() => import("./pages/Changelog"));
const NotFound = lazy(() => import("./pages/NotFound"));

function RouteFallback() {
  return (
    <LoadingBlock mih={200} />
  );
}

/** The pages that get the floating "Back to top" button: the unit overview and every report. */
function hasScrollToTop(pathname: string): boolean {
  return pathname === "/" || pathname.startsWith("/reports/");
}

function Shell() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const [opened, { toggle, close }] = useDisclosure();
  const { pathname } = useLocation();
  // Every page prints in the light scheme (the print-aware provider in main.tsx reads what this reports).
  usePrintLifecycle();
  // Desktop navbar collapse — device-level, persisted like the other view settings; the
  // mobile overlay keeps its own `opened` disclosure above and always shows full labels.
  const [navCollapsed, setNavCollapsed] = useStoredState("appShell.navCollapsed", false, isBoolean);
  const rail = navCollapsed && !opened;
  // The telemetry panel: the stored flag is device-level, but a stored `true` shows nothing to a non-admin.
  const panelStored = useTelemetryPanelOpen();
  const showPanel = admin && panelStored;
  const closePanel = () => setTelemetryPanelOpen(false);

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
      // 420 px beside the page from `lg` up (the content gives way: at 1200 px the 240 px navbar and the panel still leave ~540 px);
      // below it the panel covers the page edge to edge, since a pushed 420 px next to the navbar would leave a sliver.
      aside={{ width: 420, breakpoint: "lg", collapsed: { desktop: !showPanel, mobile: !showPanel } }}
      padding="md"
    >
      <AppShell.Header>
        <a href="#main-content" className="skip-link">
          {t("appShell.skipToContent")}
        </a>
        <Group h={48} px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger
              opened={opened}
              onClick={toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label={t("appShell.toggleMobileNav")}
              aria-expanded={opened}
            />
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
        {hasScrollToTop(pathname) && <ScrollToTop />}
      </AppShell.Main>

      {showPanel && (
        <AppShell.Aside aria-label={t("telemetry.title")}>
          {/* Outside every route boundary: its own, with a way to close it (the open flag survives a reload). */}
          <ErrorBoundary fallback={<TelemetryPanelCrash onClose={closePanel} />}>
            <Suspense fallback={<LoadingBlock />}>
              <TelemetryPanel onClose={closePanel} />
            </Suspense>
          </ErrorBoundary>
        </AppShell.Aside>
      )}
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
        {/* Not behind RedirectIfAuthed: a signed-in device may finish a reset (the page clears its local session on success). */}
        <Route path="/reset-password/confirm" element={<ConfirmPasswordReset />} />
        <Route element={<RequireAuth />}>
          <Route element={<Shell />}>
            <Route index element={<Home />} />
            <Route path="teams" element={<Teams />} />
            <Route path="teams/:id" element={<TeamDetails />} />
            {/* Work-category targets: everyone reads them, the page shows inputs to an ADMIN only and the server is the rule — never inside RequireAdmin. */}
            <Route path="teams/:id/work-category-targets" element={<TeamWorkCategoryTargets />} />
            {/* Capacity: everyone reads it, the server decides who edits (the team's manager or an ADMIN) — never inside RequireAdmin. */}
            <Route path="capacity" element={<Capacity />} />
            {/* Public holidays: everyone reads, the page shows add/delete to an ADMIN only and the server is the rule — never inside RequireAdmin. */}
            <Route path="public-holidays" element={<PublicHolidays />} />
            {/* Reports: any signed-in user (D12) — never inside the RequireAdmin group. */}
            <Route path="reports/commitments" element={<ReportCommitments />} />
            {/* Report 1's old address, kept alive for bookmarks and shared links. */}
            <Route path="reports/velocity" element={<LegacyVelocityRedirect />} />
            <Route path="reports/throughput" element={<ReportThroughput />} />
            <Route path="reports/sprint-consistency" element={<ReportSprintConsistency />} />
            <Route path="reports/task-estimation-accuracy" element={<ReportTaskAccuracy />} />
            <Route path="reports/task-estimates" element={<ReportTaskEstimates />} />
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
            <Route path="reports/costs" element={<ReportCosts />} />
            {/* The old /reports/cost-matrix… addresses (bookmarks, emailed links) land on the same page under /reports/costs. */}
            <Route path="reports/cost-matrix/*" element={<LegacyCostMatrixRedirect />} />
            <Route path="reports/compare-people" element={<ReportComparePeople />} />
            <Route path="reports/person-trend" element={<ReportPersonTrend />} />
            <Route path="reports/deep-dive" element={<ReportDeepDive />} />
            {/* The management surface — ADMIN only, guarded once here (the pages no longer redirect themselves). */}
            <Route element={<RequireAdmin />}>
              <Route path="flow-users" element={<FlowUsers />} />
              {/* The old /users… addresses (bookmarks, emailed links) land on the same page under /flow-users. */}
              <Route path="users/*" element={<LegacyUsersRedirect />} />
              <Route path="flow-users/new" element={<CreateFlowUser />} />
              <Route path="flow-users/:id/edit" element={<EditFlowUser />} />
              <Route path="flow-users/:id/features" element={<FlowUserFeatures />} />
              <Route path="feature-flags" element={<FeatureFlags />} />
              <Route path="data-sources" element={<DataSources />} />
              <Route path="data-sources/:id" element={<DataSourceDetails />} />
              <Route path="data-sources/:id/profile" element={<DataSourceProfile />} />
              <Route path="data-sources/:id/identities" element={<DataSourceIdentities />} />
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
