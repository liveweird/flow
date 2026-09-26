import { readFile } from "node:fs/promises";
import openapiTS, { astToString, COMMENT_HEADER } from "openapi-typescript";

// Generate in memory: this gate must never overwrite a developer's committed/edited types.
const spec = new URL("../../server/src/main/resources/openapi/documentation.yaml", import.meta.url);
const target = new URL("../src/api/schema.ts", import.meta.url);
const generated = COMMENT_HEADER + astToString(await openapiTS(spec));
if (generated !== await readFile(target, "utf8")) {
  console.error("API types are stale. Run cd web && npm run gen:api and commit src/api/schema.ts.");
  process.exitCode = 1;
} else {
  console.log("API types match the published OpenAPI contract.");
}
