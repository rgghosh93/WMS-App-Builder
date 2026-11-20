import { describe, expect, it } from 'vitest';
import { optionalNumber, positiveIntegerError, positiveNumberError } from './validation';

describe('positiveNumberError', () => {
  it('accepts whole numbers and allowed decimals', () => {
    expect(positiveNumberError('12', 2)).toBeNull();
    expect(positiveNumberError(' 4.2 ', 3)).toBeNull();
    expect(positiveNumberError('66.667', 3)).toBeNull();
  });

  it('explains what is wrong', () => {
    expect(positiveNumberError('', 2)).toBe('Required');
    expect(positiveNumberError('abc', 2)).toBe('Enter a number');
    expect(positiveNumberError('0', 2)).toBe('Must be greater than 0');
    expect(positiveNumberError('-3', 2)).toBe('Must be greater than 0');
    expect(positiveNumberError('1.234', 2)).toBe('Up to 2 decimal places');
    expect(positiveNumberError('1e3', 2)).toBe('Up to 2 decimal places');
  });
});

describe('positiveIntegerError', () => {
  it('accepts positive whole numbers only', () => {
    expect(positiveIntegerError('3')).toBeNull();
    expect(positiveIntegerError('1.5')).toBe('Whole number, greater than 0');
    expect(positiveIntegerError('0')).toBe('Whole number, greater than 0');
  });
});

describe('optionalNumber', () => {
  it('maps blank to null', () => {
    expect(optionalNumber(' ')).toBeNull();
    expect(optionalNumber('2.5')).toBe(2.5);
  });
});
