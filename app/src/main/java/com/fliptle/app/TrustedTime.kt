package com.fliptle.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Obtains the current epoch time from a source the device user cannot forge:
 * a network time source. The local system clock is deliberately NOT used, so
 * the freeze cannot be shortened by changing the phone's clock — even across a
 * reboot, where the monotonic clock is no longer available as an anchor.
 *
 * Strategy:
 *   1. SNTP (NTP over UDP/123) against well-known public servers.
 *   2. Fallback: the Date header of an HTTPS response (TLS-authenticated host).
 *
 * Returns epoch millis, or null if no trusted source could be reached. A null
 * result must be treated as "cannot verify" -> keep the freeze LOCKED.
 */
object TrustedTime {

    private val ntpServers = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
    private val httpsHosts = listOf(
        "https://www.google.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace"
    )

    fun fetchEpochMillis(timeoutMs: Int = 3000): Long? {
        for (host in ntpServers) {
            try {
                return requestNtp(host, timeoutMs)
            } catch (_: Exception) { /* try next */ }
        }
        for (url in httpsHosts) {
            try {
                val d = requestHttpsDate(url, timeoutMs)
                if (d > 0) return d
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun requestNtp(host: String, timeoutMs: Int): Long {
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val buf = ByteArray(48)
            // LI = 0, VN = 3, Mode = 3 (client).
            buf[0] = 0x1B
            socket.send(DatagramPacket(buf, buf.size, address, 123))
            socket.receive(DatagramPacket(buf, buf.size))
            // Transmit Timestamp: seconds (offset 40) + fraction (offset 44),
            // measured from 1900-01-01.
            val seconds = readUInt32(buf, 40)
            val fraction = readUInt32(buf, 44)
            val millis = (seconds - NTP_EPOCH_OFFSET) * 1000L +
                (fraction * 1000L / 0x1_0000_0000L)
            if (seconds == 0L) throw IllegalStateException("empty NTP response")
            return millis
        }
    }

    private fun requestHttpsDate(urlString: String, timeoutMs: Int): Long {
        val conn = URL(urlString).openConnection() as HttpsURLConnection
        return try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.connect()
            conn.date // parsed Date header (epoch millis), 0 if absent
        } finally {
            conn.disconnect()
        }
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 4) {
            result = (result shl 8) or (buf[offset + i].toLong() and 0xFF)
        }
        return result
    }

    // Seconds between 1900-01-01 (NTP epoch) and 1970-01-01 (Unix epoch).
    private const val NTP_EPOCH_OFFSET = 2_208_988_800L
}
