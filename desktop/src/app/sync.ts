import type { J } from "../core/util";
import type { PeerLink } from "../net/peerlink";
import type { AppCore } from "./core";

/** Keeps my own devices (phone and desktop with the same identity) in step. Filled in below. */
export class DeviceSync {
  constructor(private core: AppCore) {}
  start() {}
  profileChanged() {}
  onLinkAuthed(_link: PeerLink) {}
  onLinkGone(_link: PeerLink) {}
  onMessage(_link: PeerLink, _m: J) {}
}
