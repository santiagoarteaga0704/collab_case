import BrandMark from './BrandMark'

// Drafting-sheet illustration: the product's core moment (two people modelling the same
// class diagram, one element locked) drawn the way a technical plate would be.
function DraftingSheet() {
  return (
    <svg className="sheet-drawing" viewBox="0 0 560 420" role="img"
      aria-label="Dos personas editando el mismo diagrama de clases: Socio y Libro se relacionan con Préstamo">
      <g className="sheet-edges">
        <path d="M150 128 V 300 H 205" />
        <path d="M410 128 V 300 H 355" />
      </g>
      <g className="sheet-labels">
        <text x="158" y="146">1</text>
        <text x="160" y="292">0..*</text>
        <text x="398" y="146">1</text>
        <text x="372" y="292">0..*</text>
      </g>
      <g className="sheet-class">
        <rect x="60" y="40" width="180" height="88" />
        <line x1="60" y1="68" x2="240" y2="68" />
        <line x1="60" y1="106" x2="240" y2="106" />
        <text className="sheet-class-name" x="150" y="59">Socio</text>
        <text x="70" y="86">+ nombre: String</text>
        <text x="70" y="100">+ email: String</text>
        <text x="70" y="121">+ reservar(): Préstamo</text>
      </g>
      <g className="sheet-class">
        <rect x="320" y="40" width="180" height="88" />
        <line x1="320" y1="68" x2="500" y2="68" />
        <line x1="320" y1="106" x2="500" y2="106" />
        <text className="sheet-class-name" x="410" y="59">Libro</text>
        <text x="330" y="86">+ titulo: String</text>
        <text x="330" y="100">+ isbn: String</text>
      </g>
      <g className="sheet-class is-locked">
        <rect className="sheet-lock-ring" x="197" y="244" width="166" height="104" rx="3" />
        <rect x="205" y="252" width="150" height="88" />
        <line x1="205" y1="280" x2="355" y2="280" />
        <text className="sheet-class-name" x="280" y="271">Préstamo</text>
        <text x="215" y="298">+ fecha: Date</text>
        <text x="215" y="312">+ devuelto: boolean</text>
      </g>
      <g className="sheet-cursor cursor-ana">
        <path d="M0 0 L0 17 L5 12.5 L8.5 20 L11 19 L7.5 11.5 L14 11.5 Z" />
        <rect x="14" y="18" width="40" height="18" rx="9" />
        <text x="34" y="31">Ana</text>
      </g>
      <g className="sheet-cursor cursor-luis">
        <path d="M0 0 L0 17 L5 12.5 L8.5 20 L11 19 L7.5 11.5 L14 11.5 Z" />
        <rect x="14" y="18" width="40" height="18" rx="9" />
        <text x="34" y="31">Luis</text>
      </g>
    </svg>
  )
}

export default function AuthLayout({ title, intro, children }) {
  return (
    <main className="auth-page">
      <div className="auth-brand"><BrandMark size={24} /><span>Collab CASE</span></div>
      <div className="auth-stage">
        <section className="auth-pitch">
          <h2><span>Modelen juntos.</span> <span>El backend sale del diagrama.</span></h2>
          <p>Diagramas de clases en tiempo real, con bloqueo por elemento, asistente de IA y generación de proyectos Spring Boot.</p>
          <DraftingSheet />
        </section>
        <section className="auth-card" aria-labelledby="auth-title">
          <h1 id="auth-title">{title}</h1>
          {intro && <p className="auth-intro">{intro}</p>}
          {children}
        </section>
      </div>
      <dl className="title-block" aria-hidden="true">
        <div><dt>Proyecto</dt><dd>Sistema de biblioteca</dd></div>
        <div><dt>Lámina</dt><dd>Modelo de dominio</dd></div>
        <div><dt>Editando</dt><dd>Ana, Luis</dd></div>
      </dl>
    </main>
  )
}
