import { arr, b64decode, b64encode, int, nowMs, obj, objects, str, strings, type J } from "../core/util";
import { contactFrom, contactJson } from "../core/models";
import { profileFrom, profileJson } from "../core/identity";
import { Docs } from "../core/store";
import type { PeerLink } from "../net/peerlink";
import type { AppCore } from "./core";

/**
 * Keeps my own devices (phone and desktop signed in with the same identity) in step over a
 * "sync" link: profile (newest edit wins), contacts, people I follow / who follow me, blocks.
 */
export class DeviceSync {
  private links = new Set<PeerLink>();
  private applying = false;
  private ts = 0;
  constructor(private core: AppCore) {}

  start() { this.ts = int(Docs.read("sync.json") ?? {}, "ts"); }

  private stamp(t: number) { this.ts = t; Docs.write("sync.json", { ts: t }); }

  /** Called whenever my profile was edited on this device. */
  profileChanged() {
    if (this.applying) return;
    this.stamp(nowMs());
    this.pushAll();
  }

  private snapshot(): J {
    const c = this.core, p = c.profile.profile.value;
    const j: J = {
      t: "sy", ts: this.ts,
      contacts: Object.values(c.social.contacts.value).map(contactJson),
      following: c.social.following.value, followers: c.social.followers.value, blocked: c.social.blocked.value,
    };
    if (p) j.profile = profileJson(p);
    if (c.profile.photo) j.photo = b64encode(c.profile.photo);
    return j;
  }

  private pushAll() { const s = JSON.stringify(this.snapshot()); for (const l of this.links) l.send(s); }

  onLinkAuthed(link: PeerLink) {
    this.links.add(link);
    link.send(JSON.stringify(this.snapshot()));
    this.core.toast("Synced with your other device");
  }
  onLinkGone(link: PeerLink) { this.links.delete(link); }

  onMessage(link: PeerLink, m: J) {
    if (str(m, "t") !== "sy") return;
    const c = this.core;
    this.applying = true;
    try {
      const rts = int(m, "ts");
      const pj = obj(m, "profile");
      if (pj && rts > this.ts) {
        c.profile.save(profileFrom(pj));
        const ph = str(m, "photo");
        c.profile.setPhoto(ph ? b64decode(ph) : null);
        this.stamp(rts);
      }
      for (const j of objects(arr(m, "contacts"))) {
        const x = contactFrom(j);
        if (x.uid && x.uid !== c.profile.uid && !c.social.contact(x.uid)) c.social.upsert(x);
      }
      const merge = (cur: string[], inc: string[]) => [...new Set([...cur, ...inc])];
      const fo = merge(c.social.following.value, strings(arr(m, "following")));
      const fr = merge(c.social.followers.value, strings(arr(m, "followers")));
      const bl = merge(c.social.blocked.value, strings(arr(m, "blocked")));
      if (fo.length !== c.social.following.value.length) c.social.following.value = fo;
      if (fr.length !== c.social.followers.value.length) c.social.followers.value = fr;
      if (bl.length !== c.social.blocked.value.length) c.social.blocked.value = bl;
      c.social.flush();
    } finally { this.applying = false; }
    void link;
  }
}
