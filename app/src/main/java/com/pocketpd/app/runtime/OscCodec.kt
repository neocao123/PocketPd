package com.pocketpd.app.runtime

import java.nio.ByteBuffer

/**
 * Minimal OSC 1.0 codec (messages + bundles) over big-endian bytes.
 * Argument types: Int ('i'), Float ('f'), String ('s'), ByteArray ('b');
 * T/F/N are decoded without payload. Bundles are decoded and dispatched
 * immediately (timetags are not scheduled).
 */
object OscCodec {

    class OscException(message: String) : Exception(message)

    data class OscMessage(val address: String, val args: List<Any>)

    // ------------------------------------------------------------------ encode

    fun encode(msg: OscMessage): ByteArray {
        require(msg.address.startsWith("/")) { "OSC address must start with '/'" }
        val bytes = ArrayList<Byte>(256)
        fun put(b: Byte) = bytes.add(b)
        fun putInt(v: Int) {
            put((v ushr 24).toByte()); put((v ushr 16).toByte())
            put((v ushr 8).toByte()); put(v.toByte())
        }
        fun putPadded(data: ByteArray) {
            for (b in data) put(b)
            repeat(pad(data.size)) { put(0) }
        }
        fun putString(s: String) = putPadded(s.toByteArray(Charsets.UTF_8))

        putString(msg.address)
        val tags = StringBuilder(",")
        for (a in msg.args) tags.append(
            when (a) {
                is Int -> 'i'
                is Float -> 'f'
                is String -> 's'
                is ByteArray -> 'b'
                is Long -> 'h'
                is Double -> 'f'
                else -> 's'
            }
        )
        putString(tags.toString())
        for (a in msg.args) when (a) {
            is Int -> putInt(a)
            is Float -> putInt(a.toRawBits())
            is String -> putString(a)
            is ByteArray -> {
                putInt(a.size)
                putPadded(a)
            }
            is Long -> {
                for (shift in 56 downTo 8 step 8) put((a ushr shift).toByte())
                put(a.toByte())
            }
            is Double -> putInt(a.toFloat().toRawBits())
            else -> putString(a.toString())
        }
        return bytes.toByteArray()
    }

    private fun pad(n: Int): Int = (4 - (n % 4)) % 4

    // ------------------------------------------------------------------ decode

    fun decode(packet: ByteArray, length: Int = packet.size): List<OscMessage> {
        val out = ArrayList<OscMessage>()
        decodeInto(packet, length, out)
        return out
    }

    private fun decodeInto(packet: ByteArray, length: Int, out: MutableList<OscMessage>) {
        val buf = ByteBuffer.wrap(packet, 0, length)
        val head = readString(buf)
        if (head == "#bundle") {
            if (buf.remaining() < 8) return
            buf.position(buf.position() + 8) // timetag: not scheduled
            while (buf.remaining() >= 4) {
                val size = buf.int
                if (size <= 0 || size > buf.remaining()) break
                val element = ByteArray(size)
                buf.get(element)
                decodeInto(element, size, out)
            }
            return
        }
        if (!head.startsWith("/")) return
        val tags = readString(buf)
        if (tags.isEmpty() || tags[0] != ',') throw OscException("missing typetags for $head")
        val args = ArrayList<Any>()
        for (t in tags.substring(1)) {
            when (t) {
                'i' -> args.add(buf.int)
                'f' -> args.add(buf.float)
                's' -> args.add(readString(buf))
                'b' -> {
                    val n = buf.int
                    if (n < 0 || n > buf.remaining()) throw OscException("bad blob length")
                    val blob = ByteArray(n)
                    buf.get(blob)
                    args.add(blob)
                    skipPad(buf, n)
                }
                'T' -> args.add(true)
                'F' -> args.add(false)
                'N' -> args.add("")
                'h' -> {
                    var v = 0L
                    repeat(8) { v = (v shl 8) or (buf.get().toLong() and 0xFF) }
                    args.add(v)
                }
                'd' -> {
                    var bits = 0L
                    repeat(8) { bits = (bits shl 8) or (buf.get().toLong() and 0xFF) }
                    args.add(Double.fromBits(bits).toFloat())
                }
                else -> throw OscException("unsupported typetag '$t'")
            }
        }
        out.add(OscMessage(head, args))
    }

    private fun readString(buf: ByteBuffer): String {
        val start = buf.position()
        var end = start
        while (end < buf.limit() && buf.get(end).toInt() != 0) end++
        if (end >= buf.limit()) throw OscException("unterminated string")
        val s = String(packetBytes(buf, start, end), Charsets.UTF_8)
        buf.position(end + 1)
        skipPad(buf, buf.position() - start)
        return s
    }

    private fun packetBytes(buf: ByteBuffer, from: Int, to: Int): ByteArray {
        val arr = ByteArray(to - from)
        val old = buf.position()
        buf.position(from)
        buf.get(arr)
        buf.position(old)
        return arr
    }

    private fun skipPad(buf: ByteBuffer, consumed: Int) {
        val skip = pad(consumed)
        if (buf.remaining() < skip) throw OscException("truncated padding")
        buf.position(buf.position() + skip)
    }
}
