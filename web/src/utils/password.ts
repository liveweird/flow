// 64 characters, so `byte & 63` maps uniformly with no modulo bias.
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/**
 * Cryptographically random password: 16 chars × 6 bits = 96 bits of entropy. Generated
 * CLIENT-side (the Lettuce design) — the server only ever stores the bcrypt hash, so no
 * response can leak plaintext; the one-time reveal modal shows the copy held in page state.
 */
export function generatePassword(length = 16): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => ALPHABET[b & 63]).join("");
}

/** The bcrypt ceiling check mirrored client-side (the server rejects > 71 UTF-8 bytes). */
export function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}
