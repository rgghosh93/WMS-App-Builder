import { describe, expect, it } from 'vitest';
import { PATTERNS, bars, encode } from './code128';

describe('Code 128', () => {
  it('has a well-formed pattern table', () => {
    expect(PATTERNS).toHaveLength(107);
    expect(new Set(PATTERNS).size).toBe(107);
    PATTERNS.slice(0, 106).forEach((p) => {
      const digits = [...p].map(Number);
      expect(digits).toHaveLength(6);
      // Every symbol is 11 modules with an even number of them black, which catches a mistyped digit.
      expect(digits.reduce((a, b) => a + b, 0)).toBe(11);
      expect((digits[0] + digits[2] + digits[4]) % 2).toBe(0);
    });
    expect([...PATTERNS[106]].reduce((a, d) => a + Number(d), 0)).toBe(13);
  });

  it('adds the start, check and stop symbols', () => {
    // The standard worked example: "Wikipedia" has check symbol 88.
    expect(encode('Wikipedia')).toEqual([104, 55, 73, 75, 73, 80, 69, 68, 73, 65, 88, 106]);
  });

  it('refuses what it cannot encode', () => {
    expect(encode('')).toBeNull();
    expect(encode('café')).toBeNull();
    expect(bars('')).toBeNull();
  });

  it('lays out bars without overlaps', () => {
    const code = bars('SHIP-TEST-1')!;
    expect(code.width).toBe(11 * (11 + 2) + 13);
    code.bars.slice(1).forEach((b, i) => expect(b.x).toBeGreaterThan(code.bars[i].x + code.bars[i].width - 1));
    const last = code.bars[code.bars.length - 1];
    expect(last.x + last.width).toBe(code.width);
  });
});
