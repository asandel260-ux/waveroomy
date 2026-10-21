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

/**
 * Same-network discovery without any server: hosts broadcast a tiny UDP beacon,
 * listeners show them as "Nearby rooms", and tapping one runs the offer/answer
 * exchange over a short-lived TCP socket so no code has to be copied by hand.
 * (Works on Wi-Fi, a phone hotspot or any LAN that allows device-to-device traffic.)
 */
class LanService(private val ctx: Context, private val mySid: () -> String) {

    val nearby = MutableStateFlow<List<NearbyRoom>>(emptyList())

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
        if (listening) cleanup = later(2000) { sweep() }
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
        seen.clear()
        nearby.value = emptyList()
    }

    /* ====================== hosting ====================== */

    fun startHosting(h: LanHost) {
        if (hosting) {
            host = h
            return
        }
        host = h
        hosting = true
        try {
            val ss = ServerSocket(0)
            hostSocket = ss
            hostThread = Thread {
                while (hosting) {
                    val c = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    Thread { serve(c) }.also { it.isDaemon = true; it.start() }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Log.w("Lan", "cannot open server: $e")
            hosting = false
            return
        }
        beaconThread = Thread {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket()
                ds.broadcast = true
                while (hosting) {
                    val b = callMain(2000) { host?.beacon() }
                    if (b != null) {
                        b.put("wr", 1).put("sid", mySid()).put("port", hostSocket?.localPort ?: 0)
                        val data = b.toString().toByteArray(Charsets.UTF_8)
                        for (addr in broadcastAddresses()) {
                            try {
                                ds.send(DatagramPacket(data, data.size, addr, PORT))
                            } catch (e: Exception) {
                            }
                        }
                    }
                    Thread.sleep(2000)
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

    fun stopHosting() {
        hosting = false
        host = null
        try {
            hostSocket?.close()
        } catch (e: Exception) {
        }
        hostSocket = null
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
            val hello = parseJsonOrNull(rd.readLine() ?: return) ?: return
            if (hello.str("t") != "join") return
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
            val ans = parseJsonOrNull(rd.readLine() ?: return) ?: return
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
    fun join(
        room: NearbyRoom,
        myHello: JSONObject,
        accept: (offerCode: String, cb: (answer: String?) -> Unit) -> Unit,
        done: (error: String?) -> Unit
    ) {
        Thread {
            var err: String? = null
            try {
                val s = Socket()
                s.connect(InetSocketAddress(room.ip, room.port), 6000)
                s.soTimeout = 25_000
                val rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val wr = PrintWriter(s.getOutputStream(), true)
                myHello.put("t", "join").put("roomId", room.roomId)
                wr.println(myHello.toString())
                val resp = parseJsonOrNull(rd.readLine() ?: throw IllegalStateException("no reply"))
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
                        rd.readLine()
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
