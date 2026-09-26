import type { ParseKeys, TFunction } from "i18next";

/**
 * The two Mantine `validate` rules every registry form repeats — a trimmed name of 1..max characters
 * and a trimmed description of at most max characters — written once (the server's counterpart is
 * `infra/validation/Text.kt` `requireNameAndDescription`).
 */
export function nameRule(t: TFunction, key: ParseKeys, max: number) {
  return (value: string) => {
    const v = value.trim();
    return v.length >= 1 && v.length <= max ? null : t(key);
  };
}

export function descriptionRule(t: TFunction, key: ParseKeys, max: number) {
  return (value: string) => (value.trim().length <= max ? null : t(key));
}
