import { Button } from "../components/kit";

/** How HoMit connects, drawn as it works: every device is linked straight to the others and nothing sits in the middle. */
function MeshDiagram() {
  const nodes = [{ x: 44, y: 44, l: "You" }, { x: 256, y: 60, l: "Friend" }, { x: 70, y: 214, l: "Your phone" }, { x: 238, y: 226, l: "Friend" }];
  const pairs: [number, number][] = [[0, 1], [0, 2], [0, 3], [1, 2], [1, 3], [2, 3]];
  return (
    <svg viewBox="0 0 300 280" width="100%" role="img" aria-label="Four devices, each linked directly to the others, with no server between them" style={{ display: "block", maxWidth: 400 }}>
      {pairs.map(([a, b], i) => <line key={i} x1={nodes[a].x} y1={nodes[a].y} x2={nodes[b].x} y2={nodes[b].y} stroke="var(--border-strong)" strokeWidth="1.5" />)}
      {nodes.map((n, i) => (
        <g key={i}>
          <rect x={n.x - 15} y={n.y - 15} width="30" height="30" rx="6" fill={i === 0 ? "var(--accent)" : "var(--surface2)"} stroke={i === 0 ? "var(--accent)" : "var(--border-strong)"} strokeWidth="1.5" />
          <text x={n.x} y={n.y + 34} textAnchor="middle" fontSize="12" fill="var(--muted)">{n.l}</text>
        </g>
      ))}
    </svg>
  );
}

export function WelcomeScreen({ onStart }: { onStart: () => void }) {
  return (
    <div className="scroll" role="region" aria-labelledby="welcome-h">
      <div style={{ maxWidth: 1000, margin: "0 auto", padding: "72px 32px 56px", display: "grid", gridTemplateColumns: "minmax(0, 1.15fr) minmax(0, 1fr)", columnGap: 64, rowGap: 48, alignItems: "center" }}>
        <div>
          <div className="display" style={{ fontSize: 22, color: "var(--accent-bright)" }}>HoMit</div>
          <h1 id="welcome-h" className="display" style={{ fontSize: 60, margin: "16px 0 0", lineHeight: 1.04 }}>Voice rooms that run straight between your devices.</h1>
          <p style={{ margin: "24px 0 0", maxWidth: 460, fontSize: 16, lineHeight: "25px", color: "var(--muted)" }}>Talk, message, share your screen and draw together. You connect with a code or over the same Wi-Fi, so your voice and files never pass through a company's computer. Use the same identity on your phone and on this computer.</p>
          <div style={{ marginTop: 32 }}><Button text="Create my identity" onClick={onStart} /></div>
        </div>
        <MeshDiagram />
      </div>
    </div>
  );
}
