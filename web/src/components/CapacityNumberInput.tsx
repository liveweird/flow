import { useState } from "react";
import { NumberInput, type NumberInputProps } from "@mantine/core";
import { clampTo, DECIMAL_SEPARATORS } from "../utils/capacity";

type Props = Omit<NumberInputProps, "value" | "onChange" | "min" | "max" | "decimalScale"> & {
  /** The committed value; the caller owns it. */
  value: number;
  min: number;
  max: number;
  /** Called with every typed value that parses, already held to the range and to two decimals. */
  onCommit: (value: number) => void;
};

/**
 * A controlled two-decimal number field that commits as you type without fighting the keystrokes.
 * Mantine reports a half-typed "0." or "" as text; feeding the parsed number straight back would
 * erase the dot (or the field) under the cursor, so the field keeps its own text and only the
 * parsed, clamped number goes up. On blur the text snaps back to the committed value.
 */
export default function CapacityNumberInput({ value, min, max, onCommit, ...rest }: Props) {
  const [text, setText] = useState<string | number>(value);
  const [seen, setSeen] = useState(value);
  // An outside change (Reset, a reload) replaces the text; one the user just typed already matches it.
  if (value !== seen) {
    setSeen(value);
    if (Number(text) !== value) setText(value);
  }

  return (
    <NumberInput
      {...rest}
      min={min}
      max={max}
      decimalScale={2}
      allowNegative={false}
      allowedDecimalSeparators={DECIMAL_SEPARATORS}
      value={text}
      onChange={(next) => {
        setText(next);
        const parsed = typeof next === "number" ? next : next === "" ? Number.NaN : Number(next);
        if (Number.isFinite(parsed)) onCommit(clampTo(parsed, min, max));
      }}
      onBlur={(event) => {
        setText(value);
        rest.onBlur?.(event);
      }}
    />
  );
}
