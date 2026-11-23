import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// Check the shipped CSS directly so palette edits cannot silently reduce contrast.
const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');
type RGB = [number, number, number];

function hslToRgb(value: string): RGB {
  const [h, s, l] = value.replaceAll('%', '').split(/\s+/).map(Number);
  const saturation = s / 100;
  const lightness = l / 100;
  const a = saturation * Math.min(lightness, 1 - lightness);
  const channel = (n: number) => {
    const k = (n + h / 30) % 12;
    return lightness - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
  };
  return [channel(0), channel(8), channel(4)];
}

function contrast(a: RGB, b: RGB): number {
  const luminance = (rgb: RGB) => rgb.reduce((sum, channel, i) =>
    sum + (channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
      * [0.2126, 0.7152, 0.0722][i], 0);
  const [low, high] = [luminance(a), luminance(b)].sort((x, y) => x - y);
  return (high + 0.05) / (low + 0.05);
}

function tint(color: RGB, surface: RGB, opacity: number): RGB {
  return color.map((channel, i) => channel * opacity + surface[i] * (1 - opacity)) as RGB;
}

for (const [theme, selector] of [['light', ':root'], ['dark', '.dark']]) {
  const block = css.slice(css.indexOf(`${selector} {`)).split('}')[0];
  const palette = Object.fromEntries(
    [...block.matchAll(/--([\w-]+):\s*(\d+ \d+% \d+%);/g)].map(([, name, value]) => [name, hslToRgb(value)])
  );
  const surfaces = ['background', 'background-secondary', 'card', 'popover', 'muted', 'accent'];
  const check = (foreground: string, background: string, minimum = 4.5) => {
    expect(contrast(palette[foreground], palette[background]), `${theme}: ${foreground} on ${background}`)
      .toBeGreaterThanOrEqual(minimum);
  };

  describe(`${theme} color contrast`, () => {
    it('keeps body text, secondary text, and inline accents readable across surfaces', () => {
      for (const surface of surfaces) {
        for (const text of ['foreground', 'muted-foreground', 'primary', 'secondary', 'success', 'warning', 'destructive']) {
          check(text, surface);
        }
      }
    });

    it('keeps filled controls and tinted notices readable', () => {
      for (const color of ['primary', 'secondary', 'success', 'warning', 'destructive']) {
        check(`${color}-foreground`, color);
        if (palette[`${color}-hover`]) check(`${color}-foreground`, `${color}-hover`);
        for (const surface of ['background', 'card', 'muted']) {
          for (const opacity of [0.1, 0.2]) {
            expect(contrast(palette[color], tint(palette[color], palette[surface], opacity)),
              `${theme}: ${color} text on ${opacity * 100}% tint over ${surface}`).toBeGreaterThanOrEqual(4.5);
          }
        }
      }
    });

    it('keeps participant names and message text readable on all eight participant surfaces', () => {
      for (let i = 1; i <= 8; i++) {
        const surface = `participant-${i}-bg`;
        check(`participant-${i}`, surface);
        check('foreground', surface);
        check('muted-foreground', surface);
      }
    });

    it('keeps field boundaries and keyboard focus distinguishable', () => {
      for (const surface of ['background', 'card', 'muted', 'popover']) {
        check('input', surface, 3);
        check('ring', surface, 3);
      }
    });
  });
}
