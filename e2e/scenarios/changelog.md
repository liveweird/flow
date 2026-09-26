# The changelog page and the what's-new dot

- **Spec**: [tests/changelog.spec.ts](../tests/changelog.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) — any authenticated user would do
- **Owns** (exclusive server-side state): none — the changelog is a build-time artifact and
  the what's-new state is device-level localStorage, so a fresh browser context always
  starts unseen and nothing touches the server

## Scenario: the what's-new dot leads to the changelog and clears once it is read

1. The admin signs in on a fresh browser context.
   - *Expected*: the header account-menu button carries the red what's-new dot (this device has
     never seen the current version).
2. They click the version stamp.
   - *Expected*: the **Changelog** page opens; reading it marks the version seen, so the
     dot is gone.
3. They look at the newest entry.
   - *Expected*: it shows a version (`vX.Y.Z`) and a release date (`YYYY-MM-DD`) — no exact
     version is asserted, so releases never break this spec.
4. They navigate back to the Home page.
   - *Expected*: the dot stays cleared.

## Not covered here (and why)

- **Language switching** — this spec runs as the SEED ADMIN, and the switcher writes the
  server-side user language (V18); seeded accounts must stay English. The PL page title and
  bodies are pinned by `Changelog.test.tsx`, and the language journey lives in
  [i18n.md](i18n.md) on a throwaway user.
- **The exact entry list, body markdown rendering, EN/PL body selection, the seen-state
  storage shape, corrupt-state tolerance, the version pin `CHANGELOG[0].version ===
  APP_VERSION`** — pinned by the unit suites (`changelog/entries.test.ts`,
  `pages/Changelog.test.tsx`, `components/VersionStamp.test.tsx`, the `App.test.tsx`
  nav/dot cases).
- **The Changelog nav link** — same navigation surface as the stamp; the App unit tests pin
  both affordances.
