// Stamps the colour scheme on <html> before the app bundle loads, so the first paint already
// uses the right canvas (index.css) — an external same-origin file because the server's CSP
// forbids inline scripts. Mirrors Mantine's localStorage manager key; "auto" or no stored value
// follows the OS preference. MantineProvider takes over on mount.
(function () {
  try {
    var stored = window.localStorage.getItem("mantine-color-scheme-value");
    var scheme =
      stored === "light" || stored === "dark"
        ? stored
        : window.matchMedia("(prefers-color-scheme: dark)").matches
          ? "dark"
          : "light";
    document.documentElement.setAttribute("data-mantine-color-scheme", scheme);
  } catch {
    /* storage or matchMedia unavailable — Mantine resolves the scheme on mount */
  }
})();
