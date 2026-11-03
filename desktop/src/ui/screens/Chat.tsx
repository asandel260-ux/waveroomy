import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { core } from "../../app/instance";
import { DmMessage, REACTIONS, avatarFrom, defaultAvatar, isAdmin } from "../../core/models";
import { Media } from "../../core/store";
import { native } from "../../core/native";
import { fmtClock } from "../../core/util";
import { SAVED, MAX_FILE, gid, isGroup } from "../../room/dmhub";
import { useFlow } from "../hooks";
import { hsl } from "../theme";
import { asButton, Avatar, Backdrop, Button, ConfirmDialog, Dialog, EmptyState, GroupAvatar, Icon, IconButton, InputDialog, Menu, MenuEntry, Muted, RichText, Row2, Sheet, Spinner } from "../components/kit";
import type { ScreenProps } from "./registry";

export const authorColor = (uid: string) => { let h = 0; for (const c of uid) h = (h * 31 + c.charCodeAt(0)) | 0; return hsl(Math.abs(h) % 360, 0.65, 0.7); };
export const fmtSize = (n: number) => n >= 1048576 ? (n / 1048576).toFixed(1) + " MB" : n >= 1024 ? Math.floor(n / 1024) + " KB" : n + " B";
const dayKey = (ts: number) => { const d = new Date(ts); d.setHours(0, 0, 0, 0); return d.getTime(); };
const dayLabel = (ts: number) => {
  const d = dayKey(ts), t = dayKey(Date.now());
  if (d === t) return "Today";
  if (d === dayKey(t - 1)) return "Yesterday";
  return new Date(ts).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
};
const nameOf = (uid: string, snap: string) => uid === core.profile.uid ? "You" : core.social.contact(uid)?.displayName ?? (snap || "Someone");

/** Object URL for a stored attachment; refreshed when the media store changes. */
function useMediaUrl(id: string, enabled: boolean): string | null {
  const tick = useFlow(core.dmHub.mediaTick);
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!enabled) { setUrl(null); return; }
    let live = true; let made: string | null = null;
    void Media.read(id).then((b) => {
      if (!live || !b) { if (live) setUrl(null); return; }
      made = URL.createObjectURL(new Blob([b as BlobPart]));
      setUrl(made);
    });
    return () => { live = false; if (made) URL.revokeObjectURL(made); };
  }, [id, tick, enabled]);
  return url;
}

function wrapSel(el: HTMLTextAreaElement, o: string, c = o): string {
  const { selectionStart: s, selectionEnd: e, value } = el;
  const next = value.slice(0, s) + o + value.slice(s, e) + c + value.slice(e);
  requestAnimationFrame(() => { el.focus(); el.setSelectionRange(s + o.length, e + o.length); });
  return next;
}

export function ChatScreen({ nav, route, back }: ScreenProps) {
  const chat = route.t === "chat" ? route.uid : SAVED;
  const hub = core.dmHub;
  const convs = useFlow(core.dms.convs);
  const groups = useFlow(core.groups.groups);
  const contacts = useFlow(core.social.contacts);
  const blocked = useFlow(core.social.blocked);
  const connected = useFlow(core.connected);
  const me = core.profile.uid;
  const grp = isGroup(chat);
  const isSaved = chat === SAVED;
  const group = grp ? groups[gid(chat)] : undefined;
  const conv = convs[chat];
  const msgs = conv?.messages ?? [];

  const [text, setText] = useState("");
  const [replyTo, setReplyTo] = useState<DmMessage | null>(null);
  const [editing, setEditing] = useState<DmMessage | null>(null);
  const [actionOn, setActionOn] = useState<DmMessage | null>(null);
  const [forwarding, setForwarding] = useState<DmMessage | null>(null);
  const [gifDialog, setGifDialog] = useState(false);
  const [fmtBar, setFmtBar] = useState(false);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const [viewer, setViewer] = useState<DmMessage | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [drag, setDrag] = useState(false);
  const [newBelow, setNewBelow] = useState(0);
  const [atBottom, setAtBottom] = useState(true);
  const scroller = useRef<HTMLDivElement>(null);
  const ta = useRef<HTMLTextAreaElement>(null);
  const first = useRef(true);

  useEffect(() => { hub.openChat.value = chat; core.dms.markRead(chat); return () => { hub.openChat.value = null; }; }, [chat]);
  useLayoutEffect(() => {
    const el = scroller.current; if (!el || msgs.length === 0) return;
    core.dms.markRead(chat);
    const mineLast = msgs[msgs.length - 1].from === me;
    if (first.current) { el.scrollTop = el.scrollHeight; first.current = false; }
    else if (atBottom || mineLast) el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
    else setNewBelow((n) => n + 1);
  }, [msgs.length, msgs[msgs.length - 1]?.id]);
  useEffect(() => { if (atBottom) setNewBelow(0); }, [atBottom]);
  useEffect(() => { const el = ta.current; if (el) { el.style.height = "auto"; el.style.height = Math.min(el.scrollHeight, 140) + "px"; } }, [text]);

  const title = hub.titleOf(chat);
  const online = !grp && !isSaved && connected.has(chat);
  const readOnly = group?.left === true;
  const pinned = conv?.pinned ? msgs.find((m) => m.id === conv.pinned) : undefined;
  const isBlocked = blocked.includes(chat);

  const send = () => {
    const t = text;
    if (!t.trim()) return;
    if (editing) { hub.edit(chat, editing.id, t); setEditing(null); }
    else hub.send(chat, t, replyTo?.id ?? "");
    setText(""); setReplyTo(null);
  };
  const sendFiles = async (files: FileList | File[]) => {
    for (const f of Array.from(files)) {
      if (f.size > MAX_FILE) { core.toast(`${f.name} is larger than 16 MB`); continue; }
      const kind = f.type === "image/gif" ? "image" : f.type.startsWith("image/") ? "image" : "file";
      await hub.sendFile(chat, { name: f.name || "file", type: f.type || "application/octet-stream", bytes: new Uint8Array(await f.arrayBuffer()) }, kind, replyTo?.id ?? "", "");
    }
    setReplyTo(null);
  };
  const onPaste = (e: React.ClipboardEvent) => {
    const files = Array.from(e.clipboardData.files) as File[];
    if (files.length) { e.preventDefault(); void sendFiles(files); }
  };

  const list = useMemo(() => msgs, [msgs]);
  return (
    <Backdrop style={3}>
      <div style={{ display: "flex", flexDirection: "column", flex: 1, minHeight: 0 }}
        onDragOver={(e) => { if (e.dataTransfer.types.includes("Files")) { e.preventDefault(); e.stopPropagation(); setDrag(true); } }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => { if (e.dataTransfer.files.length) { e.preventDefault(); e.stopPropagation(); setDrag(false); if (!readOnly) void sendFiles(e.dataTransfer.files); } }}>
        {/* header */}
        <div className="hstack" style={{ padding: "6px 8px 6px 4px", gap: 2 }}>
          <IconButton icon="chevron-left" onClick={back} title="Back" />
          <button className="hstack g10 grow" style={{ textAlign: "left", borderRadius: 6, padding: 4, minWidth: 0 }} onClick={() => { if (grp) nav.push({ t: "groupInfo", gid: gid(chat) }); else if (!isSaved) nav.push({ t: "profile", uid: chat }); }}>
            {isSaved ? <div style={{ width: 40, height: 40, borderRadius: 6, display: "grid", placeItems: "center", background: "var(--accent)", boxShadow: "0 0 0 1px var(--border-strong)" }}><Icon name="bookmark" size={20} color="#fff" /></div>
              : grp ? <GroupAvatar emoji={group?.emoji ?? "💬"} hue={group?.hue ?? 265} size={40} />
              : <Avatar avatar={contacts[chat]?.avatar ?? defaultAvatar()} size={40} uid={chat} />}
            <div style={{ minWidth: 0 }}>
              <div className="display ellipsis" style={{ fontSize: 18 }}>{title}</div>
              {online ? <div style={{ color: "var(--neon)", fontSize: 12, fontWeight: 500 }}>connected</div> : <Muted size={12}>{isSaved ? "Notes, links and files for yourself" : grp ? `${Object.keys(group?.members ?? {}).length} members · ${hub.connectedCount(chat)} connected` : "not connected"}</Muted>}
            </div>
          </button>
          {!isSaved && !readOnly && <IconButton icon="phone" onClick={() => hub.startCall(chat)} title="Call" />}
          <IconButton icon="more-v" onClick={(e) => { const b = e.currentTarget.getBoundingClientRect(); setMenu({ x: b.right - 190, y: b.bottom }); }} />
        </div>
        {pinned && (
          <div className="hstack g8" style={{ background: "var(--surface)", padding: "8px 14px", cursor: "pointer" }} {...asButton(() => document.getElementById("m-" + pinned.id)?.scrollIntoView({ behavior: "smooth", block: "center" }), "Jump to pinned message")}>
            <Icon name="pin" size={14} color="var(--accent-bright)" />
            <span className="ellipsis grow" style={{ color: "var(--muted)", fontSize: 13 }}>{hub.preview(pinned)}</span>
            <IconButton icon="x" size={32} iconSize={16} color="var(--muted)" title="Unpin" onClick={(e) => { e.stopPropagation(); hub.togglePin(chat, pinned.id); }} />
          </div>
        )}
        {!grp && !isSaved && !online && (
          <div className="hstack g8" style={{ background: "var(--surface)", padding: "8px 16px" }}>
            <div className="grow"><Muted size={12}>{title} isn't connected. They'll get your messages as soon as you're both online.</Muted></div>
            <Button small kind="secondary" text="Retry connection" onClick={() => { core.rendezvous.kick(); core.toast(`Looking for ${title}…`); }} />
          </div>
        )}

        {/* messages */}
        {msgs.length === 0 ? (
          <div style={{ flex: 1, display: "grid", placeItems: "center" }}>
            <EmptyState icon={isSaved ? "bookmark" : grp ? "users" : "message"} title={isSaved ? "Your notes" : "No messages yet"} text={isSaved ? "Save notes, links, photos and files here. Forward any message to keep it." : "Say hello 👋"} />
          </div>
        ) : (
          <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
            <div ref={scroller} className="scroll" style={{ position: "absolute", inset: 0 }} onScroll={(e) => { const el = e.currentTarget; setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 80); }}>
              <div style={{ maxWidth: 860, margin: "0 auto", padding: "10px 16px", display: "flex", flexDirection: "column", gap: 4 }}>
                {list.map((m, i) => {
                  const before = list[i - 1];
                  const newDay = !before || dayKey(before.at) !== dayKey(m.at);
                  return (
                    <div key={m.id} id={"m-" + m.id} style={{ display: "contents" }}>
                      {newDay && <div style={{ display: "flex", justifyContent: "center", padding: "8px 0" }}><span style={{ color: "var(--muted)", fontSize: 12, fontWeight: 500, background: "var(--surface2)", borderRadius: 6, padding: "4px 12px" }}>{dayLabel(m.at)}</span></div>}
                      {m.kind === "call"
                        ? <div style={{ display: "flex", justifyContent: "center", padding: "6px 0" }}><Muted size={12}>{m.text}</Muted></div>
                        : <Bubble chat={chat} m={m} all={list} showAuthor={grp && m.from !== me && (!before || before.from !== m.from || before.kind === "call" || newDay)} onAction={() => setActionOn(m)} onView={() => setViewer(m)} />}
                    </div>
                  );
                })}
              </div>
            </div>
            {(newBelow > 0 || !atBottom) && (
              <button className="hstack g6" style={{ position: "absolute", bottom: 10, left: "50%", transform: "translateX(-50%)", borderRadius: 6, padding: "8px 14px", background: "var(--accent)", boxShadow: "0 0 0 1px var(--border-strong)", color: "#fff", fontSize: 12, fontWeight: 500 }} onClick={() => scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: "smooth" })}>
                <Icon name="arrow-down" size={14} color="#fff" />{newBelow > 0 ? `${newBelow} new` : "Latest"}
              </button>
            )}
          </div>
        )}
        {drag && <div style={{ position: "absolute", inset: 0, zIndex: 20, background: "rgb(10 7 16 / .8)", display: "grid", placeItems: "center", border: "2px dashed var(--neon)", borderRadius: 10, pointerEvents: "none" }}><div className="display" style={{ fontSize: 22 }}>Drop to send</div></div>}

        {/* composer */}
        {readOnly ? <div style={{ background: "var(--surface)", padding: 16, textAlign: "center" }}><Muted>You are no longer in this group.</Muted></div> : (
          <>
            {(replyTo || editing) && (
              <div className="hstack g10" style={{ background: "var(--surface)", padding: "6px 4px 6px 14px" }}>
                <Icon name={editing ? "edit" : "reply"} size={16} color="var(--accent-bright)" />
                <div className="grow" style={{ minWidth: 0 }}>
                  <div style={{ color: "var(--accent-bright)", fontSize: 12, fontWeight: 600 }}>{editing ? "Edit message" : `Reply to ${nameOf(replyTo!.from, replyTo!.authorName)}`}</div>
                  <div className="ellipsis" style={{ color: "var(--muted)", fontSize: 13 }}>{hub.preview((editing ?? replyTo)!)}</div>
                </div>
                <IconButton icon="x" size={36} iconSize={16} color="var(--muted)" onClick={() => { setReplyTo(null); if (editing) { setEditing(null); setText(""); } }} />
              </div>
            )}
            {fmtBar && (
              <div className="hstack" style={{ background: "var(--surface)", padding: "0 8px" }}>
                {([["bold", "**"], ["italic", "__"], ["strike", "~~"], ["code", "`"], ["eye-off", "||"]] as const).map(([ic, o]) => <IconButton key={ic} icon={ic} size={40} iconSize={18} onClick={() => ta.current && setText(wrapSel(ta.current, o))} />)}
                <IconButton icon="quote" size={40} iconSize={18} onClick={() => { const el = ta.current; if (!el) return; const s = el.selectionStart; const ls = text.lastIndexOf("\n", s - 1) + 1; setText(text.slice(0, ls) + "> " + text.slice(ls)); }} />
                <IconButton icon="file" size={40} iconSize={18} onClick={() => ta.current && setText(wrapSel(ta.current, "```\n", "\n```"))} />
                <IconButton icon="link" size={40} iconSize={18} onClick={() => ta.current && setText(wrapSel(ta.current, "[", "](https://)"))} />
              </div>
            )}
            <div className="hstack" style={{ background: "var(--surface)", padding: "8px 6px", alignItems: "flex-end", gap: 2 }}>
              <IconButton icon="paperclip" color="var(--muted)" title="Attach a file or photo" onClick={() => void hub.pickAndSend(chat, "file", replyTo?.id ?? "")} />
              <IconButton icon="image" color="var(--muted)" title="Photo" onClick={() => void hub.pickAndSend(chat, "image", replyTo?.id ?? "")} />
              <IconButton icon="gif" color="var(--muted)" title="GIF from a link" onClick={() => setGifDialog(true)} />
              <div className="grow" style={{ minHeight: 44, borderRadius: 10, background: "var(--surface2)", boxShadow: "inset 0 0 0 1px var(--border)", padding: "11px 16px", display: "flex" }}>
                <textarea ref={ta} value={text} rows={1} placeholder={isSaved ? "Write a note" : "Message"} maxLength={4000} autoFocus
                  style={{ flex: 1, resize: "none", background: "transparent", border: 0, outline: 0, color: "var(--text)", fontSize: 15, lineHeight: "22px", maxHeight: 140, margin: "-1px 0" }}
                  onChange={(e) => setText(e.target.value)} onPaste={onPaste}
                  onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } else if (e.key === "Escape") { setReplyTo(null); if (editing) { setEditing(null); setText(""); } } }} />
              </div>
              <IconButton icon="type" color={fmtBar ? "var(--accent-bright)" : "var(--muted)"} onClick={() => setFmtBar(!fmtBar)} title="Formatting" />
              <IconButton icon="send" disabled={!text.trim()} bg={text.trim() ? "var(--accent)" : "var(--surface3)"} color={text.trim() ? "var(--on-accent)" : "var(--faint)"} onClick={send} title="Send" />
            </div>
          </>
        )}
      </div>

      {menu && <Menu at={menu} onDismiss={() => setMenu(null)} entries={[
        ...(grp ? [{ label: "Group info", icon: "users", onClick: () => nav.push({ t: "groupInfo", gid: gid(chat) }) }] : []),
        ...(!grp && !isSaved ? [
          { label: "View profile", icon: "user", onClick: () => nav.push({ t: "profile", uid: chat }) },
          { label: isBlocked ? "Unblock" : "Block", icon: "ban", danger: true, onClick: () => core.block(chat, !isBlocked) }] : []),
        ...(!isSaved ? [{ label: conv?.muted ? "Unmute" : "Mute", icon: conv?.muted ? "bell" : "bell-off", onClick: () => core.dms.setMuted(chat, !conv?.muted) }] : []),
        { label: grp ? "Leave and delete" : "Delete chat", icon: "trash", danger: true, onClick: () => setConfirmDelete(true) }
      ] as MenuEntry[]} />}

      {gifDialog && <InputDialog title="GIF link" placeholder="Link to a GIF" confirm="Send" onConfirm={(u) => { hub.sendGifUrl(chat, u, replyTo?.id ?? ""); setGifDialog(false); }} onDismiss={() => setGifDialog(false)} />}

      {actionOn && (() => {
        const act = actionOn; const mine = act.from === me; const canAdmin = !!group && isAdmin(group, me);
        const close = () => setActionOn(null);
        return (
          <Sheet center onDismiss={close}>
            {!isSaved && !act.deleted && <div className="hstack" style={{ flexWrap: "wrap", gap: 8, padding: "4px 16px 8px" }}>
              {REACTIONS.map((e) => <button key={e} style={{ width: 44, height: 44, borderRadius: "50%", background: "var(--surface2)", fontSize: 22 }} onClick={() => { hub.react(chat, act.id, e); close(); }}>{e}</button>)}
            </div>}
            {!act.deleted && <>
              {!readOnly && <Row2 title="Reply" icon="reply" onClick={() => { setReplyTo(act); setEditing(null); close(); ta.current?.focus(); }} />}
              {act.text && <Row2 title="Copy text" icon="copy" onClick={() => { core.copy(act.text); close(); }} />}
              {mine && act.kind === "text" && !readOnly && <Row2 title="Edit" icon="edit" onClick={() => { setEditing(act); setReplyTo(null); setText(act.text); close(); ta.current?.focus(); }} />}
              <Row2 title="Forward" icon="forward" onClick={() => { setForwarding(act); close(); }} />
              {!isSaved && <Row2 title="Save to Saved messages" icon="bookmark" onClick={() => { hub.saveToSaved(act); close(); }} />}
              <Row2 title={conv?.pinned === act.id ? "Unpin" : "Pin"} icon="pin" onClick={() => { hub.togglePin(chat, act.id); close(); }} />
            </>}
            <Row2 title="Delete for me" icon="trash" danger onClick={() => { hub.delete(chat, act.id, false); close(); }} />
            {!isSaved && !act.deleted && (mine || canAdmin) && <Row2 title="Delete for everyone" icon="trash" danger onClick={() => { hub.delete(chat, act.id, true); close(); }} />}
          </Sheet>
        );
      })()}
      {forwarding && <ChatPicker title="Forward to" onDismiss={() => setForwarding(null)} onPick={(t) => { void hub.forward(t, forwarding); setForwarding(null); core.toast("Forwarded"); }} />}
      {viewer && <ImageViewer m={viewer} onClose={() => setViewer(null)} />}
      {confirmDelete && <ConfirmDialog danger title={grp ? "Leave and delete?" : "Delete chat?"} text={grp ? "You will leave the group and the history on this computer is erased." : "The history on this computer is erased. The other person keeps their copy."} confirm="Delete" onDismiss={() => setConfirmDelete(false)} onConfirm={() => { setConfirmDelete(false); hub.deleteChat(chat); back(); }} />}
    </Backdrop>
  );
}

function Bubble({ chat, m, all, showAuthor, onAction, onView }: { chat: string; m: DmMessage; all: DmMessage[]; showAuthor: boolean; onAction: () => void; onView: () => void }) {
  const me = core.profile.uid;
  const mine = m.from === me;
  const fg = mine ? "#fff" : "var(--text)";
  const link = mine ? "#fff" : "var(--accent-bright)";
  const r = m.reply && !m.deleted ? all.find((x) => x.id === m.reply) : null;
  return (
    <div className="bubble-row" style={{ display: "flex", flexDirection: "column", alignItems: mine ? "flex-end" : "flex-start" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 6, flexDirection: mine ? "row-reverse" : "row", maxWidth: "100%" }}>
        <div
          style={{ maxWidth: 440, minWidth: 0, background: mine ? "var(--accent)" : "var(--surface2)", color: fg, padding: "7px 10px", borderRadius: `16px 16px ${mine ? "4px 16px" : "16px 4px"}`, cursor: m.status === "failed" || m.kind === "image" || m.kind === "gif" ? "pointer" : "default" }}
          onClick={() => { if (m.status === "failed") core.dmHub.retry(chat, m.id); else if (m.kind === "image" || m.kind === "gif") onView(); }}
          onContextMenu={(e) => { e.preventDefault(); onAction(); }}>
          {showAuthor && <div style={{ color: authorColor(m.from), fontSize: 12, fontWeight: 600 }}>{nameOf(m.from, m.authorName)}</div>}
          {m.fwd && !m.deleted && <div style={{ opacity: 0.75, fontSize: 11, fontWeight: 500 }}>Forwarded from {m.fwd}</div>}
          {m.reply && !m.deleted && (
            <div className="hstack g6" style={{ margin: "3px 0", padding: 6, borderRadius: 6, background: "rgb(255 255 255 / .12)" }}>
              <div style={{ width: 3, height: 30, borderRadius: 6, background: mine ? "#fff" : "var(--accent-bright)" }} />
              <div style={{ minWidth: 0 }}>
                <div style={{ color: mine ? "#fff" : "var(--accent-bright)", fontSize: 12, fontWeight: 600 }}>{r ? nameOf(r.from, r.authorName) : "Message"}</div>
                <div className="ellipsis" style={{ opacity: 0.8, fontSize: 12 }}>{r ? core.dmHub.preview(r) : "not available"}</div>
              </div>
            </div>
          )}
          {m.deleted ? <div style={{ opacity: 0.6, fontSize: 14, fontStyle: "italic" }}>This message was deleted</div>
            : m.kind === "image" || m.kind === "gif" ? <MediaView m={m} />
            : m.kind === "file" ? <FileCard chat={chat} m={m} /> : null}
          {!m.deleted && m.text && <div style={{ paddingTop: m.kind === "text" ? 0 : 4 }}><RichText text={m.text} color={fg} linkColor={link} /></div>}
          <div className="hstack" style={{ justifyContent: "flex-end", gap: 4, paddingTop: 2, fontSize: 11, opacity: 0.8 }}>
            {m.edited && !m.deleted && <span>edited</span>}
            <span>{fmtClock(m.at)}</span>
            {mine && (m.status === "pending" ? <span>· waiting</span> : m.status === "failed" ? <span style={{ color: "#FFD0D0", fontSize: 10 }}>· {m.error || "not delivered"} · click to retry</span> : <Icon name="check" size={11} />)}
          </div>
        </div>
        <button className="bubble-more" onClick={onAction} title="Actions"><Icon name="more-v" size={16} /></button>
      </div>
      {Object.keys(m.reactions).length > 0 && (
        <div style={{ display: "flex", flexWrap: "wrap", gap: 4, paddingTop: 2 }}>
          {Object.entries(m.reactions).map(([e, who]) => {
            const on = who.includes(me);
            return <button key={e} className="hstack" style={{ borderRadius: 6, padding: "3px 8px", background: on ? "var(--accent-30)" : "var(--surface2)", boxShadow: `inset 0 0 0 1px ${on ? "rgb(82 255 31 / .7)" : "var(--border)"}`, fontSize: 13 }} onClick={() => core.dmHub.react(chat, m.id, e)}>{e}{who.length > 1 && <span style={{ color: "var(--muted)", fontSize: 12, marginLeft: 4 }}>{who.length}</span>}</button>;
          })}
        </div>
      )}
    </div>
  );
}

function MediaView({ m }: { m: DmMessage }) {
  const has = useHas(m.id);
  const prog = useFlow(core.dmHub.mediaProgress);
  const src = useMediaUrl(m.id, has && !m.url);
  const img = m.url || src;
  if (img) return <img src={img} alt="" style={{ width: 240, maxHeight: 300, minHeight: 80, objectFit: "cover", borderRadius: 6, display: "block" }} />;
  const p = prog[m.id];
  return (
    <div style={{ width: 200, height: 140, borderRadius: 6, background: "rgb(0 0 0 / .25)", display: "grid", placeItems: "center", textAlign: "center" }}>
      {p != null ? <Spinner size={30} /> : <Muted size={12}>{m.kind === "gif" ? "GIF" : "Photo"} · {fmtSize(m.size)}<br />waiting for sender</Muted>}
    </div>
  );
}

function useHas(id: string): boolean {
  const tick = useFlow(core.dmHub.mediaTick);
  const [has, setHas] = useState(false);
  useEffect(() => { let live = true; void Media.read(id).then((b) => { if (live) setHas(!!b); }); return () => { live = false; }; }, [id, tick]);
  return has;
}

function FileCard({ chat, m }: { chat: string; m: DmMessage }) {
  const has = useHas(m.id);
  const prog = useFlow(core.dmHub.mediaProgress);
  const p = prog[m.id];
  const act = async () => {
    if (has) { const b = await Media.read(m.id); if (b && (await native.saveFile(m.name || "file", b))) core.toast("Saved"); }
    else core.dmHub.download(chat, m);
  };
  return (
    <div className="hstack g10" style={{ padding: "2px 0", cursor: "pointer" }} {...asButton(act, has ? "Save file" : "Download file")}>
      <div style={{ width: 42, height: 42, borderRadius: "50%", background: "rgb(255 255 255 / .18)", display: "grid", placeItems: "center" }}>{p != null ? <Spinner size={22} /> : <Icon name={has ? "download" : "arrow-down"} size={20} />}</div>
      <div style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ fontSize: 14, fontWeight: 500, maxWidth: 190 }}>{m.name || "file"}</div>
        <div style={{ opacity: 0.7, fontSize: 11 }}>{fmtSize(m.size)}{has ? " · click to save" : " · click to download"}</div>
      </div>
    </div>
  );
}

function ImageViewer({ m, onClose }: { m: DmMessage; onClose: () => void }) {
  const has = useHas(m.id);
  const src = useMediaUrl(m.id, has && !m.url);
  const img = m.url || src;
  return (
    <div style={{ position: "fixed", inset: 0, top: 36, zIndex: 70, background: "rgb(0 0 0 / .86)", display: "grid", placeItems: "center" }} onClick={onClose}>
      {img && <img src={img} alt="" style={{ maxWidth: "92vw", maxHeight: "86vh", borderRadius: 10 }} onClick={(e) => e.stopPropagation()} />}
      <div className="hstack g8" style={{ position: "absolute", top: 12, right: 12 }}>
        {!m.url && has && <IconButton icon="download" bg="rgb(0 0 0 / .6)" color="#fff" onClick={async (e) => { e.stopPropagation(); const b = await Media.read(m.id); if (b && (await native.saveFile(m.name || (m.kind === "gif" ? "image.gif" : "image.jpg"), b))) core.toast("Saved"); }} />}
        <IconButton icon="x" bg="rgb(0 0 0 / .6)" color="#fff" onClick={onClose} />
      </div>
    </div>
  );
}

/** Pick one of my chats (Saved messages, groups, people I've messaged / met). */
export function ChatPicker({ title, onDismiss, onPick }: { title: string; onDismiss: () => void; onPick: (chat: string) => void }) {
  const groups = useFlow(core.groups.groups);
  const contacts = useFlow(core.social.contacts);
  return (
    <Sheet title={title} onDismiss={onDismiss} center>
      <div style={{ maxHeight: 480, overflowY: "auto" }}>
        <Row2 title="Saved messages" subtitle="Just for you" icon="bookmark" onClick={() => onPick(SAVED)} />
        {Object.values(groups).filter((g) => !g.left).map((g) => (
          <button key={g.id} className="listrow" onClick={() => onPick("g:" + g.id)}><GroupAvatar emoji={g.emoji} hue={g.hue} size={36} /><span style={{ fontSize: 15 }}>{g.name}</span></button>
        ))}
        {Object.values(contacts).map((c) => (
          <button key={c.uid} className="listrow" onClick={() => onPick(c.uid)}><Avatar avatar={c.avatar} size={36} uid={c.uid} /><span style={{ fontSize: 15 }}>{c.displayName}</span></button>
        ))}
      </div>
    </Sheet>
  );
}
