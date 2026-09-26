# App shell chrome: the icon rail and the theme switch

- **Spec**: [tests/shell.spec.ts](../tests/shell.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`)
- **Owns** (exclusive server-side state): nothing — both settings are DEVICE-level
  localStorage, never synced server-side (unlike the per-user language in
  [i18n.md](i18n.md))

## Scenario: the icon rail collapses and expands the navbar, remembered across reload

1. The admin signs in.
   - *Expected*: the header's "Show or hide the navigation" toggle carries `data-expanded`,
     and the sidebar shows full-label leaves (**Teams** as a text link).
2. They click the toggle.
   - *Expected*: `data-expanded` is gone (rail mode) — the same leaf is still found by its
     accessible name, now carried as an `aria-label` on an icon-only link.
3. They reload the page.
   - *Expected*: the navbar stays collapsed — the choice persists on this device.
4. They click the toggle again.
   - *Expected*: `data-expanded` returns — the navbar expands back to full labels.

## Scenario: switching the theme in the account menu updates the color scheme and persists across reload

1. The admin opens the account menu and picks **Dark** in the theme control.
   - *Expected*: `<html data-mantine-color-scheme="dark">`.
2. They reload the page.
   - *Expected*: still dark — the device cached the choice.
3. They open the account menu again and pick **Light**.
   - *Expected*: `<html data-mantine-color-scheme="light">`.

## Not covered here (and why)

- **The System (auto) option and OS-preference resolution** — needs color-scheme emulation
  control per test; the light/dark round trip already proves the control writes and the
  provider reads back the stored value.
- **Colour tokens and contrast** — pinned by `web/src/theme.test.ts`; the accessibility sweep
  (`accessibility.spec.ts`) runs `color-contrast` against the rendered (default, light) pages.
