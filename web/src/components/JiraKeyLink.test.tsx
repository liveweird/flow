import { QueryClient } from "@tanstack/react-query";
import { act } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import JiraKeyLink from "./JiraKeyLink";
import { renderWithProviders, screen } from "../test/render";

const FILTERS_KEY = ["reports", "filters"];
const connections = (...sites: Array<[number, string]>) => ({
  connections: sites.map(([id, siteUrl]) => ({ id, name: `Connection ${id}`, siteUrl })),
});

function clientWith(...sites: Array<[number, string]>) {
  const client = new QueryClient();
  client.setQueryData(FILTERS_KEY, connections(...sites));
  return client;
}

describe("JiraKeyLink", () => {
  test("links the key to {siteUrl}/browse/{KEY} in a new tab, safely, with accessible 'opens in Jira' text and a hidden icon", () => {
    renderWithProviders(<JiraKeyLink issueKey="FLO-12" />, { queryClient: clientWith([1, "https://stub.atlassian.net"]) });
    const link = screen.getByRole("link", { name: "FLO-12 (opens in Jira)" });
    expect(link).toHaveAttribute("href", "https://stub.atlassian.net/browse/FLO-12");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(link.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByText("FLO-12")).toBe(link);
  });

  test("strips a trailing slash from the site and encodes the key", () => {
    renderWithProviders(<JiraKeyLink issueKey="A/B 1" />, { queryClient: clientWith([1, "https://stub.atlassian.net/"]) });
    expect(screen.getByRole("link")).toHaveAttribute("href", "https://stub.atlassian.net/browse/A%2FB%201");
  });

  test("is plain text without a known site: no filters loaded, a blank site, a stranger connection, or two different sites", () => {
    const { unmount } = renderWithProviders(<JiraKeyLink issueKey="FLO-1" />);
    expect(screen.getByText("FLO-1")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    unmount();
    const cases: Array<[Array<[number, string]>, number | undefined]> = [
      [[[1, ""]], undefined],
      [[[1, "https://stub.atlassian.net"]], 7],
      [[[1, "https://stub.atlassian.net"], [2, "https://other.atlassian.net"]], undefined],
    ];
    for (const [sites, connectionId] of cases) {
      const view = renderWithProviders(<JiraKeyLink issueKey="FLO-1" connectionId={connectionId} />, { queryClient: clientWith(...sites) });
      expect(screen.getByText("FLO-1")).toBeInTheDocument();
      expect(screen.queryByRole("link")).not.toBeInTheDocument();
      view.unmount();
    }
  });

  test("a connection id picks its own site even when the connections differ", () => {
    renderWithProviders(<JiraKeyLink issueKey="OPS-3" connectionId={2} />, {
      queryClient: clientWith([1, "https://stub.atlassian.net"], [2, "https://other.atlassian.net"]),
    });
    expect(screen.getByRole("link")).toHaveAttribute("href", "https://other.atlassian.net/browse/OPS-3");
  });

  test("iconOnly shows no key text, names the link after the key, and renders nothing without a site", () => {
    const { unmount } = renderWithProviders(<JiraKeyLink issueKey="FLO-1" iconOnly skipTab />, {
      queryClient: clientWith([1, "https://stub.atlassian.net"]),
    });
    const link = screen.getByRole("link", { name: "FLO-1 (opens in Jira)" });
    expect(link).toHaveAttribute("tabindex", "-1");
    unmount();
    renderWithProviders(<JiraKeyLink issueKey="FLO-1" iconOnly />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(/FLO-1/)).not.toBeInTheDocument();
  });

  test("turns into a link when the filters arrive in the cache after the first render", () => {
    const client = new QueryClient();
    renderWithProviders(<JiraKeyLink issueKey="FLO-1" />, { queryClient: client });
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    act(() => client.setQueryData(FILTERS_KEY, connections([1, "https://stub.atlassian.net"])));
    expect(screen.getByRole("link", { name: "FLO-1 (opens in Jira)" })).toBeInTheDocument();
  });
});
