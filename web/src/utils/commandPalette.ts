import { createSpotlight } from "@mantine/spotlight";

/**
 * The command palette's store (v1.19.0), module-level so the header trigger, the ⌘K
 * shortcut, and tests open the same instance. `palette.open()` / `palette.close()` are the
 * imperative handles; `paletteStore` feeds the `<Spotlight>` in components/CommandPalette.tsx.
 */
export const [paletteStore, palette] = createSpotlight();
