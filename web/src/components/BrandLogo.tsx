import { useComputedColorScheme } from "@mantine/core";

/** The theme-aware Flow logo (light/dark SVG variants from public/). */
export default function BrandLogo({ size = 28 }: { size?: number }) {
  const computed = useComputedColorScheme("light", { getInitialValueInEffect: true });
  const src = computed === "dark" ? "/logo-dark.svg" : "/logo-light.svg";
  // alt="" — decorative: an adjacent "Flow" wordmark names the brand at every call site,
  // so an alt here would make screen readers announce it twice.
  return <img src={src} alt="" style={{ height: size, width: size, display: "block" }} />;
}
