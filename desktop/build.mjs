// Bundles the renderer and the Electron main/preload with esbuild (no other build tool needed).
import { build } from "esbuild";
import { cpSync, mkdirSync, rmSync, writeFileSync, readFileSync } from "node:fs";

rmSync("dist", { recursive: true, force: true });
rmSync("dist-electron", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });

await build({
  entryPoints: ["src/main.tsx"],
  bundle: true,
  outfile: "dist/app.js",
  format: "iife",
  target: "chrome120",
  jsx: "automatic",
  minify: process.env.DEV !== "1",
  sourcemap: process.env.DEV === "1",
  loader: { ".svg": "dataurl", ".woff2": "dataurl" },
  define: { "process.env.NODE_ENV": '"production"' },
  logLevel: "info"
});
cpSync("src/ui/app.css", "dist/app.css");
cpSync("index.html", "dist/index.html");
try { cpSync("public", "dist", { recursive: true }); } catch {}

await build({
  entryPoints: ["electron/main.ts", "electron/preload.ts"],
  bundle: true,
  platform: "node",
  format: "cjs",
  target: "node20",
  outdir: "dist-electron",
  external: ["electron"],
  logLevel: "info"
});
