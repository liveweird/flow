/**
 * The page-width vocabulary (v1.19.0). Every page starts full-bleed on the canvas under a
 * `PageHeader`; content that reads badly stretched — the registry tables, the changelog —
 * caps itself with CONTENT_MAX_WIDTH, and the simple field forms with FORM_MAX_WIDTH. Both
 * are LEFT-aligned caps, never centred Containers: the header above stays anchored to the
 * page's left edge on every screen, so the eye never hunts for it.
 */
export const CONTENT_MAX_WIDTH = 1120;
export const FORM_MAX_WIDTH = 640;
