import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import {
  EMPTY_USER_FORM,
  MAX_EMAIL_LENGTH,
  MAX_PASSWORD_BYTES,
  MAX_USER_NAME_LENGTH,
  MIN_PASSWORD_LENGTH,
  rolesOf,
  userFormValidation,
} from "./userForm";

const t = i18n.t;
const rules = userFormValidation(t);

describe("userFormValidation", () => {
  test("name requires 1–50 characters after trimming", () => {
    expect(rules.name("Alice")).toBeNull();
    expect(rules.name("  padded  ")).toBeNull();
    expect(rules.name("a".repeat(MAX_USER_NAME_LENGTH))).toBeNull();

    expect(rules.name("")).toBe(t("users.validation.nameLength"));
    expect(rules.name("   ")).toBe(t("users.validation.nameLength"));
    expect(rules.name("a".repeat(MAX_USER_NAME_LENGTH + 1))).toBe(t("users.validation.nameLength"));
  });

  test("email must look like an address with a dotted domain", () => {
    expect(rules.email("alice@example.com")).toBeNull();
    expect(rules.email("  alice@example.com  ")).toBeNull(); // trimmed

    expect(rules.email("")).toBe(t("users.validation.emailInvalid"));
    expect(rules.email("not-an-email")).toBe(t("users.validation.emailInvalid"));
    expect(rules.email("no-dot@domain")).toBe(t("users.validation.emailInvalid"));
    expect(rules.email("two@@example.com")).toBe(t("users.validation.emailInvalid"));
    expect(rules.email("spaced name@example.com")).toBe(t("users.validation.emailInvalid"));
  });

  test("an email over MAX_EMAIL_LENGTH is rejected as too long", () => {
    const local = "a".repeat(MAX_EMAIL_LENGTH - "@example.com".length);
    expect(rules.email(`${local}@example.com`)).toBeNull(); // exactly at the limit
    expect(rules.email(`x${local}@example.com`)).toBe(t("users.validation.emailTooLong"));
  });
});

describe("rolesOf", () => {
  test("maps the admin checkbox to the additive roles set", () => {
    expect(rolesOf({ name: "A", email: "a@b.co", admin: true, language: "en" as const })).toEqual(["ADMIN"]);
    expect(rolesOf({ name: "A", email: "a@b.co", admin: false, language: "en" as const })).toEqual([]);
  });
});

describe("shared vocabulary", () => {
  test("EMPTY_USER_FORM is the blank non-admin form", () => {
    expect(EMPTY_USER_FORM).toEqual({ name: "", email: "", admin: false, language: "en" });
  });

  test("the limits mirror the server's (users/Validation.kt + auth/Passwords.kt)", () => {
    expect(MAX_USER_NAME_LENGTH).toBe(50);
    expect(MAX_EMAIL_LENGTH).toBe(254);
    expect(MIN_PASSWORD_LENGTH).toBe(10);
    expect(MAX_PASSWORD_BYTES).toBe(71);
  });
});
