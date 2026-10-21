package app.waveroom.net

import android.util.Base64
import app.waveroom.core.str
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/* ===========================================================================
 * SDP helpers. Invite/answer codes carry a whole SDP, so we shrink it: only
 * Opus for audio and VP8 (+RTX) for video, no link-local candidates, then
 * deflate + base64url. A typical code ends up around 1.2-2 KB, which still
 * fits in a QR code.
 * ========================================================================= */

object Sdp {

    private val rtpmapRe = Regex("^a=rtpmap:(\\d+) ([^/\\s]+)")
    private val fmtpAptRe = Regex("^a=fmtp:(\\d+) .*apt=(\\d+)")
    private val ptLineRe = Regex("^a=(rtpmap|fmtp|rtcp-fb):(\\d+)")

    /** Keep only [keep] codec names (case-insensitive) in the m-line of [kind], plus matching RTX. */
    fun pruneCodecs(sdp: String, kind: String, keep: Set<String>): String {
        val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("m=$kind ")) {
                out.add(line); i++; continue
            }
            // collect the whole media section
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("m=")) j++
            val section = lines.subList(i, j)
            out.addAll(pruneSection(section, keep))
            i = j
        }
        return out.joinToString("\r\n") + "\r\n"
    }

    private fun pruneSection(section: List<String>, keep: Set<String>): List<String> {
        val keepNames = keep.map { it.lowercase() }.toSet()
        val keptPts = HashSet<String>()
        for (l in section) {
            val m = rtpmapRe.find(l) ?: continue
            if (m.groupValues[2].lowercase() in keepNames) keptPts.add(m.groupValues[1])
        }
        if (keptPts.isEmpty()) return section          // nothing matched: leave untouched
        val rtxPts = HashSet<String>()
        for (l in section) {
            val m = rtpmapRe.find(l) ?: continue
            if (m.groupValues[2].equals("rtx", true)) {
                // find apt of this rtx
                val pt = m.groupValues[1]
                for (f in section) {
                    val fm = fmtpAptRe.find(f) ?: continue
                    if (fm.groupValues[1] == pt && fm.groupValues[2] in keptPts) rtxPts.add(pt)
                }
            }
        }
        val allowed = keptPts + rtxPts
        val out = ArrayList<String>()
        for (l in section) {
            if (l.startsWith("m=")) {
                val parts = l.split(" ")
                val head = parts.take(3)
                val pts = parts.drop(3).filter { it in allowed }
                out.add((head + pts).joinToString(" "))
                continue
            }
            val pm = ptLineRe.find(l)
            if (pm != null && pm.groupValues[2] !in allowed) continue
            out.add(l)
        }
        return out
    }

    /** Drop IPv6 link-local and TCP candidates; they bloat the code and rarely help. */
    fun filterCandidates(sdp: String): String {
        val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }
        val out = ArrayList<String>(lines.size)
        for (l in lines) {
            if (l.startsWith("a=candidate:")) {
                if (l.contains(" fe80:") || l.contains(" tcp ", true) || l.contains("tcptype")) continue
            }
            out.add(l)
        }
        return out.joinToString("\r\n") + "\r\n"
    }

    fun forOffer(sdp: String): String =
        pruneCodecs(pruneCodecs(sdp, "audio", setOf("opus")), "video", setOf("VP8"))

    /* ---- compact transport format ---- */

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            bos.write(buf, 0, n)
        }
        d.end()
        return bos.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setInput(data)
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            bos.write(buf, 0, n)
            if (bos.size() > 200_000) break
        }
        inf.end()
        return bos.toByteArray()
    }

    const val PREFIX = "WR1."

    fun pack(json: JSONObject): String {
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.encodeToString(deflate(raw), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Accepts a bare code or any text containing one (e.g. a pasted message or waveroom:// link). */
    fun unpack(text: String): JSONObject? {
        val t = text.trim()
        val at = t.indexOf(PREFIX)
        if (at < 0) return null
        var end = at + PREFIX.length
        while (end < t.length && (t[end].isLetterOrDigit() || t[end] == '-' || t[end] == '_')) end++
        val body = t.substring(at + PREFIX.length, end)
        return try {
            val bytes = inflate(Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            if (j.str("k").isEmpty()) null else j
        } catch (e: Exception) {
            null
        }
    }
}
