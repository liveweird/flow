import type { ComponentProps } from "react";
import { MantineProvider } from "@mantine/core";
import { usePrinting } from "../utils/printing";

/**
 * The app's `MantineProvider`, pinned to the light scheme while the page is being printed (`usePrintLifecycle`
 * flips the store): a dark-scheme print would be pale text on white paper. The user's stored scheme is never touched.
 */
export default function PrintAwareMantineProvider(props: ComponentProps<typeof MantineProvider>) {
  const printing = usePrinting();
  return <MantineProvider {...props} forceColorScheme={printing ? "light" : props.forceColorScheme} />;
}
