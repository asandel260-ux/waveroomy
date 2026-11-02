import { useState } from "react";
import { Icon, Button, Silk, NeonWave } from "../components/kit";

const SLIDES = [
  { icon: "waveform", title: "Talk. Just talk.", body: "Open a room and everyone in it can speak. No stage, no seats, no audience — a host keeps things friendly." },
  { icon: "wifi", title: "No servers. Really.", body: "Devices connect directly to each other with a code or over the same Wi-Fi. Your voice, messages and files never touch a company's computer." },
  { icon: "message", title: "Chats, groups and calls", body: "Message friends, make group chats, send photos, GIFs and files, keep notes to yourself — and ring anyone you're connected to." }
];

export function WelcomeScreen({ onStart }: { onStart: () => void }) {
  const [page, setPage] = useState(0);
  const s = SLIDES[page];
  const last = page === SLIDES.length - 1;
  return (
    <div style={{ position: "relative", flex: 1, display: "flex", flexDirection: "column", alignItems: "center", background: "linear-gradient(180deg, #260B63, #14072E, var(--bg))", overflow: "hidden" }}>
      <Silk />
      <div style={{ position: "absolute", top: 40, left: 0, right: 0 }}><NeonWave height={160} amplitude={42} stroke={4} /></div>
      <div style={{ position: "relative", zIndex: 1, flex: 1, width: "100%", maxWidth: 520, padding: 28, display: "flex", flexDirection: "column", alignItems: "center", textAlign: "center" }}>
        <div style={{ height: 36 }} />
        <div key={"i" + page} style={{ height: 120, display: "grid", placeItems: "center", animation: "pagein .35s var(--ease)" }}>
          <div style={{ width: 84, height: 84, borderRadius: "50%", display: "grid", placeItems: "center", background: "linear-gradient(180deg, var(--grad-top), var(--grad-bottom))", boxShadow: "0 0 0 1.5px rgb(182 144 255 / .6)" }}>
            <Icon name={s.icon} size={38} color="#fff" />
          </div>
        </div>
        <div style={{ height: 120 }} />
        <div className="display" style={{ fontSize: 44 }}>HoMit</div>
        <div key={"t" + page} style={{ marginTop: 18, animation: "pagein .32s var(--ease)" }}>
          <div className="display" style={{ fontSize: 24 }}>{s.title}</div>
          <div className="muted" style={{ fontSize: 15, lineHeight: "22px", marginTop: 10 }}>{s.body}</div>
        </div>
        <div style={{ flex: 1 }} />
        <div className="hstack g8">
          {SLIDES.map((_, i) => <span key={i} style={{ width: i === page ? 22 : 8, height: 8, borderRadius: 4, background: i === page ? "var(--neon)" : "var(--border-strong)", transition: "width .2s" }} />)}
        </div>
        <div style={{ height: 22 }} />
        <Button block text={last ? "Get started" : "Next"} onClick={() => (last ? onStart() : setPage(page + 1))} />
        <div style={{ height: 40, marginTop: 6 }}>{!last && <button className="muted" style={{ padding: 12, fontSize: 14 }} onClick={onStart}>Skip</button>}</div>
      </div>
    </div>
  );
}
