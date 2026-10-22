// A UML class compartment box, the unit everything in the product is built from.
export default function BrandMark({ size = 22 }) {
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <rect x="2.5" y="2.5" width="19" height="19" rx="2.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M2.5 9h19M2.5 15h19" stroke="currentColor" strokeWidth="1.8" />
      <circle cx="18" cy="6" r="1.6" className="brand-mark-dot" />
    </svg>
  )
}
