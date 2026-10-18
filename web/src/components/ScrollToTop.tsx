import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Affix, Tooltip, Transition } from "@mantine/core";
import { IconArrowUp } from "@tabler/icons-react";

/** How far down the page (px) the button appears. */
const SHOW_AFTER_PX = 400;
const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";

/**
 * The floating "Back to top" button of the report pages (rendered once by the app shell for `/` and `/reports/*`): bottom-right,
 * shown once the page is scrolled past `SHOW_AFTER_PX`. It scrolls smoothly — instantly under `prefers-reduced-motion` — and then
 * moves focus to the page heading (the `PageHeader` h2, else the main region), so a keyboard or screen-reader user lands where the page starts instead of
 * on a control that has just vanished. Hidden on paper (`data-print="hide"`, `print.css`).
 */
export default function ScrollToTop() {
  const { t } = useTranslation();
  const [scrolled, setScrolled] = useState(() => window.scrollY > SHOW_AFTER_PX);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > SHOW_AFTER_PX);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  const goToTop = () => {
    window.scrollTo({ top: 0, behavior: window.matchMedia(REDUCED_MOTION).matches ? "auto" : "smooth" });
    // The page heading, else the main region itself (it is programmatically focusable already).
    const target = document.querySelector<HTMLElement>("#main-content h2") ?? document.getElementById("main-content");
    if (target) {
      target.tabIndex = -1;
      target.focus({ preventScroll: true });
    }
  };

  return (
    <Affix position={{ bottom: 20, right: 20 }} data-print="hide">
      <Transition transition="slide-up" mounted={scrolled}>
        {(styles) => (
          <Tooltip label={t("common.scrollToTop")} position="left" withinPortal>
            <ActionIcon
              variant="default"
              size="lg"
              radius="xl"
              style={{ ...styles, boxShadow: "var(--mantine-shadow-md)" }}
              aria-label={t("common.scrollToTop")}
              onClick={goToTop}
            >
              <IconArrowUp size={20} />
            </ActionIcon>
          </Tooltip>
        )}
      </Transition>
    </Affix>
  );
}
