export type DimensionUom = 'IN' | 'FT' | 'CM' | 'M';
export type WeightUom = 'LB' | 'OZ' | 'KG' | 'G';
export type VolumeUom = 'CU_IN' | 'CU_FT' | 'CU_CM' | 'CU_M' | 'L';

export const DIMENSION_UOMS: { value: DimensionUom; label: string }[] = [
  { value: 'IN', label: 'in' },
  { value: 'FT', label: 'ft' },
  { value: 'CM', label: 'cm' },
  { value: 'M', label: 'm' },
];

export const WEIGHT_UOMS: { value: WeightUom; label: string }[] = [
  { value: 'LB', label: 'lb' },
  { value: 'OZ', label: 'oz' },
  { value: 'KG', label: 'kg' },
  { value: 'G', label: 'g' },
];

export const VOLUME_UOMS: { value: VolumeUom; label: string }[] = [
  { value: 'CU_IN', label: 'in³' },
  { value: 'CU_FT', label: 'ft³' },
  { value: 'CU_CM', label: 'cm³' },
  { value: 'CU_M', label: 'm³' },
  { value: 'L', label: 'L' },
];

const METERS_PER: Record<DimensionUom, number> = { IN: 0.0254, FT: 0.3048, CM: 0.01, M: 1 };
const CUBIC_METERS_PER: Record<VolumeUom, number> = {
  CU_IN: 0.0254 ** 3,
  CU_FT: 0.3048 ** 3,
  CU_CM: 1e-6,
  CU_M: 1,
  L: 0.001,
};

/** Width × depth × height converted to the given volume unit, rounded to 3 decimals; null if any is missing. */
export function physicalVolume(
  width: number,
  depth: number,
  height: number,
  dimensionUom: DimensionUom,
  volumeUom: VolumeUom,
): number | null {
  if (![width, depth, height].every((n) => Number.isFinite(n) && n > 0)) return null;
  const cubicMeters = width * depth * height * METERS_PER[dimensionUom] ** 3;
  return Math.round((cubicMeters / CUBIC_METERS_PER[volumeUom]) * 1000) / 1000;
}

export function formatVolume(volume: number | null, uom: VolumeUom | null): string {
  if (volume == null || !uom) return '';
  const unit = VOLUME_UOMS.find((u) => u.value === uom)?.label ?? uom;
  return `${formatNumber(volume)} ${unit}`;
}

interface Dimensions {
  width: number | null;
  depth: number | null;
  height: number | null;
  dimensionUom: DimensionUom | null;
}

/** "48 × 40 × 60 in", or an empty string when there are no dimensions. */
export function formatDimensions(d: Dimensions): string {
  if (d.width == null || d.depth == null || d.height == null || !d.dimensionUom) return '';
  const unit = DIMENSION_UOMS.find((u) => u.value === d.dimensionUom)?.label ?? d.dimensionUom;
  return `${formatNumber(d.width)} × ${formatNumber(d.depth)} × ${formatNumber(d.height)} ${unit}`;
}

export function formatWeight(weight: number | null, uom: WeightUom | null): string {
  if (weight == null || !uom) return '';
  const unit = WEIGHT_UOMS.find((u) => u.value === uom)?.label ?? uom;
  return `${formatNumber(weight)} ${unit}`;
}

export function formatNumber(n: number): string {
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(3)));
}
