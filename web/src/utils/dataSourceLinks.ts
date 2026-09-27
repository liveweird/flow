/** The ONE place the data-sources route family is spelled out — never hand-assemble a URL. */
export const dataSourcesPath = "/data-sources";
export const dataSourcePath = (id: number) => `${dataSourcesPath}/${id}`;
export const dataSourceProfilePath = (id: number) => `${dataSourcePath(id)}/profile`;

/** `key` prefills the inspector's lookup input (`?key=`); omit it to land on a blank form. */
export function dataSourceInspectPath(id: number, key?: string): string {
  const base = `${dataSourcePath(id)}/inspect`;
  return key ? `${base}?key=${encodeURIComponent(key)}` : base;
}
