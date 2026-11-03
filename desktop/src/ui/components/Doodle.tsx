import { useMemo } from "react";
import { makeScene, type Scene, type SceneCfg } from "../doodle";

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

function SceneSvg({ s, drawIn = false }: { s: Scene; drawIn?: boolean }) {
  const vb = `0 0 ${s.W} ${s.H}`;
  // Each layer is two stacked pictures drifting together: a still one, drawn once, and a small one that holds the
  // parts that move on their own (turning spirals, lights running along edges, fireflies), so little is redrawn per frame.
  return (
    <div className={`doodle-stack ${drawIn ? "wipe" : ""}`}>
      {s.layers.map((ly, li) => (
        <div key={li} className={`dl dl${ly.depth}`}>
          <svg className="doodle-svg" aria-hidden="true" viewBox={vb} preserveAspectRatio="xMidYMid slice">
            {ly.shapes.filter((sh) => !sh.spin).map((sh, i) => sh.band
              ? <path key={i} d={sh.d} fill={sh.band === "light" ? "var(--band-light)" : "var(--band-dark)"} fillOpacity={sh.o} />
              : <path key={i} d={sh.d} fill={INK[sh.ink ?? "green"]} fillOpacity={sh.o} />)}
          </svg>
          <svg className="doodle-svg live-ink" aria-hidden="true" viewBox={vb} preserveAspectRatio="xMidYMid slice">
            {ly.shapes.filter((sh) => sh.spin).map((sh, i) => <path key={i} d={sh.d} fill={INK[sh.ink ?? "green"]} fillOpacity={sh.o} className="turn" style={{ transformOrigin: `${sh.at![0]}px ${sh.at![1]}px`, animationDuration: `${Math.abs(sh.spin!)}s`, animationDirection: sh.spin! < 0 ? "reverse" : "normal" }} />)}
            {ly.shapes.filter((x) => x.tracer).map((sh, i) => <path key={"t" + i} d={sh.tracer} className="tracer" pathLength={1000} style={{ animationDelay: `${(i * 2.7) % 13}s`, animationDuration: `${9 + (i % 5) * 2}s` }} />)}
            {ly.dots.map((p, i) => <circle key={"d" + i} cx={p.x} cy={p.y} r={p.r} className="firefly" style={{ animationDelay: `${p.d}s`, animationDuration: `${7 + (i % 5) * 2}s` }} />)}
          </svg>
        </div>
      ))}
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
