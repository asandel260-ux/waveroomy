import { Button } from "../components/kit";

const POINTS = [
  { n: "01", title: "Everyone can speak", body: "Open a room and talk. No stage, no seats, no audience — a host keeps things friendly." },
  { n: "02", title: "No servers", body: "Devices connect directly with a code or over the same Wi-Fi. Voice, messages and files never touch a company's computer." },
  { n: "03", title: "Chats, groups, calls", body: "Message friends, make groups, send photos and files, keep notes to yourself, and ring anyone you're connected to." },
];

/** How HoMit connects, drawn as it works: four devices, every pair linked directly, nothing in the middle. */
function MeshDiagram() {
  const nodes = [{ x: 40, y: 40, l: "You" }, { x: 260, y: 56, l: "Friend" }, { x: 72, y: 220, l: "Phone" }, { x: 240, y: 232, l: "Friend" }];
  const pairs: [number, number][] = [[0, 1], [0, 2], [0, 3], [1, 2], [1, 3], [2, 3]];
  return (
    <svg viewBox="0 0 300 280" width="100%" role="img" aria-label="Four devices, each linked directly to the others, with no server in between" style={{ display: "block", maxWidth: 420 }}>
      {pairs.map(([a, b], i) => <line key={i} x1={nodes[a].x} y1={nodes[a].y} x2={nodes[b].x} y2={nodes[b].y} stroke="var(--border-strong)" strokeWidth="1" />)}
      {nodes.map((n, i) => (
        <g key={i}>
          <rect x={n.x - 14} y={n.y - 14} width="28" height="28" fill={i === 0 ? "var(--accent)" : "var(--bg)"} stroke={i === 0 ? "var(--accent)" : "var(--text)"} strokeWidth="1.5" />
          <text x={n.x} y={n.y + 34} textAnchor="middle" fontSize="10" fontWeight="600" fill="var(--muted)" letterSpacing=".08em" style={{ textTransform: "uppercase" }}>{n.l}</text>
        </g>
      ))}
      <text x="150" y="146" textAnchor="middle" fontSize="10" fontFamily="var(--mono)" fill="var(--faint)">direct · encrypted · no server</text>
    </svg>
  );
}

export function WelcomeScreen({ onStart }: { onStart: () => void }) {
  return (
    <div className="scroll" style={{ background: "var(--bg)" }}>
      <div style={{ maxWidth: 1040, margin: "0 auto", padding: "72px 32px 56px", display: "grid", gridTemplateColumns: "repeat(12, 1fr)", columnGap: 24, rowGap: 64 }}>
        <div style={{ gridColumn: "1 / span 7" }}>
          <div className="tiny">HoMit · Windows</div>
          <h1 className="display" style={{ fontSize: 88, margin: "20px 0 0", letterSpacing: "-.055em" }}>Talk.<br />Directly.</h1>
          <p style={{ margin: "28px 0 0", maxWidth: 420, fontSize: 17, lineHeight: "26px", color: "var(--muted)" }}>Voice rooms, messages and screen sharing that run between devices — on your phone and on this computer, with one identity.</p>
          <div style={{ marginTop: 40 }}><Button text="Get started" icon="arrow-right" onClick={onStart} /></div>
        </div>
        <div style={{ gridColumn: "9 / span 4", alignSelf: "center" }}><MeshDiagram /></div>
        <div style={{ gridColumn: "1 / span 12", display: "grid", gridTemplateColumns: "repeat(3, 1fr)", columnGap: 24, borderTop: "2px solid var(--text)" }}>
          {POINTS.map((p) => (
            <div key={p.n} style={{ paddingTop: 20 }}>
              <div className="mono" style={{ fontSize: 12, color: "var(--accent-bright)" }}>{p.n}</div>
              <div style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-.025em", margin: "12px 0 8px" }}>{p.title}</div>
              <div className="muted" style={{ fontSize: 14, lineHeight: "21px", maxWidth: 280 }}>{p.body}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
