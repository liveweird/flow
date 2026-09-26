// Typed translation keys: every t()/i18nKey is checked against the EN resource tree
// (the canonical key set — PL parity is enforced separately by locales/parity.test.ts).
// Dynamic keys built from unions type-check when the union is statically known; the few
// genuinely dynamic sites go through utils/i18nKey.ts `dynamicKey` with a justifying comment.
import type { en } from "./i18n";

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "translation";
    resources: {
      translation: typeof en;
    };
  }
}
