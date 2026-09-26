import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@fontsource-variable/inter/index.css";
// The LAYERED Mantine stylesheets (`@layer mantine`): every unlayered rule of ours —
// theme.module.css above all — then wins regardless of stylesheet order. Load-bearing: Vite
// splits theme.module.css into a shared CSS chunk once lazy pages import it, and index.html
// links that chunk BEFORE Mantine's own styles, where plain source order would lose.
import "@mantine/core/styles.layer.css";
import "@mantine/notifications/styles.layer.css";
import "@mantine/spotlight/styles.layer.css";
import "./index.css";
import "./i18n";
import App from "./App.tsx";
import ErrorBoundary from "./components/ErrorBoundary";
import SessionBoundary from "./components/SessionBoundary";
import { shouldRetryQuery } from "./api/http";
import { theme } from "./theme";
import { cssVariablesResolver } from "./themeVariables";
import { bindSessionQueryCache } from "./utils/sessionQueryCache";

const queryClient = new QueryClient({
  // Never retry a 4xx; up to two retries for transient failures (see shouldRetryQuery).
  defaultOptions: { queries: { retry: shouldRetryQuery } },
});
bindSessionQueryCache(queryClient);

// A redeploy invalidates the hashed lazy chunks — the first failed dynamic import reloads the
// page once to pick up the new index.html. Rate-limited via sessionStorage (at most one
// reload per minute) so a genuinely missing chunk can't loop; past that, the rejection falls
// through to the ErrorBoundary instead.
const CHUNK_RELOAD_KEY = "flow.chunkReloadedAt";
window.addEventListener("vite:preloadError", (event) => {
  const lastReload = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) ?? 0);
  if (Date.now() - lastReload > 60_000) {
    event.preventDefault();
    sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now()));
    window.location.reload();
  }
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {/* No <ColorSchemeScript> here: rendered inside the React tree it never executes, and
        public/color-scheme.js already stamps the scheme on <html> before the bundle loads. */}
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
      {/* The success-toast host (utils/toast.tsx is the only writer). Top-center on purpose:
          bottom-right would cover PaginationBar and form footers, top-right the header
          user-menu/bell cluster. Deliberately mounted here, not in App — tests never see it. */}
      <Notifications position="top-center" autoClose={2500} limit={3} />
      {/* Last-resort crash fallback; the navigation-resetting RouteErrorBoundary lives inside
          the Shell (App.tsx), so this one only catches crashes of the shell itself. */}
      <ErrorBoundary>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <SessionBoundary>
              <App />
            </SessionBoundary>
          </BrowserRouter>
        </QueryClientProvider>
      </ErrorBoundary>
    </MantineProvider>
  </StrictMode>,
);
