import { Navigate, useLocation, useParams } from "react-router-dom";
import { costsPath } from "../utils/reportLinks";

/**
 * `/reports/cost-matrix…` was the Costs page's address before the "Cost matrix" became "Costs": a bookmark or an emailed link keeps working by
 * replacing the entry with the same tail under `/reports/costs`, keeping the query string and the hash. The tail is the route's own `*` match, so
 * a case-variant old link (`/reports/Cost-Matrix`, which the router matches case-insensitively) is covered too. A parameter the page no longer
 * has (`split`) is ignored by the new page like any unknown one.
 */
export default function LegacyCostMatrixRedirect() {
  const { search, hash } = useLocation();
  const rest = useParams()["*"] ?? "";
  const tail = rest ? `/${rest}` : "";
  return <Navigate to={`${costsPath}${tail}${search}${hash}`} replace />;
}
