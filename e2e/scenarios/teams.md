# Teams (the flat-teams registry)

- **Spec**: [tests/teams.spec.ts](../tests/teams.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`); one throwaway user per test created
  through the Flow users page
- **Owns** (exclusive server-side state): its throwaway teams (unique `e2e-team-*` /
  `e2e-ro-team-*` names) and users — all deleted by the end of the file

## Scenario: admin creates a team, renames it, assigns its manager, and deletes it

1. The admin signs in and creates a throwaway user through the Flow users page.
2. On the Teams page they click **New team**, fill a unique name and a description, and Create.
   - *Expected*: the app lands on the new team's page — its name as the heading, "No manager".
3. They click **Edit**, change the name, and Save.
   - *Expected*: the heading shows the new name.
4. They click **Edit** again, search the **Manager** picker for the throwaway user, pick them, and Save.
   - *Expected*: the details page reads "Manager: <the user's name>".
5. Back on the Teams list they filter by the new name and delete the team from its row menu.
   - *Expected*: the row is gone; the throwaway user is deleted afterwards.

## Scenario: a regular user sees the read-only teams list

1. The admin creates a throwaway user and a throwaway team, then signs out.
2. The user signs in and opens the Teams page, filtering by the team's name.
   - *Expected*: the team is listed as a link; there is no **New team** button and no row menu.
3. They open the team.
   - *Expected*: the team page renders with its name as the heading and without the **Edit** button.
4. The admin signs back in and deletes the team and the user.

## Not covered here (and why)

- **Validation and conflicts (empty name, duplicate name, unknown manager)** — pinned by the
  server's `TeamTest` and the SPA's `Teams.test.tsx`/`TeamDetails.test.tsx`; the journey drives
  the happy path only.
