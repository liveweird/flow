import { describe, expect, test } from "vitest";
import { Affix, AppShell, Drawer, Modal, Popover, Tooltip } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { Spotlight, spotlight } from "@mantine/spotlight";
import printCss from "./print.css?raw";
import ScrollRegion from "./components/ScrollRegion";
import { act, renderWithProviders } from "./test/render";

describe("print.css", () => {
  test("every Mantine class it targets is one Mantine really renders (a Mantine upgrade that renames one fails here, not on paper)", () => {
    renderWithProviders(
      <>
        <AppShell header={{ height: 48 }} navbar={{ width: 200, breakpoint: "sm" }}>
          <AppShell.Header>header</AppShell.Header>
          <AppShell.Navbar>navbar</AppShell.Navbar>
          <AppShell.Main>main</AppShell.Main>
        </AppShell>
        <Drawer opened onClose={() => {}}>
          drawer
        </Drawer>
        <Modal opened onClose={() => {}}>
          modal
        </Modal>
        <Affix>affix</Affix>
        <Popover opened>
          <Popover.Target>
            <button type="button">target</button>
          </Popover.Target>
          <Popover.Dropdown>dropdown</Popover.Dropdown>
        </Popover>
        <Tooltip label="tip" opened>
          <button type="button">anchor</button>
        </Tooltip>
        <Notifications />
        <Spotlight actions={[]} nothingFound="none" />
        <ScrollRegion label="Region" minWidth={500}>
          <div />
        </ScrollRegion>
      </>,
    );
    act(() => spotlight.open());
    const targeted = [...new Set(printCss.match(/\.mantine-[A-Za-z]+-[A-Za-z]+/g) ?? [])].map((selector) => selector.slice(1));
    expect(targeted.length).toBeGreaterThan(8);
    for (const name of targeted) {
      expect(document.querySelector(`.${name}`), name).not.toBeNull();
    }
  });
});
