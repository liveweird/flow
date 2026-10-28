import type { ReactNode } from "react";
import { Text } from "@mantine/core";

/** A trend tooltip's heading: the point's title and, beneath it in regular weight, a second line (the team reference). */
export default function TrendTooltipLabel({ title, note }: { title: ReactNode; note: string }) {
  return (
    <>
      {title}
      <br />
      <Text span fw={400} inherit>
        {note}
      </Text>
    </>
  );
}
