/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding: the wrapper exports helpers beside the component; fast-refresh is irrelevant under vitest
import { type ReactElement } from "react";
import { render, type RenderOptions } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { theme } from "../theme";

interface Options extends Omit<RenderOptions, "wrapper"> {
  route?: string;
  /** `location.state` for the initial entry — the sync modal's / Infer page's hand-off. */
  state?: unknown;
  queryClient?: QueryClient;
}

export function renderWithProviders(ui: ReactElement, options: Options = {}) {
  const { route = "/", state, queryClient: suppliedQueryClient, ...rest } = options;
  const queryClient = suppliedQueryClient ?? new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(ui, {
    wrapper: ({ children }) => (
      <MantineProvider env="test" theme={theme}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[state === undefined ? route : { pathname: route, state }]}>{children}</MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>
    ),
    ...rest,
  });
}

export * from "@testing-library/react";
