import { useDebouncedValue } from "@mantine/hooks";
import { FEATURES, type Feature } from "../api/session";
import { isOneOf, isOneOfOrNull, isString, useStoredState } from "./useStoredState";

/** The persisted view-settings prefix of the /feature-flags screen (filters, paging, sort). */
export const FEATURE_FLAGS_KEY = "featureFlags";

/**
 * The /feature-flags filters: the picked feature, the optional state, and the name/email
 * freetext (persisted under `flow.viewSettings.featureFlags.*`). `debounced` is what the query
 * and the bulk run read, so a click never acts on a slice the table is not showing. `deps` is
 * the paging reset list, `key` the same list as a string (it scopes the bulk "nothing to change" note).
 */
export function useFeatureFlagFilters() {
  const [feature, setFeature] = useStoredState<Feature>(
    `${FEATURE_FLAGS_KEY}.feature`,
    FEATURES[0],
    isOneOf(FEATURES),
  );
  const [state, setState] = useStoredState<string | null>(
    `${FEATURE_FLAGS_KEY}.filter.state`,
    null,
    isOneOfOrNull(["enabled", "disabled"]),
  );
  const [name, setName] = useStoredState(`${FEATURE_FLAGS_KEY}.filter.name`, "", isString);
  const [email, setEmail] = useStoredState(`${FEATURE_FLAGS_KEY}.filter.email`, "", isString);
  const [debouncedName] = useDebouncedValue(name, 300);
  const [debouncedEmail] = useDebouncedValue(email, 300);
  const deps = [feature, state, debouncedName, debouncedEmail];
  return {
    feature,
    setFeature,
    state,
    setState,
    name,
    setName,
    email,
    setEmail,
    debounced: { name: debouncedName, email: debouncedEmail },
    activeCount: (state ? 1 : 0) + (name.trim() ? 1 : 0) + (email.trim() ? 1 : 0),
    deps,
    key: JSON.stringify(deps),
  };
}

export type FeatureFlagFilters = ReturnType<typeof useFeatureFlagFilters>;
