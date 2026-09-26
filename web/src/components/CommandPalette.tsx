import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Kbd, Text, UnstyledButton } from "@mantine/core";
import { useOs } from "@mantine/hooks";
import { Spotlight, type SpotlightActionData, type SpotlightActionGroupData, type SpotlightFilterFunction } from "@mantine/spotlight";
import { IconSearch } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { ACCOUNT_NAV, visibleSections } from "../utils/navigation";
import { palette, paletteStore } from "../utils/commandPalette";
import { foldDiacritics } from "../utils/text";
import classes from "../theme.module.css";

/**
 * The command palette: ⌘K / Ctrl K, or the search-looking trigger in the header. One group —
 * every page the session may see (the same nav model as the sidebar plus the account leaves),
 * matched client-side by folded label. Renders BOTH the trigger and the Spotlight, mounted
 * once in the shell header. No `highlightQuery`: it splits a label into `<mark>` + text
 * fragments, and tests/e2e locate results by their full name.
 */
export default function CommandPalette() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const os = useOs();
  const [query, setQuery] = useState("");

  const go = (to: string) => {
    palette.close();
    navigate(to);
  };

  const pages: SpotlightActionGroupData = {
    group: t("appShell.palette.groupPages"),
    actions: [...visibleSections(admin).flatMap((section) => section.items), ...ACCOUNT_NAV].map(
      (leaf) => {
        const Icon = leaf.icon;
        return {
          id: `page:${leaf.to}`,
          label: t(leaf.label),
          leftSection: <Icon size={18} stroke={1.5} />,
          onClick: () => go(leaf.to),
        };
      },
    ),
  };
  // Pages match the folded query on their label.
  const filter: SpotlightFilterFunction = (q, actions) => {
    const folded = foldDiacritics(q.trim());
    return actions.flatMap((entry): (SpotlightActionData | SpotlightActionGroupData)[] => {
      if (!("actions" in entry)) return [entry];
      const group = entry as SpotlightActionGroupData;
      const items = group.actions.filter((a: SpotlightActionData) => foldDiacritics(String(a.label ?? "")).includes(folded));
      return items.length > 0 ? [{ ...group, actions: items }] : [];
    });
  };
  const shortcut = os === "macos" ? "⌘ K" : "Ctrl K";
  return (
    <>
      <UnstyledButton
        className={classes.paletteTrigger}
        visibleFrom="sm"
        aria-label={t("appShell.palette.open")}
        onClick={palette.open}
      >
        <IconSearch size={16} />
        <Text component="span" size="sm" inherit>
          {t("appShell.palette.placeholder")}
        </Text>
        <Kbd size="xs">{shortcut}</Kbd>
      </UnstyledButton>
      <ActionIcon hiddenFrom="sm" size="lg" aria-label={t("appShell.palette.open")} onClick={palette.open}>
        <IconSearch size={18} />
      </ActionIcon>
      <Spotlight
        store={paletteStore}
        shortcut="mod + K"
        query={query}
        onQueryChange={setQuery}
        actions={[pages]}
        filter={filter}
        nothingFound={t("appShell.palette.nothingFound")}
        scrollable
        maxHeight={420}
        searchProps={{
          leftSection: <IconSearch size={18} stroke={1.5} />,
          placeholder: t("appShell.palette.placeholder"),
        }}
      />
    </>
  );
}
