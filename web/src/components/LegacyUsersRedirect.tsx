import { Navigate, useLocation, useParams } from "react-router-dom";
import { flowUsersPath } from "../utils/flowUserLinks";

/**
 * `/users…` was the Flow users pages' address before "Users" became "Flow users" (to tell Flow's own accounts from Jira
 * users): a bookmark or an emailed link keeps working by replacing the entry with the same tail under `/flow-users`
 * (`/new`, `/7/edit`, `/7/features`), keeping the query string and the hash. The tail is the route's own `*` match, so a
 * case-variant old link (`/Users/7/edit`, which the router matches case-insensitively) keeps it too.
 */
export default function LegacyUsersRedirect() {
  const { search, hash } = useLocation();
  const rest = useParams()["*"] ?? "";
  const tail = rest ? `/${rest}` : "";
  return <Navigate to={`${flowUsersPath}${tail}${search}${hash}`} replace />;
}
