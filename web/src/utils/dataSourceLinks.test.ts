import { describe, expect, test } from "vitest";
import { dataSourceInspectPath, dataSourcePath, dataSourceProfilePath, dataSourcesPath } from "./dataSourceLinks";

describe("dataSourceLinks", () => {
  test("builds the list/detail/profile paths", () => {
    expect(dataSourcesPath).toBe("/data-sources");
    expect(dataSourcePath(1)).toBe("/data-sources/1");
    expect(dataSourceProfilePath(1)).toBe("/data-sources/1/profile");
  });

  test("the inspect path omits the query when no key is given, and encodes one when it is", () => {
    expect(dataSourceInspectPath(1)).toBe("/data-sources/1/inspect");
    expect(dataSourceInspectPath(1, "ENG-123")).toBe("/data-sources/1/inspect?key=ENG-123");
    expect(dataSourceInspectPath(1, "a b")).toBe("/data-sources/1/inspect?key=a%20b");
  });
});
