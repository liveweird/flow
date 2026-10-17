import { describe, expect, test } from "vitest";
import { clearSession } from "../api/session";
import { clearRememberedFilters, writeRememberedQuery } from "./useStoredState";

const PREFIX = "flow.viewSettings.";

function seedMemories() {
  writeRememberedQuery("reports.throughput", "teamId=1");
  writeRememberedQuery("reports.deepDive", "epicId=FLO-1");
  writeRememberedQuery("capacity", "teamId=3");
  localStorage.setItem(`${PREFIX}reports.teamId`, "2");
  // Not remembered filters: a list page's text filter, a panel flag, a layer choice, another storage family.
  localStorage.setItem(`${PREFIX}users.filter.q`, JSON.stringify("ann"));
  localStorage.setItem(`${PREFIX}reports.commitments.filtersOpen`, "true");
  localStorage.setItem(`${PREFIX}reports.deepDive.layers`, "{}");
  localStorage.setItem("flow.lang", "pl");
}

function keptKeys() {
  return Object.keys(localStorage).sort();
}

describe("remembered filters end with the session", () => {
  test("clearRememberedFilters removes every screen's memory and the shared team, nothing else", () => {
    seedMemories();
    clearRememberedFilters();
    expect(keptKeys()).toEqual([
      "flow.lang",
      `${PREFIX}reports.commitments.filtersOpen`,
      `${PREFIX}reports.deepDive.layers`,
      `${PREFIX}users.filter.q`,
    ]);
  });

  test("signing out (clearSession, which logout and a definitive rejection both run) clears them too", () => {
    seedMemories();
    localStorage.setItem("flow.auth.token", "t");
    clearSession();
    expect(localStorage.getItem("flow.auth.token")).toBeNull();
    expect(localStorage.getItem(`${PREFIX}reports.throughput.filter`)).toBeNull();
    expect(localStorage.getItem(`${PREFIX}capacity.filter`)).toBeNull();
    expect(localStorage.getItem(`${PREFIX}reports.teamId`)).toBeNull();
    expect(localStorage.getItem(`${PREFIX}users.filter.q`)).not.toBeNull();
  });

  test("is best-effort: a failing storage does not throw", () => {
    const original = Storage.prototype.removeItem;
    Storage.prototype.removeItem = () => {
      throw new Error("denied");
    };
    try {
      seedMemories();
      expect(() => clearRememberedFilters()).not.toThrow();
    } finally {
      Storage.prototype.removeItem = original;
    }
  });
});
