# The ADMIN telemetry panel

- **Spec**: [tests/telemetry.spec.ts](../tests/telemetry.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) and one throwaway regular user
- **Owns** (exclusive server-side state): one throwaway DISABLED Jira-stub data source
  (`e2e-telemetry-ds-*`, created through the API in the second test, never synced so no DERIVE can
  disturb another spec, deleted by the afterEach) and one throwaway user (the fourth test, deleted
  by the test and by the afterEach). The panel's open flag, expanded sections and range are DEVICE-level
  localStorage. Every test first waits, through the API (bounded, 90 s), until the sampler has taken its
  first sample and the first storage measurement (the first tick comes seconds after the app starts), and exactly ONE live (non-stale) process is listed: a stack rebuilt within 15 minutes still lists the previous container's process as stale, so the Memory locators are scoped to the live instance by name.

## Scenario: an admin opens the telemetry panel from the account menu and reads the Memory section

1. The admin signs in and opens the account menu, then picks **Telemetry panel**.
   - *Expected*: an aside landmark named "Telemetry" appears beside the page with an h2 "Telemetry".
2. They read the **Memory** section (open by default).
   - *Expected*: the live instance's "Memory of <instance>" table whose Container limit row reads 768 MB (the compose
     app's cgroup) and whose Share of the limit row shows a percentage.
3. They open the account menu again.
   - *Expected*: the Telemetry panel item is marked as on (`aria-current`).

## Scenario: the Storage and Sync sections show the database and the data sources

1. The admin has a disabled data source (made through the API) and opens the panel.
2. They open **Storage**.
   - *Expected*: the "Database size per schema" table has a Database row in MB or GB and a
     "Schema raw" row; the "Estimated size per data source" table lists the data source.
3. They open **Sync**.
   - *Expected*: the "Sync health per data source" row for the data source reads "Never" (no good sync
     yet) and "Disabled".

## Scenario: the open telemetry panel survives a reload and closes with its own button

1. The admin opens the panel and expands **Storage**, then reloads the page.
   - *Expected*: the panel is still open and Storage is still expanded.
2. They press the panel's **Close** button and reload.
   - *Expected*: no panel before or after the reload.

## Scenario: a regular user has no telemetry item in the account menu

1. The admin creates a throwaway user and signs out; the user signs in and opens the account menu.
   - *Expected*: Change password is there, "Telemetry panel" is not.
2. They store the open flag by hand (`flow.viewSettings.telemetry.panelOpen` = true) and reload.
   - *Expected*: still no panel.
3. The admin signs back in and deletes the user.

## Scenario: the open telemetry panel has no WCAG A/AA violations

1. The admin opens the panel and expands every section.
   - *Expected*: the Memory table, its trend chart group and the storage table are visible.
2. An axe WCAG A/AA scan of the page runs (colour contrast included).
   - *Expected*: no violations.
