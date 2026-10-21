/** The ONE place the Flow users route family is spelled out — never hand-assemble a Flow user URL. */
export const flowUsersPath = "/flow-users";
export const newFlowUserPath = `${flowUsersPath}/new`;
export const editFlowUserPath = (id: number) => `${flowUsersPath}/${id}/edit`;
export const flowUserFeaturesPath = (id: number) => `${flowUsersPath}/${id}/features`;

/** The address this family had before "Users" became "Flow users" — only the redirect names it. */
export const legacyUsersPath = "/users";
