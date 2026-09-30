/** The ONE place the users route family is spelled out — never hand-assemble a user URL. */
export const usersPath = "/users";
export const newUserPath = `${usersPath}/new`;
export const editUserPath = (id: number) => `${usersPath}/${id}/edit`;
export const userFeaturesPath = (id: number) => `${usersPath}/${id}/features`;
