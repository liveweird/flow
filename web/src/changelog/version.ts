// The app's ONE user-facing version, in its own tiny module so the shell's eager imports
// (VersionStamp, the what's-new dot) never pull the bilingual changelog into the main
// bundle — entries.ts is a lazy dependency of the Changelog page only. Releasing = adding
// the newest entry in entries.ts AND bumping this literal to its version; entries.test.ts
// pins the two together, so forgetting either fails the suite.
export const APP_VERSION = "1.8.0";
