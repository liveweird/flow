import { createContext, useContext } from "react";

/** The length of Home's window in days, from the server's own `window` (the page provides it): copy says "the previous N days" without a constant. */
export const HomeWindowDays = createContext(90);

export function useHomeWindowDays(): number {
  return useContext(HomeWindowDays);
}
