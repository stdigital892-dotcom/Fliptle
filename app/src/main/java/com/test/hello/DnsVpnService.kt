package com.test.hello

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A local (no remote server) VpnService that filters DNS.
 *
 * Design: split-tunnel. The VPN advertises itself as the DNS resolver
 * (VPN_DNS) and routes ONLY that address into the tunnel. Every other packet
 * bypasses the VPN and flows over the normal network untouched — so there is no
 * need to re-implement a TCP/IP stack, and "all other traffic passes normally".
 *
 * For each DNS query captured:
 *   - if a freeze is active AND the domain is blocked -> reply NXDOMAIN so the
 *     site cannot resolve;
 *   - otherwise -> forward to a real upstream resolver and relay the answer.
 */
class DnsVpnService : VpnService() {

    @Volatile private var running = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var worker: Thread? = null
    private val writeLock = Any()
    private lateinit var executor: ExecutorService
    private lateinit var freezeStore: FreezeStore

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newFixedThreadPool(4)
        freezeStore = FreezeStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (!running) {
            if (establish()) {
                running = true
                worker = Thread { runLoop() }.also { it.start() }
            } else {
                // No VPN consent yet, or establish failed.
                stopVpn()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        if (::executor.isInitialized) executor.shutdownNow()
        closeInterface()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    private fun establish(): Boolean {
        val builder = Builder()
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32) // route ONLY DNS into the tunnel
            .setSession(getString(R.string.vpn_session))
        builder.setBlocking(true)
        val pfd = try {
            builder.establish()
        } catch (_: Exception) {
            null
        } ?: return false

        vpnInterface = pfd
        tunIn = FileInputStream(pfd.fileDescriptor)
        tunOut = FileOutputStream(pfd.fileDescriptor)
        return true
    }

    private fun runLoop() {
        val packet = ByteArray(MAX_PACKET)
        val input = tunIn ?: return
        while (running) {
            val len = try {
                input.read(packet)
            } catch (_: Exception) {
                break
            }
            if (len <= 0) continue
            if ((packet[0].toInt() and 0xF0) != 0x40) continue           // IPv4 only
            if ((packet[9].toInt() and 0xFF) != PROTO_UDP) continue      // UDP only
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (len < ihl + 8) continue
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            if (dstPort != DNS_PORT) continue

            val request = packet.copyOfRange(0, len)
            val dnsPayload = packet.copyOfRange(ihl + 8, len)
            executor.execute { handleQuery(request, dnsPayload) }
        }
    }

    private fun handleQuery(request: ByteArray, dnsPayload: ByteArray) {
        val domain = parseDomain(dnsPayload)
        val blocked = domain != null &&
            freezeStore.active &&
            DomainBlocklist(this).isBlocked(domain)

        val responsePayload = if (blocked) buildNxdomain(dnsPayload) else forwardUpstream(dnsPayload)
        if (responsePayload != null) {
            val ipResponse = buildUdpResponse(request, responsePayload)
            val out = tunOut ?: return
            synchronized(writeLock) {
                try {
                    out.write(ipResponse)
                    out.flush()
                } catch (_: Exception) {
                    // interface closing
                }
            }
        }
    }

    private fun forwardUpstream(query: ByteArray): ByteArray? = try {
        DatagramSocket().use { socket ->
            protect(socket) // keep this query off the VPN to avoid a loop
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val server = InetAddress.getByName(UPSTREAM_DNS)
            socket.send(DatagramPacket(query, query.size, server, DNS_PORT))
            val buf = ByteArray(4096)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            buf.copyOfRange(0, response.length)
        }
    } catch (_: Exception) {
        null
    }

    // ---- DNS parsing / response building -------------------------------------

    private fun parseDomain(dns: ByteArray): String? {
        if (dns.size < 13) return null
        var pos = 12
        val sb = StringBuilder()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null // no compression in a question
            pos++
            if (pos + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    private fun questionEnd(dns: ByteArray): Int {
        var pos = 12
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            pos += len + 1
        }
        return pos + 4 // QTYPE (2) + QCLASS (2)
    }

    /** Response echoing the question with rcode = NXDOMAIN and no answers. */
    private fun buildNxdomain(query: ByteArray): ByteArray {
        val end = minOf(questionEnd(query), query.size)
        val resp = query.copyOfRange(0, end)
        resp[2] = 0x81.toByte() // QR=1, RD=1
        resp[3] = 0x83.toByte() // RA=1, rcode=3 (NXDOMAIN)
        resp[6] = 0; resp[7] = 0 // ancount
        resp[8] = 0; resp[9] = 0 // nscount
        resp[10] = 0; resp[11] = 0 // arcount
        return resp
    }

    /** Wrap a DNS payload in an IPv4/UDP packet, swapping the request's addresses. */
    private fun buildUdpResponse(request: ByteArray, dnsPayload: ByteArray): ByteArray {
        val ihl = (request[0].toInt() and 0x0F) * 4
        val udpLen = 8 + dnsPayload.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)
        System.arraycopy(request, 0, out, 0, ihl)

        out[2] = (totalLen shr 8).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64 // TTL
        for (i in 0 until 4) {
            out[12 + i] = request[16 + i] // src = original dst
            out[16 + i] = request[12 + i] // dst = original src
        }
        out[10] = 0; out[11] = 0
        val checksum = ipChecksum(out, ihl)
        out[10] = (checksum shr 8).toByte()
        out[11] = (checksum and 0xFF).toByte()

        val u = ihl
        out[u] = request[u + 2]; out[u + 1] = request[u + 3]     // src port = orig dst
        out[u + 2] = request[u]; out[u + 3] = request[u + 1]     // dst port = orig src
        out[u + 4] = (udpLen shr 8).toByte()
        out[u + 5] = (udpLen and 0xFF).toByte()
        out[u + 6] = 0; out[u + 7] = 0 // UDP checksum optional for IPv4
        System.arraycopy(dnsPayload, 0, out, u + 8, dnsPayload.size)
        return out
    }

    private fun ipChecksum(data: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    // ---- lifecycle helpers ---------------------------------------------------

    private fun stopVpn() {
        running = false
        worker?.interrupt()
        closeInterface()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeInterface() {
        try { tunIn?.close() } catch (_: Exception) {}
        try { tunOut?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        tunIn = null
        tunOut = null
        vpnInterface = null
    }

    private fun startForegroundNotification() {
        val channelId = "vpn_filter"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Website filter",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.vpn_notif_title))
            .setContentText(getString(R.string.vpn_notif_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.test.hello.VPN_START"
        const val ACTION_STOP = "com.test.hello.VPN_STOP"

        private const val NOTIFICATION_ID = 43
        private const val MAX_PACKET = 32_767
        private const val PROTO_UDP = 17
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val UPSTREAM_DNS = "8.8.8.8"
        private const val VPN_ADDRESS = "10.111.0.2"
        private const val VPN_DNS = "10.111.0.3"

        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
