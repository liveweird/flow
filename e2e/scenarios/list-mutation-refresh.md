# List mutation refresh races

The suite owns uniquely named records, created through the real API. It signs in as the seed
administrator and never changes existing user records. Cleanup releases held responses and
deletes only owned records through normal APIs, even when an assertion fails.

## Scenario: a pending Users filter cannot restore a deleted user

1. Create a uniquely named user, then sign in as administrator.
2. Load a settled list containing it; change the name filter to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter.
6. Remove the owned record through the API and release the intercepted request.

## Scenario: a pending Teams filter cannot restore a deleted team

1. Create a uniquely named team, then sign in as administrator.
2. Load a settled list containing it; change the name filter to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter.
6. Remove the owned record through the API and release the intercepted request.

## Scenario: a pending Feature Flags filter cannot undo a successful toggle

1. Create a throwaway user with MFA disabled; sign in as the seed administrator.
2. Filter Feature Flags to that user and verify its MFA switch is off.
3. Change to another matching name filter and hold its first real response.
4. Toggle MFA on while the previous row remains visible; verify PUT 204 and persisted MFA enabled.
5. Release the response captured before the update.
   - *Expected*: the switch is enabled and on, with the selected name filter preserved.
6. Release the intercepted response and delete only the throwaway user through the API.
