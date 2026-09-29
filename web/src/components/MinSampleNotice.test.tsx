import { describe, expect, test } from "vitest";
import MinSampleNotice from "./MinSampleNotice";
import { renderWithProviders, screen } from "../test/render";

describe("MinSampleNotice", () => {
  test("names how many items there are and how many are needed", () => {
    renderWithProviders(<MinSampleNotice n={3} minSampleSize={5} />);
    expect(screen.getByRole("note")).toHaveTextContent(
      "Only 3 items in this selection — at least 5 are needed to show percentiles and a histogram.",
    );
  });

  test("one item is singular", () => {
    renderWithProviders(<MinSampleNotice n={1} minSampleSize={5} />);
    expect(screen.getByRole("note")).toHaveTextContent("Only 1 item in this selection");
  });

  test("the share variant names what is withheld", () => {
    renderWithProviders(<MinSampleNotice n={4} minSampleSize={5} subject="share" />);
    expect(screen.getByRole("note")).toHaveTextContent(
      "Only 4 items started in this selection — at least 5 are needed to show a share.",
    );
  });

  test("the thresholds variant says no item is banded", () => {
    renderWithProviders(<MinSampleNotice n={2} minSampleSize={5} subject="thresholds" />);
    expect(screen.getByRole("note")).toHaveTextContent(
      "Only 2 finished items in the window — at least 5 are needed to show thresholds, so no item is banded.",
    );
  });

  test("an empty thresholds window keeps the 'no item is banded' consequence", () => {
    renderWithProviders(<MinSampleNotice n={0} minSampleSize={5} subject="thresholds" />);
    expect(screen.getByRole("note")).toHaveTextContent(
      "No finished item in the window yet, so there are no thresholds and no item is banded.",
    );
  });

  test("an empty selection says there is nothing to measure", () => {
    renderWithProviders(<MinSampleNotice n={0} minSampleSize={5} />);
    expect(screen.getByRole("note")).toHaveTextContent("Nothing to measure in this selection.");
  });
});
