/**
 * The accessible name of a drill figure button (`reports.drill.figureAria`: "<figure> — <column>: show the items of <target>"), as a
 * locator pattern. A test names the target (and, where one bucket has several figures, the column) and not the figure's value.
 */
export function showItems(target: string, column?: string): RegExp {
  const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`${column === undefined ? "" : `— ${escape(column)}`}: show the items of ${escape(target)}$`);
}
