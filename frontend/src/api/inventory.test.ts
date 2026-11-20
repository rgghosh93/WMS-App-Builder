import { describe, expect, it } from 'vitest';
import { containerIds, countContainers, packBreakdown, type ContainerNode } from './inventory';

describe('packBreakdown', () => {
  const packs = [
    { uomCode: 'CS', quantity: 6 },
    { uomCode: 'PL', quantity: 480 },
  ];

  it('uses the largest packs first', () => {
    expect(packBreakdown(1000, packs, 'EA')).toBe('2 PL + 6 CS + 4 EA');
    expect(packBreakdown(27, packs, 'EA')).toBe('4 CS + 3 EA');
    expect(packBreakdown(480, packs, 'EA')).toBe('1 PL');
  });

  it('is empty when the quantity is smaller than any pack or there are no packs', () => {
    expect(packBreakdown(5, packs, 'EA')).toBe('');
    expect(packBreakdown(50, [], 'EA')).toBe('');
  });
});

describe('container tree helpers', () => {
  const node = (id: string, children: ContainerNode[] = []): ContainerNode => ({
    id,
    lpnId: id,
    permanent: false,
    containerType: { id: 't', containerTypeId: 'T', description: 'T', category: 'PALLET' },
    status: 'IN_USE',
    totalQuantity: 0,
    itemCount: 0,
    lines: [],
    children,
  });

  it('counts and lists containers at every depth', () => {
    const tree = [node('P1', [node('C1', [node('X1')]), node('C2')]), node('P2')];
    expect(countContainers(tree)).toBe(5);
    expect(containerIds(tree)).toEqual(['P1', 'C1', 'X1', 'C2', 'P2']);
  });
});
