import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import LanguageDetector from "i18next-browser-languagedetector";

// Per-namespace resource files (one folder per language). Each area file is merged into a single
// `translation` namespace keyed by area, so keys read as e.g. `common.action.logout`,
// `auth.signIn`. Only EN is imported statically: the typed `en` tree below is the canonical key
// set (src/i18next.d.ts) AND the runtime fallback bundle; every other language is
// auto-discovered from its `locales/<lang>/` folder by the glob further down.
import enCommon from "./locales/en/common.json";
import enAppShell from "./locales/en/appShell.json";
import enAuth from "./locales/en/auth.json";
import enChangelog from "./locales/en/changelog.json";
import enDataSources from "./locales/en/dataSources.json";
import enHome from "./locales/en/home.json";
import enMetrics from "./locales/en/metrics.json";
import enReports from "./locales/en/reports.json";
import enTeams from "./locales/en/teams.json";
import enUsers from "./locales/en/users.json";

/**
 * The build-time supported-language set. Adding a language: a complete `locales/<lang>/`
 * folder (parity-gated), one code here, and one NATIVE_LANGUAGE_NAMES line. English is THE
 * default and the display fallback everywhere.
 */
export const SUPPORTED_LANGUAGES = ["en", "pl"] as const;

export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];

/**
 * Each language names itself — deliberate constants, not translations (a switcher entry must
 * be readable BEFORE switching) and not Intl.DisplayNames (which yields lowercase "polski"
 * and varies across engines). The Record enforces one line per supported language.
 */
export const NATIVE_LANGUAGE_NAMES: Record<SupportedLanguage, string> = {
  en: "English",
  pl: "Polski",
};

/** Narrow an arbitrary language tag to a supported one, defaulting to English. */
export function asSupportedLanguage(lang: string | undefined): SupportedLanguage {
  return (SUPPORTED_LANGUAGES as readonly string[]).includes(lang ?? "")
    ? (lang as SupportedLanguage)
    : "en";
}

const LANGUAGE_STORAGE_KEY = "flow.lang";

/**
 * Exported for the i18next type augmentation (src/i18next.d.ts): the EN tree is the
 * canonical key set every t() call is checked against. Knip cannot trace the d.ts
 * consumer, hence the public tag.
 * @public
 */
export const en = {
  common: enCommon,
  appShell: enAppShell,
  auth: enAuth,
  changelog: enChangelog,
  dataSources: enDataSources,
  home: enHome,
  metrics: enMetrics,
  reports: enReports,
  teams: enTeams,
  users: enUsers,
};

// Every non-EN bundle is assembled from its locales/<lang>/ folder — adding a language never
// adds imports here. Eager on purpose: at 2 shipped languages the whole set rides the main
// chunk; move non-EN to lazy loading when a 3rd language ships or a bundle grows large.
const NON_EN_MODULES = import.meta.glob<Record<string, unknown>>(
  ["./locales/*/*.json", "!./locales/en/**"],
  { eager: true, import: "default" },
);

function bundleFor(lang: SupportedLanguage): Record<string, unknown> {
  const bundle: Record<string, unknown> = {};
  for (const [path, module] of Object.entries(NON_EN_MODULES)) {
    const match = /\/([^/]+)\/([^/]+)\.json$/.exec(path);
    if (!match || match[1] !== lang) continue;
    bundle[match[2]] = module;
  }
  return bundle;
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: Object.fromEntries(
      SUPPORTED_LANGUAGES.map((lang) => [
        lang,
        { translation: lang === "en" ? en : bundleFor(lang) },
      ]),
    ),
    fallbackLng: "en",
    supportedLngs: SUPPORTED_LANGUAGES,
    // Map e.g. pl-PL -> pl.
    nonExplicitSupportedLngs: true,
    interpolation: {
      // React already escapes interpolated values.
      escapeValue: false,
    },
    detection: {
      order: ["localStorage", "navigator"],
      caches: ["localStorage"],
      lookupLocalStorage: LANGUAGE_STORAGE_KEY,
    },
  });

// Keep the document language in sync so assistive tech and the browser pick the right locale.
function syncDocumentLang(lng: string) {
  if (typeof document !== "undefined") {
    document.documentElement.lang = lng;
  }
}
syncDocumentLang(i18n.resolvedLanguage ?? "en");
i18n.on("languageChanged", syncDocumentLang);

export default i18n;
