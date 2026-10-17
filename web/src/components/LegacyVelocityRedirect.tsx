import { Navigate, useLocation } from "react-router-dom";
import { commitmentsPath } from "../utils/reportLinks";

/**
 * `/reports/velocity` was Report 1's address before it was renamed Commitments ("velocity" now means what a team
 * DELIVERS per sprint): bookmarks and shared links keep working by replacing the entry with the new route, keeping
 * the query string (the filters) and the hash.
 */
export default function LegacyVelocityRedirect() {
  const { search, hash } = useLocation();
  return <Navigate to={`${commitmentsPath}${search}${hash}`} replace />;
}
