import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * Mounts [children] once the box is within [margin] of the viewport, and keeps them mounted from then on. Home draws some thirty
 * recharts instances: letting the ones far below the fold wait until the reader scrolls toward them keeps the first paint (and a
 * drill's open/close) cheap. The box holds [height] meanwhile, so the page does not jump when a chart arrives, and the chart's TABLE
 * (the accessible text alternative) is never deferred -- only its picture. Without `IntersectionObserver` (an old browser, a test
 * runner) everything mounts at once.
 */
export default function NearViewport({ height, margin = "400px", children }: { height: number; margin?: string; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const supported = typeof IntersectionObserver !== "undefined";
  const [near, setNear] = useState(!supported);
  useEffect(() => {
    const element = ref.current;
    if (near || element === null) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) setNear(true);
      },
      { rootMargin: margin },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [near, margin]);
  return (
    <div ref={ref} style={near ? undefined : { minHeight: height }}>
      {near ? children : null}
    </div>
  );
}
