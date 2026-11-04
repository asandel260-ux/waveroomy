import { useEffect, useMemo, useRef } from "react";
import { makeScene, type Layer, type Scene, type SceneCfg } from "../doodle";

export type SceneId = "rooms" | "chats" | "you" | "social" | "create" | "room" | "quiet" | "intro";

/** Each screen gets its own picture: different direction, density and glow. */
const SCENES: Record<SceneId, { seed: number; cfg: SceneCfg }> = {
  rooms: { seed: 1103, cfg: { angle: -1.15, ribbons: 8 } },
  chats: { seed: 2207, cfg: { angle: 1.05, ribbons: 8, spread: 0.7 } },
  you: { seed: 3319, cfg: { angle: -0.22, ribbons: 7, spread: 0.4, calm: 1.4 } },
  social: { seed: 4421, cfg: { angle: 0.55, ribbons: 6 } },
  create: { seed: 5527, cfg: { angle: -0.6, ribbons: 6, spread: 0.9 } },
  room: { seed: 6631, cfg: { angle: -0.95, ribbons: 6, calm: 0.7 } },
  quiet: { seed: 7733, cfg: { angle: 1.4, ribbons: 5, calm: 0.6 } },
  intro: { seed: 8839, cfg: { angle: -0.8, ribbons: 9 } },
};
const cache = new Map<string, Scene>();
function scene(id: SceneId, seed = 0, w = 1600, h = 1000, cfgOver?: Partial<SceneCfg>): Scene {
  const key = `${id}:${seed}:${w}x${h}`;
  let s = cache.get(key);
  if (!s) { const sc = SCENES[id]; s = makeScene(sc.seed + seed, w, h, { ...sc.cfg, ...cfgOver }); cache.set(key, s); }
  return s;
}

const INK: Record<string, string> = { green: "var(--doodle)", pale: "var(--doodle-pale)", bright: "var(--doodle-bright)" };

const COLOUR_VARS = { green: "--doodle", pale: "--doodle-pale", bright: "--doodle-bright", bandLight: "--band-light", bandDark: "--band-dark" } as const;

/** The still part of a layer, painted once onto a canvas instead of hundreds of page elements. It repaints only when the
 *  window size or the theme colours change. */
function StillLayer({ layers, W, H }: { layers: Layer[]; W: number; H: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = ref.current; if (!cv) return;
    const ctx = cv.getContext("2d"); if (!ctx) return;
    const paths = layers.flatMap((ly) => ly.shapes.filter((sh) => !sh.spin).map((sh) => ({ p: new Path2D(sh.d), sh })));
    let raf = 0;
    const paint = () => {
      const r = cv.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio || 1, 1.5);
      if (!r.width) return;
      cv.width = Math.round(r.width * dpr); cv.height = Math.round(r.height * dpr);
      const css = getComputedStyle(document.documentElement);
      const col = (k: keyof typeof COLOUR_VARS) => css.getPropertyValue(COLOUR_VARS[k]).trim() || "#5be033";
      const k = Math.max(r.width / W, r.height / H) * dpr;                       // same as preserveAspectRatio "slice"
      ctx.setTransform(k, 0, 0, k, (cv.width - W * k) / 2, (cv.height - H * k) / 2);
      for (const { p, sh } of paths) {
        ctx.globalAlpha = sh.o;
        ctx.fillStyle = sh.band ? col(sh.band === "light" ? "bandLight" : "bandDark") : col(sh.ink ?? "green");
        ctx.fill(p);
      }
    };
    const later = () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(paint); };
    paint();
    const ro = new ResizeObserver(later); ro.observe(cv);
    const mo = new MutationObserver(later); mo.observe(document.documentElement, { attributes: true, attributeFilter: ["class", "style"] });
    return () => { cancelAnimationFrame(raf); ro.disconnect(); mo.disconnect(); };
  }, [layers, W, H]);
  return <canvas ref={ref} className="doodle-svg" aria-hidden="true" />;
}

function SceneSvg({ s }: { s: Scene; drawIn?: boolean }) {
  const vb = `0 0 ${s.W} ${s.H}`;
  // One still picture (painted once) and one small picture on top with the parts that move on their own: turning
  // spirals, lights running along edges and fireflies. The two sway together, as a single moving layer.
  const live = s.layers.flatMap((ly) => ly.shapes.filter((x) => x.spin || x.tracer));
  const dots = s.layers.flatMap((ly) => ly.dots);
  return (
    <div className="doodle-stack">
      <div className="dl dl1">
        <StillLayer layers={s.layers} W={s.W} H={s.H} />
        <svg className="doodle-svg live-ink" aria-hidden="true" viewBox={vb} preserveAspectRatio="xMidYMid slice">
          {live.filter((sh) => sh.spin).map((sh, i) => <path key={i} d={sh.d} fill={INK[sh.ink ?? "green"]} fillOpacity={sh.o} className="turn" style={{ transformOrigin: `${sh.at![0]}px ${sh.at![1]}px`, animationDuration: `${Math.abs(sh.spin!)}s`, animationDirection: sh.spin! < 0 ? "reverse" : "normal" }} />)}
          {live.filter((x) => x.tracer).map((sh, i) => <path key={"t" + i} d={sh.tracer} className="tracer" pathLength={1000} style={{ animationDelay: `${(i * 2.7) % 13}s`, animationDuration: `${9 + (i % 5) * 2}s` }} />)}
          {dots.map((p, i) => <circle key={"d" + i} cx={p.x} cy={p.y} r={p.r} className="firefly" style={{ animationDelay: `${p.d}s`, animationDuration: `${7 + (i % 5) * 2}s` }} />)}
        </svg>
      </div>
    </div>
  );
}

/** The app background. Switching screens fades to another picture; everything in it drifts slowly. */
export function DoodleBackdrop({ scene: id }: { scene: SceneId }) {
  const s = useMemo(() => scene(id), [id]);
  return <div className="doodle-bg" key={id} aria-hidden="true"><SceneSvg s={s} /></div>;
}

/** A picture for one slide of the intro tour. It is uncovered from the left, like being drawn in. */
export function DoodleDraw({ seed }: { seed: number }) {
  const s = useMemo(() => scene("intro", seed, 900, 700, { ribbons: 7 }), [seed]);
  return <div className="doodle-art"><SceneSvg s={s} drawIn /></div>;
}
