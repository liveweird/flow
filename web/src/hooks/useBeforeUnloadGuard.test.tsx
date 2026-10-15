import { describe, expect, test, vi } from "vitest";
import { render } from "@testing-library/react";
import { useBeforeUnloadGuard } from "./useBeforeUnloadGuard";

function Probe({ dirty, onReader }: { dirty: boolean | (() => boolean); onReader?: (read: () => boolean) => void }) {
  const read = useBeforeUnloadGuard(dirty);
  onReader?.(read);
  return null;
}

function dispatchBeforeUnload() {
  const event = new Event("beforeunload", { cancelable: true });
  const prevent = vi.spyOn(event, "preventDefault");
  window.dispatchEvent(event);
  return prevent;
}

describe("useBeforeUnloadGuard", () => {
  test("prevents beforeunload only while dirty, following the latest value across rerenders", () => {
    const { rerender, unmount } = render(<Probe dirty={false} />);
    expect(dispatchBeforeUnload()).not.toHaveBeenCalled();
    rerender(<Probe dirty />);
    expect(dispatchBeforeUnload()).toHaveBeenCalled();
    rerender(<Probe dirty={false} />);
    expect(dispatchBeforeUnload()).not.toHaveBeenCalled();
    rerender(<Probe dirty />);
    expect(dispatchBeforeUnload()).toHaveBeenCalled();
    // Unmounted while dirty: the listener is gone with the tree.
    unmount();
    expect(dispatchBeforeUnload()).not.toHaveBeenCalled();
  });

  test("accepts a fresh reader and hands the same reader back", () => {
    let flag = false;
    let read: (() => boolean) | undefined;
    render(<Probe dirty={() => flag} onReader={(r) => (read = r)} />);
    expect(read?.()).toBe(false);
    expect(dispatchBeforeUnload()).not.toHaveBeenCalled();
    flag = true;
    expect(read?.()).toBe(true);
    expect(dispatchBeforeUnload()).toHaveBeenCalled();
  });
});
