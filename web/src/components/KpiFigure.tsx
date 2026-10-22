import type { ReactNode } from "react";
import { Text } from "@mantine/core";

/** The one headline-figure style of the report tiles (the big bold number under a label), so a figure looks the same wherever it is drawn. */
export default function KpiFigure({ children }: { children: ReactNode }) {
  return (
    <Text fz={28} fw={700} lh={1.2}>
      {children}
    </Text>
  );
}
