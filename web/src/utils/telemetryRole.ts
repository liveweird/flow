import type { TFunction } from "i18next";

/** The process role in words (web / worker / all in one process); an unknown role reads as the server sent it. */
export function roleName(t: TFunction, role: string): string {
  switch (role) {
    case "web":
      return t("telemetry.role.web");
    case "worker":
      return t("telemetry.role.worker");
    case "all":
      return t("telemetry.role.all");
    default:
      return role;
  }
}
