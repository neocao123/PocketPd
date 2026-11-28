package com.pocketpd.app.runtime

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP OSC bridge. Listens on a port and forwards decoded messages to Pd via
 * [PdEngine.handleOscIn]; sends messages produced by patches (receiver `OSC`)
 * to the configured target host/port.
 *
 * All socket work happens on background threads: the listener is a daemon
 * thread with a 1 s receive timeout, and sends go through a single-thread
 * executor (UDP sends must not run on the main thread).
 */
class OscBridge {

    /** Receives OSC messages produced by patches (Pd receiver `OSC`). */
    fun interface OscFromPdListener {
        fun onOscMessage(msg: OscCodec.OscMessage)
    }

    /** Called on the main thread for every OSC message received, with the
     *  packet's source address (replies go back to the requester, not to
     *  the configured out target). Null when the platform declines to
     *  report it. */
    fun interface OscInListener {
        fun onOscIn(msg: OscCodec.OscMessage, sender: InetSocketAddress?)
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var listenerThread: Thread? = null
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "osc-send").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var inListener: OscInListener? = null

    @Volatile
    var targetHost: String = "127.0.0.1"

    @Volatile
    var targetPort: Int = 9000

    @Synchronized
    fun start(listenPort: Int, host: String, port: Int) {
        stop()
        targetHost = host
        targetPort = port
        var created: DatagramSocket? = null
        try {
            val s = DatagramSocket(null)
            created = s
            s.reuseAddress = true
            s.bind(InetSocketAddress(listenPort))
            s.soTimeout = 1000
            socket = s
            running.set(true)
            val t = Thread({
                val buf = ByteArray(65536)
                val packet = DatagramPacket(buf, buf.size)
                while (running.get()) {
                    try {
                        s.receive(packet)
                    } catch (e: SocketException) {
                        break // socket closed by stop()
                    } catch (e: Exception) {
                        continue // receive timeout; re-check `running`
                    }
                    val messages = try {
                        OscCodec.decode(packet.data, packet.length)
                    } catch (e: Exception) {
                        PdEngine.log("osc: malformed packet (${e.message})")
                        emptyList()
                    }
                    val sender = packet.socketAddress as InetSocketAddress?
                    for (m in messages) {
                        main.post {
                            inListener?.onOscIn(m, sender)
                            PdEngine.handleOscIn(m, sender)
                        }
                    }
                }
            }, "osc-listener")
            t.isDaemon = true
            t.start()
            listenerThread = t
            PdEngine.log("osc listening on $listenPort -> $host:$port")
        } catch (e: Exception) {
            PdEngine.log("osc listen failed on $listenPort: ${e.message}")
            running.set(false)
            created?.closeQuietly()
            socket = null
        }
    }

    private fun DatagramSocket.closeQuietly() {
        runCatching { close() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        listenerThread = null
        socket?.closeQuietly()
        socket = null
    }

    fun isRunning(): Boolean = running.get() && socket != null

    /** Sends one OSC message to the given target (safe from any thread). */
    fun send(msg: OscCodec.OscMessage, host: String = targetHost, port: Int = targetPort) {
        val data = try {
            OscCodec.encode(msg)
        } catch (e: Exception) {
            PdEngine.log("osc: cannot encode ${msg.address} (${e.message})")
            return
        }
        sender.execute {
            runCatching {
                DatagramSocket().use { s ->
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
                }
            }.onFailure { PdEngine.log("osc send failed: ${it.message}") }
        }
    }
}
