# Accessibility smoke (axe, WCAG A/AA)

- **Spec**: [tests/accessibility.spec.ts](../tests/accessibility.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) for the authenticated pages; an
  anonymous visitor for the login screen
- **Owns** (exclusive server-side state): one fixture team (unique `e2e-axe-*` name), created
  through the API before the detail-page block and deleted after it; the list/form pages and
  the login screen are read-only

This is the registered template-title exception (see README.md): one `test()` per page is
generated from a list, so a single scenario section stands in for each list.

## Scenario: login screen has no WCAG A/AA violations

1. An anonymous visitor opens `/login` and waits for the sign-in form.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero
     violations.

## Scenario: `<path>` has no WCAG A/AA violations

1. The admin signs in, opens `<path>`, and waits for its heading.
   - *Expected*: an axe scan (same tags) reports zero violations.

The list covers the Home page, the Teams list, the Users list and its create form, the
ADMIN feature-flags screen, the Data sources list, Change password and the Changelog — see
`tests/accessibility.spec.ts`'s `AUTHED_PAGES` list for the current set.

## Scenario: `<detail page>` has no WCAG A/AA violations

1. Before the block, the admin's API session seeds one team (`e2e-axe-team-*`).
2. The admin signs in and opens the page — the team's roster page, their own edit-user and
   user-features pages — and waits for its settled element (the heading).
   - *Expected*: an axe scan (same tags) reports zero violations.
3. After the block, the API session deletes the team.

## Scenario: the reset-password page has no WCAG A/AA violations

1. An anonymous visitor opens `/reset-password` and waits for its heading.
   - *Expected*: zero violations.

## Scenario: the not-found page has no WCAG A/AA violations

1. The admin opens an address that matches no route.
   - *Expected*: the not-found page renders inside the shell with zero violations.

## Scenario: `<overlay>` has no WCAG A/AA violations

1. The admin opens the overlay — the New team editor modal from the Teams page — and waits
   for the dialog.
   - *Expected*: an axe scan scoped to the dialog reports zero violations (focus trap, `aria-modal`,
     labelled close button, contrast).

## Not covered here (and why)

- **Interactive journeys mid-flight** (the one-time password reveal, a confirm mid-transition)
  — those need the journey that produces them; covered by their own journey specs where
  reachable through the ordinary flow.
- **Colour tokens themselves** — the ratios are pinned in `web/src/theme.test.ts`; axe here
  verifies the rendered pages honour them.
