import { describe, expect, test } from "vitest";
import {
  MAX_DEEP_DIVE_EPICS,
  MAX_DEEP_DIVE_ISSUES,
  MAX_DEEP_DIVE_SPRINTS,
  applyDeepDiveSelection,
  applyDeepDiveView,
  cleanSearchText,
  compareIssueKeys,
  deepDiveMode,
  deepDiveQuery,
  hasDeepDiveParams,
  normalizeDeepDiveSelection,
  parseDeepDiveSelection,
  parseDeepDiveView,
  serializeDeepDiveSelection,
  type DeepDiveSelection,
} from "./deepDiveFilter";

const parse = (query: string) =>
  parseDeepDiveSelection(new URLSearchParams(query));

describe("parseDeepDiveSelection / serializeDeepDiveSelection", () => {
  test("each of the three modes round-trips through the URL", () => {
    const sprints: DeepDiveSelection = {
      domain: "FLO",
      sprintIds: [4, 5],
      connectionId: 2,
      from: "2026-01-01",
      to: "2026-03-31",
    };
    const epics: DeepDiveSelection = { epicIds: ["FLO-2", "FLO-10"] };
    const tasks: DeepDiveSelection = {
      epicIds: ["FLO-33"],
      issueIds: ["FLO-34", "FLO-35"],
    };
    for (const selection of [sprints, epics, tasks]) {
      expect(
        parseDeepDiveSelection(serializeDeepDiveSelection(selection)),
      ).toEqual(selection);
    }
    expect([sprints, epics, tasks].map(deepDiveMode)).toEqual([
      "SPRINTS",
      "EPICS",
      "TASKS",
    ]);
  });

  test("an empty query is the empty selection with no mode", () => {
    expect(parse("")).toEqual({});
    expect(deepDiveQuery({})).toBe("");
    expect(deepDiveMode({})).toBeNull();
  });

  test("repeated values are sorted (numbers numerically, issue keys by number) and de-duplicated", () => {
    expect(
      parse("domain=FLO&sprintId=10&sprintId=9&sprintId=10&sprintId=100"),
    ).toEqual({ domain: "FLO", sprintIds: [9, 10, 100] });
    expect(
      parse("epicId=FLO-10&epicId=FLO-2&epicId=FLO-10&epicId=ABC-7"),
    ).toEqual({ epicIds: ["ABC-7", "FLO-2", "FLO-10"] });
    expect(
      parse("epicId=FLO-1&issueId=FLO-11&issueId=FLO-2&issueId=FLO-11"),
    ).toEqual({ epicIds: ["FLO-1"], issueIds: ["FLO-2", "FLO-11"] });
    expect(compareIssueKeys("FLO-2", "FLO-10")).toBeLessThan(0);
    expect(compareIssueKeys("FLO-10", "FLO-2")).toBeGreaterThan(0);
    expect(compareIssueKeys("AAA-9", "FLO-1")).toBeLessThan(0);
    expect(compareIssueKeys("odd", "odd")).toBe(0);
    expect(compareIssueKeys("b", "a")).toBeGreaterThan(0);
  });

  test("serializes in one canonical key order with repeated keys, whatever order the selection was built in", () => {
    const query = deepDiveQuery({
      to: "2026-03-31",
      connectionId: 2,
      sprintIds: [5, 4],
      domain: "FLO",
      from: "2026-01-01",
    });
    expect(query).toBe(
      "domain=FLO&sprintId=4&sprintId=5&connectionId=2&from=2026-01-01&to=2026-03-31",
    );
    expect(
      deepDiveQuery({
        epicIds: ["FLO-1"],
        issueIds: ["FLO-3", "FLO-2"],
        from: "2026-01-01",
      }),
    ).toBe("epicId=FLO-1&issueId=FLO-2&issueId=FLO-3&from=2026-01-01");
  });

  test("invalid values are dropped, valid siblings kept", () => {
    expect(
      parse(
        "domain=FLO&sprintId=abc&sprintId=-3&sprintId=0&sprintId=1.5&sprintId=7",
      ),
    ).toEqual({ domain: "FLO", sprintIds: [7] });
    expect(parse("domain=FLO&sprintId=abc")).toEqual({});
    expect(parse("epicId=%20&epicId=FLO-1&epicId=bad%00key")).toEqual({
      epicIds: ["FLO-1"],
    });
    // C1 controls (U+0080..U+009F) are rejected like C0 ones and DEL.
    expect(parse("epicId=FLO-1&epicId=bad%C2%85key&epicId=del%7Fkey")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse("epicId=FLO-1&connectionId=0")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse("epicId=FLO-1&connectionId=x")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse(`epicId=${"x".repeat(201)}`)).toEqual({});
  });

  test("values are trimmed", () => {
    expect(parse("domain=%20FLO%20&sprintId=1")).toEqual({
      domain: "FLO",
      sprintIds: [1],
    });
    expect(parse("epicId=%20FLO-1%20")).toEqual({ epicIds: ["FLO-1"] });
  });

  test("dates: a malformed or inverted or over-wide pair is dropped, a lone valid bound is kept", () => {
    expect(parse("epicId=FLO-1&from=2026-02-30")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse("epicId=FLO-1&from=2026-03-01&to=2026-02-01")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse("epicId=FLO-1&from=2020-01-01&to=2026-01-01")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse("epicId=FLO-1&from=2026-01-01&to=2029-01-05")).toEqual({
      epicIds: ["FLO-1"],
    });
    expect(parse("epicId=FLO-1&from=2026-01-01&to=2029-01-04")).toEqual({
      epicIds: ["FLO-1"],
      from: "2026-01-01",
      to: "2029-01-04",
    });
    expect(parse("epicId=FLO-1&from=2026-01-01")).toEqual({
      epicIds: ["FLO-1"],
      from: "2026-01-01",
    });
    expect(parse("epicId=FLO-1&to=2026-01-01")).toEqual({
      epicIds: ["FLO-1"],
      to: "2026-01-01",
    });
    expect(parse("epicId=FLO-1&to=nope")).toEqual({ epicIds: ["FLO-1"] });
  });

  test("a lone range, connection or domain is kept without any mode", () => {
    expect(parse("connectionId=3&from=2026-01-01")).toEqual({
      connectionId: 3,
      from: "2026-01-01",
    });
    expect(deepDiveMode(parse("connectionId=3"))).toBeNull();
  });
});

describe("one mode, inferred as the server does", () => {
  test("sprint ids need a domain, and a domain needs sprint ids", () => {
    expect(parse("sprintId=4")).toEqual({});
    expect(parse("domain=FLO")).toEqual({});
    expect(parse("sprintId=4&epicId=FLO-1")).toEqual({ epicIds: ["FLO-1"] });
  });

  test("a mix keeps one mode by precedence: sprints over tasks over epics", () => {
    expect(parse("domain=FLO&sprintId=4&epicId=FLO-1&issueId=FLO-2")).toEqual({
      domain: "FLO",
      sprintIds: [4],
    });
    expect(parse("epicId=FLO-1&issueId=FLO-2&domain=FLO")).toEqual({
      epicIds: ["FLO-1"],
      issueIds: ["FLO-2"],
    });
  });

  test("issue ids need exactly ONE epic: with several epics they are dropped (mode EPICS), alone they are dropped", () => {
    expect(parse("epicId=FLO-1&epicId=FLO-2&issueId=FLO-3")).toEqual({
      epicIds: ["FLO-1", "FLO-2"],
    });
    expect(parse("issueId=FLO-3")).toEqual({});
    expect(deepDiveMode(parse("epicId=FLO-1&epicId=FLO-2&issueId=FLO-3"))).toBe(
      "EPICS",
    );
    expect(deepDiveMode(parse("epicId=FLO-1&issueId=FLO-3"))).toBe("TASKS");
  });

  test("a value group over its limit is dropped whole, not truncated", () => {
    const sprints = Array.from(
      { length: MAX_DEEP_DIVE_SPRINTS + 1 },
      (_, i) => `sprintId=${i + 1}`,
    ).join("&");
    expect(parse(`domain=FLO&${sprints}`)).toEqual({});
    expect(
      parse(`domain=FLO&${sprints.split("&").slice(1).join("&")}`).sprintIds,
    ).toHaveLength(MAX_DEEP_DIVE_SPRINTS);
    const epics = Array.from(
      { length: MAX_DEEP_DIVE_EPICS + 1 },
      (_, i) => `epicId=FLO-${i + 1}`,
    ).join("&");
    expect(parse(epics)).toEqual({});
    expect(parse(epics.split("&").slice(1).join("&")).epicIds).toHaveLength(
      MAX_DEEP_DIVE_EPICS,
    );
    const issues = Array.from(
      { length: MAX_DEEP_DIVE_ISSUES + 1 },
      (_, i) => `issueId=FLO-${i + 2}`,
    ).join("&");
    expect(parse(`epicId=FLO-1&${issues}`)).toEqual({ epicIds: ["FLO-1"] });
    expect(
      parse(`epicId=FLO-1&${issues.split("&").slice(1).join("&")}`).issueIds,
    ).toHaveLength(MAX_DEEP_DIVE_ISSUES);
  });

  test("normalizing a hand-built selection applies the same rules (and omits absent fields)", () => {
    expect(
      normalizeDeepDiveSelection({
        domain: "FLO",
        sprintIds: [],
        epicIds: ["FLO-1"],
        issueIds: [],
      }),
    ).toEqual({ epicIds: ["FLO-1"] });
    expect(
      normalizeDeepDiveSelection({ sprintIds: [3, 3, 1], domain: "FLO" }),
    ).toEqual({ domain: "FLO", sprintIds: [1, 3] });
    expect(
      Object.keys(
        normalizeDeepDiveSelection({
          epicIds: ["FLO-1"],
          connectionId: undefined,
        }),
      ),
    ).toEqual(["epicIds"]);
    expect(
      deepDiveQuery({ domain: "FLO", sprintIds: [1], epicIds: ["FLO-9"] }),
    ).toBe("domain=FLO&sprintId=1");
  });
});

describe("applyDeepDiveSelection / hasDeepDiveParams", () => {
  test("replaces the managed params and leaves foreign ones (and their order) alone", () => {
    const base = new URLSearchParams(
      "foo=1&domain=OLD&sprintId=1&sprintId=2&epicId=X&bar=2&from=2026-01-01",
    );
    const next = applyDeepDiveSelection(base, {
      epicIds: ["FLO-2", "FLO-1"],
      connectionId: 3,
    });
    expect(next.toString()).toBe(
      "foo=1&bar=2&epicId=FLO-1&epicId=FLO-2&connectionId=3",
    );
    expect(base.get("domain")).toBe("OLD");
  });

  test("an empty selection clears every managed param", () => {
    expect(
      applyDeepDiveSelection(
        new URLSearchParams("a=b&issueId=FLO-1&to=2026-01-01"),
        {},
      ).toString(),
    ).toBe("a=b");
  });

  test("hasDeepDiveParams is true for any managed key, valid or not", () => {
    expect(hasDeepDiveParams(new URLSearchParams("sprintId=zzz"))).toBe(true);
    expect(hasDeepDiveParams(new URLSearchParams("to=2026-01-01"))).toBe(true);
    expect(
      hasDeepDiveParams(new URLSearchParams("teamId=1&lastSprints=3")),
    ).toBe(false);
  });
});

describe("cleanSearchText", () => {
  test("strips C0/DEL/C1 control characters, trims, and omits a blank result", () => {
    expect(cleanSearchText("  Sprint\u0000 4\u0085 ")).toBe("Sprint 4");
    expect(cleanSearchText("\u007f \u009f")).toBeUndefined();
    expect(cleanSearchText("")).toBeUndefined();
    expect(cleanSearchText(undefined)).toBeUndefined();
    expect(cleanSearchText("épic")).toBe("épic");
  });
});

describe("the page view (view=burnup)", () => {
  test("only view=burnup opens the burn-up; anything else is the matrix", () => {
    expect(parseDeepDiveView(new URLSearchParams("view=burnup"))).toBe("burnup");
    expect(parseDeepDiveView(new URLSearchParams(""))).toBe("matrix");
    expect(parseDeepDiveView(new URLSearchParams("view=matrix"))).toBe("matrix");
    expect(parseDeepDiveView(new URLSearchParams("view=BURNUP"))).toBe("matrix");
  });

  test("writing it keeps the selection, and the matrix is the absence of the param", () => {
    const base = new URLSearchParams("epicId=FLO-1&from=2026-01-01");
    const burnup = applyDeepDiveView(base, "burnup");
    expect(burnup.toString()).toBe("epicId=FLO-1&from=2026-01-01&view=burnup");
    expect(applyDeepDiveView(burnup, "matrix").toString()).toBe("epicId=FLO-1&from=2026-01-01");
    expect(base.has("view")).toBe(false);
  });

  test("view is not a selection param: the selection round-trip and its query ignore and keep it", () => {
    const params = new URLSearchParams("view=burnup&epicId=FLO-2&epicId=FLO-1");
    expect(hasDeepDiveParams(new URLSearchParams("view=burnup"))).toBe(false);
    expect(deepDiveQuery(parseDeepDiveSelection(params))).toBe("epicId=FLO-1&epicId=FLO-2");
    const next = applyDeepDiveSelection(params, { epicIds: ["FLO-3"], connectionId: 2 });
    expect(next.toString()).toBe("view=burnup&epicId=FLO-3&connectionId=2");
    expect(parseDeepDiveView(next)).toBe("burnup");
  });
});
