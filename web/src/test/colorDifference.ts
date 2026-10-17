// CIEDE2000 colour difference of two `#rrggbb` colours (Sharma, Wu & Dalal 2005), for the categorical-palette test: the dataviz
// floor is ΔE ≥ 15 between neighbouring series. Standalone arithmetic, no dependency.

const rad = (degrees: number) => (degrees * Math.PI) / 180;
const deg = (radians: number) => (radians * 180) / Math.PI;

/** sRGB hex → CIE L*a*b* (D65). */
function toLab(hex: string): [number, number, number] {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  const x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
  const y = 0.2126729 * r + 0.7151522 * g + 0.072175 * b;
  const z = (0.0193339 * r + 0.119192 * g + 0.9503041 * b) / 1.08883;
  const f = (t: number) => (t > 216 / 24389 ? Math.cbrt(t) : ((24389 / 27) * t + 16) / 116);
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
}

const hueAngle = (b: number, a: number) => (b === 0 && a === 0 ? 0 : (deg(Math.atan2(b, a)) + 360) % 360);

/** The CIEDE2000 distance of two `#rrggbb` colours (0 = identical; ~1 is a just-noticeable difference). */
export function deltaE2000(first: string, second: string): number {
  return deltaE2000Lab(toLab(first), toLab(second));
}

/** The same distance over two CIE L*a*b* triples (the form Sharma, Wu & Dalal's reference table is given in). */
export function deltaE2000Lab(first: readonly [number, number, number], second: readonly [number, number, number]): number {
  const [l1, a1, b1] = first;
  const [l2, a2, b2] = second;
  const meanChroma = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2;
  const g = 0.5 * (1 - Math.sqrt(meanChroma ** 7 / (meanChroma ** 7 + 25 ** 7)));
  const ap1 = (1 + g) * a1;
  const ap2 = (1 + g) * a2;
  const c1 = Math.hypot(ap1, b1);
  const c2 = Math.hypot(ap2, b2);
  const h1 = hueAngle(b1, ap1);
  const h2 = hueAngle(b2, ap2);

  let dh = 0;
  if (c1 * c2 !== 0) {
    dh = h2 - h1;
    if (dh > 180) dh -= 360;
    else if (dh < -180) dh += 360;
  }
  const dL = l2 - l1;
  const dC = c2 - c1;
  const dH = 2 * Math.sqrt(c1 * c2) * Math.sin(rad(dh / 2));

  const meanL = (l1 + l2) / 2;
  const meanC = (c1 + c2) / 2;
  let meanH = h1 + h2;
  if (c1 * c2 !== 0) {
    if (Math.abs(h1 - h2) > 180) meanH += h1 + h2 < 360 ? 360 : -360;
    meanH /= 2;
  }
  const t =
    1 -
    0.17 * Math.cos(rad(meanH - 30)) +
    0.24 * Math.cos(rad(2 * meanH)) +
    0.32 * Math.cos(rad(3 * meanH + 6)) -
    0.2 * Math.cos(rad(4 * meanH - 63));
  const rotation = -Math.sin(rad(60 * Math.exp(-(((meanH - 275) / 25) ** 2)))) * 2 * Math.sqrt(meanC ** 7 / (meanC ** 7 + 25 ** 7));
  const sL = 1 + (0.015 * (meanL - 50) ** 2) / Math.sqrt(20 + (meanL - 50) ** 2);
  const sC = 1 + 0.045 * meanC;
  const sH = 1 + 0.015 * meanC * t;
  return Math.sqrt((dL / sL) ** 2 + (dC / sC) ** 2 + (dH / sH) ** 2 + rotation * (dC / sC) * (dH / sH));
}
