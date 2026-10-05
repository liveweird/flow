import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { withDeadline } from "./metrics";

describe("withDeadline — the 30 s deadline survives a caller's signal", () => {
  const originalAny = AbortSignal.any;
  const originalTimeout = AbortSignal.timeout;
  let deadline: AbortController;

  beforeEach(() => {
    deadline = new AbortController();
    Object.defineProperty(AbortSignal, "timeout", { value: () => deadline.signal, configurable: true, writable: true });
  });

  afterEach(() => {
    Object.defineProperty(AbortSignal, "any", { value: originalAny, configurable: true, writable: true });
    Object.defineProperty(AbortSignal, "timeout", { value: originalTimeout, configurable: true, writable: true });
    vi.restoreAllMocks();
  });

  /** The manual-chaining path: a runtime without `AbortSignal.any`. */
  function withoutAny() {
    Object.defineProperty(AbortSignal, "any", { value: undefined, configurable: true, writable: true });
  }

  test("no caller signal: the deadline itself", () => {
    expect(withDeadline()).toBe(deadline.signal);
  });

  test.each([
    ["with AbortSignal.any", () => {}],
    ["without AbortSignal.any (manual chain)", withoutAny],
  ])("either source aborts the combined signal — %s", (_name, setup) => {
    setup();
    const caller = new AbortController();
    const combined = withDeadline(caller.signal)!;
    expect(combined.aborted).toBe(false);
    caller.abort(new Error("unmounted"));
    expect(combined.aborted).toBe(true);

    deadline = new AbortController();
    const second = new AbortController();
    const again = withDeadline(second.signal)!;
    expect(again.aborted).toBe(false);
    deadline.abort(new DOMException("timed out", "TimeoutError"));
    expect(again.aborted).toBe(true);
    expect((again.reason as DOMException).name).toBe("TimeoutError");
  });

  test("manual chain: an already-aborted caller signal aborts at once", () => {
    withoutAny();
    const caller = new AbortController();
    caller.abort(new Error("gone"));
    expect(withDeadline(caller.signal)!.aborted).toBe(true);
  });
});
