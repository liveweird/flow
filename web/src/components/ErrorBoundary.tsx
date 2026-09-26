import { Component, type ReactNode } from "react";
import { Alert, Button, Center, Stack, Text } from "@mantine/core";
import { IconAlertTriangle } from "@tabler/icons-react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";

/**
 * The render-crash safety net (v2.22.0) — before it, any render-time throw unmounted the
 * whole React tree into a white screen. Two mounts: `RouteErrorBoundary` wraps the routed
 * content inside the AppShell (header/nav survive a page crash, and the location key remounts
 * it on navigation so clicking any nav item recovers), and a plain `ErrorBoundary` sits at
 * the root in main.tsx as the last resort (no router hooks there). It also backstops a
 * lazy-chunk load failure when the one-shot reload in main.tsx wasn't enough.
 */
export default class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  render() {
    // React already logs the caught error itself — the boundary only swaps the UI.
    return this.state.failed ? <CrashFallback /> : this.props.children;
  }
}

export function RouteErrorBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  return <ErrorBoundary key={pathname}>{children}</ErrorBoundary>;
}

function CrashFallback() {
  const { t } = useTranslation();
  return (
    <Center mih={200} p="xl">
      <Alert
        color="red"
        variant="light"
        icon={<IconAlertTriangle />}
        title={t("common.errorBoundary.title")}
        maw={480}
      >
        <Stack gap="sm" align="flex-start">
          <Text size="sm">{t("common.errorBoundary.message")}</Text>
          <Button color="red" variant="light" onClick={() => window.location.reload()}>
            {t("common.errorBoundary.reload")}
          </Button>
        </Stack>
      </Alert>
    </Center>
  );
}
