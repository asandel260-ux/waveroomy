import { useEffect, useMemo, useRef, useState } from "react";
import QRCode from "qrcode";
import jsQR from "jsqr";
import { Tiny, Icon } from "./kit";

const FRAME_MAX = 1100;

/** Splits a long code into numbered QR frames; the scanner reassembles them. Same framing as the phone app. */
export function qrFrames(code: string): string[] {
  if (code.length <= FRAME_MAX) return [code];
  const n = Math.ceil(code.length / FRAME_MAX);
  const per = Math.ceil(code.length / n);
  return Array.from({ length: n }, (_, i) => `WRQ|${i + 1}|${n}|` + code.substring(i * per, Math.min(code.length, (i + 1) * per)));
}

export function QrDisplay({ code, size = 280 }: { code: string; size?: number }) {
  const frames = useMemo(() => qrFrames(code), [code]);
  const [idx, setIdx] = useState(0);
  const [url, setUrl] = useState<string | null>(null);
  const [bad, setBad] = useState(false);
  useEffect(() => {
    if (frames.length < 2) return;
    const t = setInterval(() => setIdx((i) => (i + 1) % frames.length), 900);
    return () => clearInterval(t);
  }, [frames]);
  useEffect(() => {
    let live = true;
    QRCode.toDataURL(frames[Math.min(idx, frames.length - 1)], { errorCorrectionLevel: "L", margin: 1, width: 720 })
      .then((u: string) => { if (live) { setUrl(u); setBad(false); } })
      .catch(() => { if (live) setBad(true); });
    return () => { live = false; };
  }, [frames, idx]);
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
      <div style={{ width: size, height: size, borderRadius: 12, background: "#fff", padding: 10, display: "grid", placeItems: "center" }}>
        {url && !bad ? <img src={url} alt="QR code" style={{ width: "100%", height: "100%", imageRendering: "pixelated" }} /> : <span style={{ color: "#000", fontSize: 13 }}>Code is too large for a QR image.<br />Share it as text instead.</span>}
      </div>
      {frames.length > 1 && <div style={{ marginTop: 8 }}><Tiny>Frame {idx + 1} of {frames.length}. Keep the scanner pointed here</Tiny></div>}
    </div>
  );
}

/** Full-window camera scanner that reports the first complete HoMit code. */
export function ScannerOverlay({ title, hint, onCode, onClose }: { title: string; hint: string; onCode: (c: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [progress, setProgress] = useState<[number, number] | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    let stop = false;
    let stream: MediaStream | null = null;
    const parts = new Map<number, string>();
    let done = false;
    const handle = (raw: string) => {
      if (done) return;
      if (raw.startsWith("WRQ|")) {
        const p = raw.split("|");
        if (p.length < 4) return;
        const i = parseInt(p[1]), n = parseInt(p[2]);
        if (!i || !n) return;
        parts.set(i, p.slice(3).join("|"));
        setProgress([parts.size, n]);
        if (parts.size === n) {
          let s = "";
          for (let k = 1; k <= n; k++) { const x = parts.get(k); if (x == null) return; s += x; }
          done = true; onCode(s);
        }
      } else if (raw.includes("WR1.")) { done = true; onCode(raw); }
    };
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment", width: { ideal: 1280 } } });
        if (stop) { stream.getTracks().forEach((t) => t.stop()); return; }
        const v = video.current!;
        v.srcObject = stream;
        await v.play();
        const cv = document.createElement("canvas");
        const cx = cv.getContext("2d", { willReadFrequently: true })!;
        const loop = () => {
          if (stop || done) return;
          if (v.videoWidth) {
            cv.width = v.videoWidth; cv.height = v.videoHeight;
            cx.drawImage(v, 0, 0);
            const img = cx.getImageData(0, 0, cv.width, cv.height);
            const r = jsQR(img.data, img.width, img.height, { inversionAttempts: "dontInvert" });
            if (r?.data) handle(r.data);
          }
          setTimeout(loop, 120);
        };
        loop();
      } catch { setErr("No camera available. Paste the code instead."); }
    })();
    return () => { stop = true; stream?.getTracks().forEach((t) => t.stop()); };
  }, []);
  return (
    <div style={{ position: "fixed", inset: 0, top: 36, background: "#000", zIndex: 60, display: "flex", flexDirection: "column" }}>
      <video ref={video} muted playsInline style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
      <div style={{ position: "absolute", left: "50%", top: "50%", width: 260, height: 260, transform: "translate(-50%,-50%)", borderRadius: 12, border: "2px solid var(--neon)" }} />
      <div style={{ position: "relative", display: "flex", alignItems: "center", gap: 12, padding: 16 }}>
        <button onClick={onClose} style={{ width: 44, height: 44, borderRadius: "50%", background: "rgb(0 0 0 / .6)", display: "grid", placeItems: "center", color: "#fff" }}><Icon name="x" /></button>
        <span style={{ color: "#fff", fontSize: 18, fontWeight: 600 }}>{title}</span>
      </div>
      <div style={{ flex: 1 }} />
      <div style={{ position: "relative", padding: 24, textAlign: "center", color: "#fff" }}>
        {progress && <div style={{ fontSize: 14 }}>Reading code… {progress[0]} of {progress[1]}</div>}
        <div style={{ fontSize: 13, opacity: 0.8, marginTop: 6 }}>{err || hint}</div>
      </div>
    </div>
  );
}
