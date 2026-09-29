import { describe, expect, test } from "vitest";
import { reportHref } from "./reportLinks";

describe("reportHref", () => {
  test("keeps the shared params (period, team, member, connection) on every report", () => {
    const search = "lastSprints=3&teamId=2&accountId=a&connectionId=1";
    for (const path of ["/reports/velocity", "/reports/throughput", "/reports/sprint-consistency"]) {
      expect(reportHref(path, search)).toBe(`${path}?${search}`);
    }
  });

  test("drops the report-specific params the target has no control for", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&breakdown=DOMAIN&bucket=MONTH";
    expect(reportHref("/reports/velocity", search)).toBe("/reports/velocity?teamId=2");
    expect(reportHref("/reports/sprint-consistency", search)).toBe("/reports/sprint-consistency?teamId=2");
    // Throughput keeps its own five, and still has no breakdown control.
    expect(reportHref("/reports/throughput", search)).toBe(
      "/reports/throughput?teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH",
    );
  });

  test("an empty query, or one that only held dropped params, is the bare path; foreign params survive", () => {
    expect(reportHref("/reports/velocity", "")).toBe("/reports/velocity");
    expect(reportHref("/reports/velocity", "?bucket=WEEK")).toBe("/reports/velocity");
    expect(reportHref("/reports/velocity", "utm=x&bucket=WEEK")).toBe("/reports/velocity?utm=x");
  });
});
