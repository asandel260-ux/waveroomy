package app.waveroom.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import app.waveroom.core.NearbyRoom
import app.waveroom.core.arr
import app.waveroom.core.bool
import app.waveroom.core.cancelLater
import app.waveroom.core.int
import app.waveroom.core.jsonOf
import app.waveroom.core.jsonArrayOf
import app.waveroom.core.later
import app.waveroom.core.mainHandler
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.parseJsonOrNull
import app.waveroom.core.str
import app.waveroom.core.strings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a hosting device exposes to the LAN service. All methods run on the main thread. */
interface LanHost {
    /** Beacon fields, or null when nothing should be advertised. */
    fun beacon(): JSONObject?
    /** null when the person may join, otherwise a user-facing refusal. */
    fun admit(hello: JSONObject): String?
    fun issueInvite(roomId: String, cb: (inviteId: String, code: String?) -> Unit)
    fun acceptAnswer(answer: JSONObject, cb: (Boolean) -> Unit)
}

/** App-level hooks: who I am on the LAN, and how to complete friend / answer exchanges. Main thread. */
interface LanApp {
    /** My presence beacon fields (uid, name, ...) or null when hidden. */
    fun presence(): JSONObject?
    /** null when this person may connect, otherwise a refusal. */
    fun admitFriend(hello: JSONObject): String?
    fun issueFriendInvite(cb: (code: String?) -> Unit)
    fun acceptAnswer(answer: JSONObject, cb: (Boolean) -> Unit)
}

data class NearbyPerson(val uid: String, val name: String, val username: String, val avatar: JSONObject?, val ip: String, val port: Int, val seenAt: Long)

/**
 * Same-network discovery without any server: hosts broadcast a tiny UDP beacon,
 * listeners show them as "Nearby rooms", and tapping one runs the offer/answer
 * exchange over a short-lived TCP socket so no code has to be copied by hand.
 * (Works on Wi-Fi, a phone hotspot or any LAN that allows device-to-device traffic.)
 */
private fun readCapped(r: java.io.BufferedReader, max: Int = 80_000): String? {
    val sb = StringBuilder()
    while (true) {
        val c = r.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString()
        if (c != '\r'.code) sb.append(c.toChar())
        if (sb.length > max) return null
    }
}

class LanService(private val ctx: Context, private val mySid: () -> String) {

    val nearby = MutableStateFlow<List<NearbyRoom>>(emptyList())
    val people = MutableStateFlow<List<NearbyPerson>>(emptyList())
    private val seenPeople = HashMap<String, NearbyPerson>()
    private var app: LanApp? = null
    private val active = java.util.concurrent.atomic.AtomicInteger(0)

    private var listenSocket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private var listening = false
    private var cleanup: Runnable? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private val seen = HashMap<String, NearbyRoom>()

    private var hostSocket: ServerSocket? = null
    private var hostThread: Thread? = null
    private var beaconThread: Thread? = null
    @Volatile private var hosting = false
    private var host: LanHost? = null

    /* ====================== listening ====================== */

    fun startListening() {
        if (listening) return
        listening = true
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mcLock = wm.createMulticastLock("waveroom-lan").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
            Log.i("Lan", "no multicast lock: $e")
        }
        listenThread = Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(PORT))
                listenSocket = s
                val buf = ByteArray(2048)
                while (listening) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length, Charsets.UTF_8)
                    val ip = p.address.hostAddress ?: continue
                    val j = parseJsonOrNull(text) ?: continue
                    if (j.int("wr") != 1 || j.str("sid") == mySid()) continue
                    if (j.str("k") == "p") {
                        val pp = NearbyPerson(j.str("uid"), j.str("name"), j.str("user"), j.obj("av"), ip, j.int("port"), nowMs())
                        if (pp.uid.isNotEmpty()) mainHandler.post { seenPeople[pp.uid] = pp; publishPeople() }
                        continue
                    }
                    val r = NearbyRoom(
                        roomId = j.str("room"), title = j.str("title"), topic = j.str("topic"),
                        tags = j.arr("tags")?.strings() ?: emptyList(), hostName = j.str("host"),
                        count = j.int("n"), speakers = j.int("spk"), locked = j.bool("lock"),
                        ip = ip, port = j.int("port"), seenAt = nowMs()
                    )
                    mainHandler.post { onBeacon(r) }
                }
            } catch (e: Exception) {
                if (listening) Log.w("Lan", "listen ended: $e")
            }
        }.also { it.isDaemon = true; it.start() }
        cleanup = later(2000) { sweep() }
    }

    private fun onBeacon(r: NearbyRoom) {
        seen[r.roomId + "@" + r.ip] = r
        publish()
    }

    private fun sweep() {
        val t = nowMs()
        val it = seen.entries.iterator()
        var changed = false
        while (it.hasNext()) if (t - it.next().value.seenAt > 7000) {
            it.remove(); changed = true
        }
        if (changed) publish()
        val pit = seenPeople.entries.iterator()
        var pchanged = false
        while (pit.hasNext()) if (t - pit.next().value.seenAt > 8000) { pit.remove(); pchanged = true }
        if (pchanged) publishPeople()
        if (listening) cleanup = later(2000) { sweep() }
    }

    private fun publishPeople() {
        people.value = seenPeople.values.sortedBy { it.name.lowercase() }
    }

    private fun publish() {
        nearby.value = seen.values.sortedByDescending { it.count }
    }

    fun stopListening() {
        listening = false
        cancelLater(cleanup)
        try {
            listenSocket?.close()
        } catch (e: Exception) {
        }
        listenSocket = null
        try {
            mcLock?.release()
        } catch (e: Exception) {
        }
        mcLock = null
        seen.clear(); seenPeople.clear()
        nearby.value = emptyList(); people.value = emptyList()
    }

    /* ====================== hosting ====================== */

    /** Room hosting just plugs the room into the always-on server/beacon. */
    fun startHosting(h: LanHost) {
        host = h
        hosting = true
    }

    fun stopHosting() {
        hosting = false
        host = null
    }

    /** Always-on: a TCP server (for friend connects and answer delivery) and presence/room beacons. */
    fun startServer(a: LanApp) {
        app = a
        if (hostSocket != null) return
        try {
            val ss = ServerSocket(0)
            hostSocket = ss
            hostThread = Thread {
                while (true) {
                    val c = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    if (active.incrementAndGet() > 6) { active.decrementAndGet(); try { c.close() } catch (e: Exception) {}; continue }
                    Thread { try { serve(c) } finally { active.decrementAndGet() } }.also { it.isDaemon = true; it.start() }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Log.w("Lan", "cannot open server: $e")
            return
        }
        beaconThread = Thread {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket()
                ds.broadcast = true
                while (true) {
                    val port = hostSocket?.localPort ?: 0
                    val packets = ArrayList<JSONObject>()
                    callMain(2000) { host?.beacon() }?.let { packets.add(it.put("wr", 1).put("sid", mySid()).put("port", port)) }
                    callMain(2000) { app?.presence() }?.let { packets.add(it.put("wr", 1).put("k", "p").put("sid", mySid()).put("port", port)) }
                    for (b in packets) {
                        val data = b.toString().toByteArray(Charsets.UTF_8)
                        for (addr in broadcastAddresses()) {
                            try {
                                ds.send(DatagramPacket(data, data.size, addr, PORT))
                            } catch (e: Exception) {
                            }
                        }
                    }
                    Thread.sleep(2500)
                }
            } catch (e: Exception) {
                Log.w("Lan", "beacon ended: $e")
            } finally {
                try {
                    ds?.close()
                } catch (e: Exception) {
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** "ip:port" strings other phones can use to reach this one on the local network. */
    fun addresses(): List<String> {
        val port = hostSocket?.localPort ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en != null && en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) out.add("${a.hostAddress}:$port")
                }
            }
        } catch (e: Exception) {
        }
        return out.take(3)
    }

    /** Joiner side: push our answer code straight to the inviter's phone when it is on the same network. */
    fun deliverAnswer(addrs: List<String>, code: String, done: (Boolean) -> Unit) {
        Thread {
            var ok = false
            for (a in addrs) {
                try {
                    val i = a.lastIndexOf(':')
                    val s = Socket()
                    s.connect(InetSocketAddress(a.substring(0, i), a.substring(i + 1).toInt()), 2500)
                    s.soTimeout = 8000
                    val rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val wr = PrintWriter(s.getOutputStream(), true)
                    wr.println(jsonOf("t" to "ans", "code" to code).toString())
                    val r = parseJsonOrNull(readCapped(rd) ?: "")
                    s.close()
                    if (r != null && r.str("t") == "ok") { ok = true; break }
                } catch (e: Exception) {
                }
            }
            mainHandler.post { done(ok) }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
            val en = NetworkInterface.getNetworkInterfaces()
            while (en != null && en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null && b is Inet4Address) out.add(b)
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Runs [block] on the main thread and waits (up to [ms]) for its result. */
    private fun <T> callMain(ms: Long, block: () -> T?): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        mainHandler.post {
            try {
                result = block()
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(ms, TimeUnit.MILLISECONDS)
        return result
    }

    /** Host side of one joiner's socket. */
    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 20_000
            val rd = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val wr = PrintWriter(sock.getOutputStream(), true)
            val hello = parseJsonOrNull(readCapped(rd) ?: return) ?: return
            when (hello.str("t")) {
                "ans" -> {
                    val parsed = Sdp.unpack(hello.str("code"))
                    var ok = false
                    if (parsed != null) {
                        val latch = CountDownLatch(1)
                        mainHandler.post { app?.acceptAnswer(parsed) { r -> ok = r; latch.countDown() } ?: latch.countDown() }
                        latch.await(8, TimeUnit.SECONDS)
                    }
                    wr.println(jsonOf("t" to (if (ok) "ok" else "deny")).toString())
                    return
                }
                "friend" -> {
                    val deny0 = callMain(3000) { app?.admitFriend(hello) ?: "" }
                    if (deny0 == null || deny0.isNotEmpty()) { wr.println(jsonOf("t" to "deny", "error" to (deny0 ?: "Busy.")).toString()); return }
                    var fc: String? = null
                    val l2 = CountDownLatch(1)
                    mainHandler.post { app?.issueFriendInvite { c -> fc = c; l2.countDown() } ?: l2.countDown() }
                    l2.await(10, TimeUnit.SECONDS)
                    val offer0 = fc
                    if (offer0 == null) { wr.println(jsonOf("t" to "deny", "error" to "Could not prepare a connection.").toString()); return }
                    wr.println(jsonOf("t" to "offer", "code" to offer0).toString())
                    val ans0 = parseJsonOrNull(readCapped(rd) ?: return) ?: return
                    if (ans0.str("t") != "answer") return
                    val parsed0 = Sdp.unpack(ans0.str("code")) ?: return
                    mainHandler.post { app?.acceptAnswer(parsed0) { } }
                    wr.println(jsonOf("t" to "ok").toString())
                    return
                }
                "join" -> {}
                else -> return
            }
            val deny = callMain(3000) { host?.admit(hello) ?: "" }
            if (deny == null) {
                wr.println(jsonOf("t" to "deny", "error" to "Host is busy.").toString()); return
            }
            if (deny.isNotEmpty()) {
                wr.println(jsonOf("t" to "deny", "error" to deny).toString()); return
            }
            var code: String? = null
            val latch = CountDownLatch(1)
            mainHandler.post {
                val hh = host
                if (hh == null) latch.countDown()
                else hh.issueInvite(hello.str("roomId")) { _, c -> code = c; latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
            val offer = code
            if (offer == null) {
                wr.println(jsonOf("t" to "deny", "error" to "Could not prepare a connection.").toString()); return
            }
            wr.println(jsonOf("t" to "offer", "code" to offer).toString())
            val ans = parseJsonOrNull(readCapped(rd) ?: return) ?: return
            if (ans.str("t") != "answer") return
            val parsed = Sdp.unpack(ans.str("code")) ?: return
            mainHandler.post { host?.acceptAnswer(parsed) { } }
            wr.println(jsonOf("t" to "ok").toString())
        } catch (e: Exception) {
            Log.i("Lan", "serve ended: $e")
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }

    /* ====================== joining ====================== */

    /**
     * Joiner side. [accept] must turn the host's offer code into our answer code
     * (Mesh.acceptInvite) and call back with it. [done] reports success/failure text.
     */
    fun connectPerson(
        p: NearbyPerson,
        myHello: JSONObject,
        accept: (offerCode: String, cb: (answer: String?) -> Unit) -> Unit,
        done: (error: String?) -> Unit
    ) = join(NearbyRoom("", "", "", emptyList(), "", 0, 0, false, p.ip, p.port, 0), myHello, accept, done, "friend")

    fun join(
        room: NearbyRoom,
        myHello: JSONObject,
        accept: (offerCode: String, cb: (answer: String?) -> Unit) -> Unit,
        done: (error: String?) -> Unit,
        kind: String = "join"
    ) {
        Thread {
            var err: String? = null
            try {
                val s = Socket()
                s.connect(InetSocketAddress(room.ip, room.port), 6000)
                s.soTimeout = 25_000
                val rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val wr = PrintWriter(s.getOutputStream(), true)
                myHello.put("t", kind).put("roomId", room.roomId)
                wr.println(myHello.toString())
                val resp = parseJsonOrNull(readCapped(rd) ?: throw IllegalStateException("no reply"))
                    ?: throw IllegalStateException("bad reply")
                if (resp.str("t") == "deny") {
                    err = resp.str("error", "The host refused the connection.")
                } else if (resp.str("t") == "offer") {
                    val latch = CountDownLatch(1)
                    var answer: String? = null
                    mainHandler.post {
                        accept(resp.str("code")) { a -> answer = a; latch.countDown() }
                    }
                    latch.await(15, TimeUnit.SECONDS)
                    val a = answer
                    if (a == null) {
                        err = "Could not prepare a connection."
                    } else {
                        wr.println(jsonOf("t" to "answer", "code" to a).toString())
                        readCapped(rd)
                    }
                } else err = "Unexpected reply."
                s.close()
            } catch (e: Exception) {
                err = "Could not reach the host on this network."
            }
            val finalErr = err
            mainHandler.post { done(finalErr) }
        }.also { it.isDaemon = true; it.start() }
    }

    companion object {
        const val PORT = 47877
    }
}
