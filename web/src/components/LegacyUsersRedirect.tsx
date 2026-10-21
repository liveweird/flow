import { Navigate, useLocation } from "react-router-dom";
import { flowUsersPath, legacyUsersPath } from "../utils/flowUserLinks";

/**
 * `/users…` was the Flow users pages' address before "Users" became "Flow users" (to tell Flow's own accounts from Jira
 * users): a bookmark or an emailed link keeps working by replacing the entry with the same tail under `/flow-users`
 * (`/new`, `/7/edit`, `/7/features`), keeping the query string and the hash.
 */
export default function LegacyUsersRedirect() {
  const { pathname, search, hash } = useLocation();
  const tail = pathname.startsWith(legacyUsersPath) ? pathname.slice(legacyUsersPath.length) : "";
  return <Navigate to={`${flowUsersPath}${tail}${search}${hash}`} replace />;
}
