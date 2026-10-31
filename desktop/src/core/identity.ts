import { b64decode, b64encode, J, nowMs, obj, str, utf8, sha256, hex, lng } from "./util";
import { Avatar, avatarFrom, avatarJson, defaultAvatar } from "./models";

/* ===========================================================================
 * Cryptographic identity. No accounts, no server: a P-256 key pair *is* the
 * account. The uid is a hash of the public key. Keys are stored exactly like the
 * Android app does (X.509 / PKCS#8, base64) so an identity can move between devices.
 * Signatures on the wire are DER-encoded ECDSA (what Java produces); WebCrypto
 * produces raw r||s, so we convert both ways.
 * ========================================================================= */

const B32 = "abcdefghijklmnopqrstuvwxyz234567";
const EC = { name: "ECDSA", namedCurve: "P-256" } as const;

function rawToDer(raw: Uint8Array): Uint8Array {
  const enc = (x: Uint8Array) => {
    let i = 0;
    while (i < x.length - 1 && x[i] === 0) i++;
    let v = x.subarray(i);
    if (v[0] & 0x80) { const t = new Uint8Array(v.length + 1); t.set(v, 1); v = t; }
    return Uint8Array.from([0x02, v.length, ...v]);
  };
  const r = enc(raw.subarray(0, 32)), s = enc(raw.subarray(32, 64));
  const body = [...r, ...s];
  return Uint8Array.from([0x30, body.length, ...body]);
}

function derToRaw(der: Uint8Array): Uint8Array {
  let p = 0;
  if (der[p++] !== 0x30) throw new Error("bad sig");
  let len = der[p++];
  if (len & 0x80) { const n = len & 0x7f; len = 0; for (let i = 0; i < n; i++) len = (len << 8) | der[p++]; }
  const out = new Uint8Array(64);
  for (let part = 0; part < 2; part++) {
    if (der[p++] !== 0x02) throw new Error("bad sig");
    let l = der[p++];
    let v = der.subarray(p, p + l);
    p += l;
    while (v.length > 32 && v[0] === 0) v = v.subarray(1);
    if (v.length > 32) throw new Error("bad sig");
    out.set(v, part * 32 + 32 - v.length);
  }
  return out;
}

export async function uidOf(pubB64: string): Promise<string> {
  const digest = await sha256(b64decode(pubB64));
  let sb = "";
  let buffer = 0, bits = 0;
  for (let i = 0; i < 10; i++) {
    buffer = (buffer << 8) | digest[i];
    bits += 8;
    while (bits >= 5) {
      sb += B32[(buffer >> (bits - 5)) & 31];
      bits -= 5;
    }
    buffer &= 0xffff;
  }
  return sb;
}

export class Identity {
  private signKey: CryptoKey | null = null;
  private dhKey: CryptoKey | null = null;
  constructor(public uid: string, public pubB64: string, private privB64: string) {}

  private async sk(): Promise<CryptoKey> {
    return (this.signKey ??= await crypto.subtle.importKey("pkcs8", b64decode(this.privB64) as BufferSource, EC, false, ["sign"]));
  }

  async sign(data: Uint8Array): Promise<string> {
    const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, await this.sk(), data as BufferSource));
    return b64encode(rawToDer(raw));
  }

  /** ECDH with someone's public key: both sides get the same secret, nobody else can. */
  async agree(theirPubB64: string): Promise<Uint8Array> {
    const priv = (this.dhKey ??= await crypto.subtle.importKey("pkcs8", b64decode(this.privB64) as BufferSource, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]));
    const pub = await crypto.subtle.importKey("spki", b64decode(theirPubB64) as BufferSource, { name: "ECDH", namedCurve: "P-256" }, false, []);
    return new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pub }, priv, 256));
  }

  toJson(): J { return { uid: this.uid, pub: this.pubB64, priv: this.privB64 }; }

  static async generate(): Promise<Identity> {
    const kp = await crypto.subtle.generateKey(EC, true, ["sign", "verify"]);
    const pub = b64encode(new Uint8Array(await crypto.subtle.exportKey("spki", kp.publicKey)));
    const priv = b64encode(new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey)));
    return new Identity(await uidOf(pub), pub, priv);
  }

  static async from(j: J): Promise<Identity | null> {
    const pub = str(j, "pub"), priv = str(j, "priv");
    if (!pub || !priv) return null;
    return new Identity(await uidOf(pub), pub, priv);
  }

  static async verify(pubB64: string, data: Uint8Array, sigB64: string): Promise<boolean> {
    try {
      const key = await crypto.subtle.importKey("spki", b64decode(pubB64) as BufferSource, EC, false, ["verify"]);
      return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, derToRaw(b64decode(sigB64)) as BufferSource, data as BufferSource);
    } catch {
      return false;
    }
  }
}

/** Friendship secret both sides derive from their two identity keys; nothing has to be exchanged. */
export async function pairSecret(id: Identity, myUid: string, uid: string, pub: string): Promise<string | null> {
  try {
    const z = await id.agree(pub);
    const [x, y] = myUid < uid ? [myUid, uid] : [uid, myUid];
    const head = utf8(`homit-pair|${x}|${y}|`);
    const all = new Uint8Array(head.length + z.length);
    all.set(head); all.set(z, head.length);
    return hex(await sha256(all)).slice(0, 48);
  } catch {
    return null;
  }
}

export interface MyProfile { username: string; displayName: string; bio: string; pronouns: string; avatar: Avatar; createdAt: number }
export const newProfile = (p: Partial<MyProfile> & { username: string; displayName: string }): MyProfile =>
  ({ bio: "", pronouns: "", avatar: defaultAvatar(), createdAt: nowMs(), ...p });
export const profileJson = (p: MyProfile): J => ({ username: p.username, displayName: p.displayName, bio: p.bio, pronouns: p.pronouns, avatar: avatarJson(p.avatar), createdAt: p.createdAt });
export const profileFrom = (j: J): MyProfile => ({
  username: str(j, "username"), displayName: str(j, "displayName"), bio: str(j, "bio"), pronouns: str(j, "pronouns"),
  avatar: avatarFrom(obj(j, "avatar")), createdAt: lng(j, "createdAt", nowMs())
});
