import { useState } from "react";
import { core } from "../../app/instance";
import { timeAgo } from "../../core/util";
import type { Report } from "../../core/models";
import { useFlow } from "../hooks";
import { Backdrop, Chip, EmptyState, InputDialog, Muted, ScreenHeader, Segmented, Tiny, Button } from "../components/kit";
import type { ScreenProps } from "./registry";

export function ModerationScreen({ back }: ScreenProps) {
  const [tab, setTab] = useState("reports");
  return (
    <Backdrop style={2}>
      <ScreenHeader title="Moderation desk" onBack={back} subtitle="For rooms you host" />
      <div style={{ padding: "6px 16px" }}><Segmented options={[["reports", "Reports"], ["audit", "Audit"], ["stats", "Activity"]]} selected={tab} onSelect={setTab} /></div>
      <div className="scroll"><div style={{ maxWidth: 760, margin: "0 auto" }}>
        {tab === "reports" ? <ReportsTab /> : tab === "audit" ? <AuditTab /> : <StatsTab />}
      </div></div>
    </Backdrop>
  );
}

function ReportsTab() {
  const reports = useFlow(core.mod.reports);
  const [filter, setFilter] = useState("open");
  const [resolving, setResolving] = useState<[Report, string] | null>(null);
  const list = reports.filter((r) => filter === "open" ? r.status === "open" && !r.outgoing : filter === "done" ? r.status !== "open" && !r.outgoing : r.outgoing);
  return (
    <>
      <div className="hstack g8" style={{ padding: "4px 16px" }}>
        <Chip text="Open" selected={filter === "open"} onClick={() => setFilter("open")} />
        <Chip text="Handled" selected={filter === "done"} onClick={() => setFilter("done")} />
        <Chip text="Filed by me" selected={filter === "mine"} onClick={() => setFilter("mine")} />
      </div>
      {list.length === 0 ? <EmptyState icon="shield-alert" title="Nothing here" text="Reports from people in rooms you host land here. There is no central moderator — you decide." /> : (
        <div className="stack g10" style={{ padding: 16 }}>
          {list.map((r) => (
            <div key={r.id} style={{ borderRadius: 16, background: "linear-gradient(180deg, var(--surface2), var(--surface))", boxShadow: "inset 0 0 0 1px var(--border)", padding: 14 }}>
              <div className="hstack"><span className="grow"><Tiny>{r.targetType} · {r.category}</Tiny></span><Tiny>{timeAgo(r.createdAt)}</Tiny></div>
              <div style={{ fontSize: 16, fontWeight: 600, marginTop: 4 }}>{r.targetLabel}</div>
              <Muted size={12}>in “{r.roomTitle}” · from {r.outgoing ? "you" : r.reporterName}</Muted>
              {r.excerpt && <div style={{ marginTop: 8, background: "var(--surface2)", borderRadius: 8, padding: 10, color: "var(--muted)", fontSize: 13 }}>“{r.excerpt}”</div>}
              {r.details && <div style={{ marginTop: 8, fontSize: 14 }}>{r.details}</div>}
              {r.status !== "open" ? <div style={{ marginTop: 8 }}><Muted size={12}>{r.status}{r.note ? ` — ${r.note}` : ""}</Muted></div>
                : !r.outgoing && <div className="hstack g8" style={{ marginTop: 10 }}><Button small text="Resolve" onClick={() => setResolving([r, "resolved"])} /><Button small kind="secondary" text="Dismiss" onClick={() => setResolving([r, "dismissed"])} /></div>}
            </div>
          ))}
        </div>
      )}
      {resolving && <InputDialog title="Note (optional)" placeholder="What did you do?" confirm={resolving[1][0].toUpperCase() + resolving[1].slice(1)} onConfirm={(note) => { core.mod.resolve(resolving[0].id, resolving[1], note); setResolving(null); }} onDismiss={() => setResolving(null)} />}
    </>
  );
}

function AuditTab() {
  const audit = useFlow(core.mod.audit);
  if (audit.length === 0) return <EmptyState icon="book" title="No actions yet" text="Kicks, bans, locks and other moderation actions you take are recorded here, on this computer only." />;
  return (
    <div>
      {audit.map((a) => (
        <div key={a.id} style={{ padding: "10px 20px" }}>
          <div className="hstack"><span className="grow mono" style={{ color: "var(--accent-bright)", fontSize: 13 }}>{a.action}</span><Tiny>{timeAgo(a.at)}</Tiny></div>
          <div style={{ fontSize: 14 }}>{a.targetLabel || a.roomTitle}</div>
          {a.detail && <Muted size={12}>{a.detail}</Muted>}
          <Muted size={11}>{a.roomTitle}</Muted>
        </div>
      ))}
    </div>
  );
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="grow" style={{ borderRadius: 16, background: "linear-gradient(135deg, var(--accent-30), var(--surface))", boxShadow: "inset 0 0 0 1px rgb(182 144 255 / .35)", padding: 12 }}>
      <div className="display" style={{ fontSize: 26 }}>{value}</div><Tiny>{label}</Tiny>
    </div>
  );
}

function Bars({ values, color }: { values: number[]; color: string }) {
  const mx = Math.max(1, ...values);
  return (
    <div style={{ display: "flex", alignItems: "flex-end", gap: 6, height: 90 }}>
      {values.map((v, i) => <div key={i} style={{ flex: 1, height: Math.max(3, (v / mx) * 90), borderRadius: 4, background: v === 0 ? "var(--surface3)" : color }} title={String(v)} />)}
    </div>
  );
}

function StatsTab() {
  useFlow(core.mod.stats);
  const rooms = useFlow(core.rooms.rooms);
  const joins = core.mod.series("joins", 14);
  const msgs = core.mod.series("msgs", 14);
  const sum = (a: number[]) => a.reduce((x, y) => x + y, 0);
  return (
    <div style={{ padding: 16 }}>
      <div className="hstack g10"><Tile label="Rooms" value={String(rooms.length)} /><Tile label="Joins · 14d" value={String(sum(joins))} /><Tile label="Messages · 14d" value={String(sum(msgs))} /></div>
      <div style={{ height: 18 }} /><Tiny>Joins per day</Tiny><div style={{ height: 8 }} /><Bars values={joins} color="var(--accent-bright)" />
      <div style={{ height: 18 }} /><Tiny>Messages per day</Tiny><div style={{ height: 8 }} /><Bars values={msgs} color="var(--neon)" />
      <div style={{ height: 10 }} /><Muted size={12}>Counted only in rooms you host, stored only on this computer.</Muted>
    </div>
  );
}
