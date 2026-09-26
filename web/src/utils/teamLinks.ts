/** The ONE place the teams route family is spelled out — never hand-assemble a team URL. */
export const teamsPath = "/teams";
export const teamPath = (id: number) => `${teamsPath}/${id}`;
