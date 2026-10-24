import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import i18n from "../i18n";
import { roleName } from "./telemetryRole";

const t = i18n.t.bind(i18n) as TFunction;

describe("roleName", () => {
  test("names the three roles and passes an unknown one through", () => {
    expect(roleName(t, "web")).toBe("Web");
    expect(roleName(t, "worker")).toBe("Worker");
    expect(roleName(t, "all")).toBe("Web and worker");
    expect(roleName(t, "batch")).toBe("batch");
  });
});
