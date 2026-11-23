import { bars } from './code128';

const QUIET = 10;

/** A Code 128 barcode with its text underneath. */
export function Barcode({ value, height = 60 }: { value: string; height?: number }) {
  const code = bars(value);
  return (
    <figure className="barcode">
      {code && (
        <svg
          viewBox={`0 0 ${code.width + QUIET * 2} ${height}`}
          preserveAspectRatio="none"
          role="img"
          aria-label={`Barcode ${value}`}
          shapeRendering="crispEdges"
        >
          {code.bars.map((b) => (
            <rect key={b.x} x={b.x + QUIET} y={0} width={b.width} height={height} fill="#000" />
          ))}
        </svg>
      )}
      <figcaption className="code">{value}</figcaption>
    </figure>
  );
}
