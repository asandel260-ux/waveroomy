import { useEffect, useRef, useState } from "react";
import { Button, Icon } from "../components/kit";

/* The first thing a new person sees: a short tour on the left, and on the right a field of dots that moves the way the app does (voices as ripples, people as nodes). */

interface Slide { title: string; text: string; mode: number }
const SLIDES: Slide[] = [
  { title: "Welcome to HoMit", text: "Voice rooms that run straight between your devices. A short look around, then you make your identity.", mode: 0 },
  { title: "Talk in a room", text: "Start a room and share its code or QR. People on the same Wi-Fi see it appear without any code at all.", mode: 1 },
  { title: "Do things together", text: "Watch a video in sync, draw on a shared board, browse the same page or play chess while you talk.", mode: 2 },
  { title: "No servers in the middle", text: "Voice, chat and files go directly from one device to the next. There is no account to sign up for.", mode: 3 },
  { title: "One identity, two devices", text: "Use the same identity on your phone and on this computer. Your contacts and profile stay in step.", mode: 4 },
];

const CELL = 11;

/** Intensity 0..1 of the dot at (x, y) (both 0..1, y down), per slide mode. */
function field(mode: number, x: number, y: number, ar: number, t: number): number {
  const cx = x - 0.5, cy = (y - 0.5) / ar;
  const d = Math.hypot(cx * 1, (y - 0.5));
  switch (mode) {
    case 0: return 0.5 + 0.5 * Math.sin(d * 22 - t * 2.2);                      // ripples leaving the icon
    case 1: {                                                                    // a voice meter: columns rise and fall
      const col = Math.floor(x * 46);
      const h = 0.18 + 0.62 * Math.abs(Math.sin(col * 0.9 + t * 1.6) * Math.cos(col * 0.37 - t * 0.8));
      return 1 - y < h ? 0.35 + 0.65 * (1 - y) / h : 0;
    }
    case 2: return 0.5 + 0.5 * Math.sin((x + y * 0.8) * 16 - t * 2);             // a wave sweeping across, everyone in step
    case 3: {                                                                    // four devices, each ringing, nothing central
      let v = 0;
      for (let i = 0; i < 4; i++) {
        const a = t * 0.35 + i * Math.PI / 2;
        const nx = 0.5 + 0.3 * Math.cos(a), ny = 0.5 + 0.28 * Math.sin(a);
        v = Math.max(v, 0.5 + 0.5 * Math.sin(Math.hypot(x - nx, y - ny) * 30 - t * 2.4) - Math.hypot(x - nx, y - ny) * 1.6);
      }
      return Math.max(0, v);
    }
    default: return 0.5 + 0.5 * Math.sin(d * 22 + t * 2.2);                      // ripples arriving at the icon
  }
}

function DotField({ mode }: { mode: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = ref.current; if (!cv) return;
    const ctx = cv.getContext("2d"); if (!ctx) return;
    const css = getComputedStyle(document.documentElement);
    const still = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches || document.documentElement.classList.contains("no-anim");
    let w = 0, h = 0, raf = 0;
    const draw = (ms: number) => {
      const t = ms / 1000;
      const accent = css.getPropertyValue("--accent-bright").trim() || "#b690ff";
      const live = css.getPropertyValue("--live").trim() || "#52ff1f";
      ctx.clearRect(0, 0, w, h);
      const nx = Math.floor(w / CELL), ny = Math.floor(h / CELL);
      const ox = (w - nx * CELL) / 2, oy = (h - ny * CELL) / 2;
      for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
        const v = field(mode, i / nx, j / ny, h / w, t);
        if (v < 0.08) continue;
        ctx.globalAlpha = Math.min(1, 0.1 + v * 0.85);
        ctx.fillStyle = v > 0.985 ? live : accent;
        const s = 3 + v * 4;
        ctx.fillRect(ox + i * CELL + (CELL - s) / 2, oy + j * CELL + (CELL - s) / 2, s, s);
      }
      ctx.globalAlpha = 1;
      if (!still) raf = requestAnimationFrame(draw);
    };
    const size = () => {
      const r = cv.parentElement!.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
      w = r.width; h = r.height; cv.width = w * dpr; cv.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      cancelAnimationFrame(raf); raf = requestAnimationFrame(draw);
    };
    const ro = new ResizeObserver(size); ro.observe(cv.parentElement!); size();
    return () => { ro.disconnect(); cancelAnimationFrame(raf); };
  }, [mode]);
  return <canvas ref={ref} aria-hidden="true" style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} />;
}

export function WelcomeScreen({ onStart }: { onStart: () => void }) {
  const [i, setI] = useState(0);
  const last = i === SLIDES.length - 1;
  const s = SLIDES[i];
  const go = (n: number) => setI(Math.max(0, Math.min(SLIDES.length - 1, n)));
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLElement && /INPUT|TEXTAREA/.test(e.target.tagName)) return;
      if (e.key === "ArrowRight") setI((n) => Math.min(SLIDES.length - 1, n + 1));
      if (e.key === "ArrowLeft") setI((n) => Math.max(0, n - 1));
    };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, []);
  return (
    <div className="intro" role="region" aria-roledescription="carousel" aria-label="Introduction to HoMit">
      <div className="intro-top"><Button small kind="ghost" text="Skip" onClick={onStart} /></div>
      <div className="intro-card">
        <div className="intro-text" key={i} aria-live="polite">
          <div className="tiny">{i + 1} of {SLIDES.length}</div>
          <h1 className="display">{s.title}</h1>
          <p>{s.text}</p>
          <div className="hstack g8" style={{ marginTop: 28 }}>
            {i > 0 && <Button kind="secondary" icon="arrow-left" text="Back" onClick={() => go(i - 1)} />}
            {last ? <Button kind="ok" text="Create my identity" onClick={onStart} /> : <Button text={i === 0 ? "Take a quick look" : "Next"} onClick={() => go(i + 1)} />}
          </div>
        </div>
        <div className="intro-art">
          <DotField mode={s.mode} />
          <img src="./icon.png" alt="HoMit app icon" width={164} height={164} draggable={false} />
        </div>
      </div>
      <div className="intro-dots" role="group" aria-label="Choose a step">
        {SLIDES.map((x, n) => <button key={n} onClick={() => go(n)} aria-label={`Step ${n + 1}: ${x.title}`} aria-current={n === i} className={n === i ? "on" : ""}><span /></button>)}
      </div>
    </div>
  );
}
