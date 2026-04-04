package com.hyperion.grabber.common.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.min

class WledDdpClient(
    private val address: InetAddress,
    private val port: Int = 4048
) : HyperionClient {
    
    private var socket: DatagramSocket? = null
    private var sequenceNumber = 1

    override fun isConnected(): Boolean {
        return socket?.isClosed == false
    }

    @Throws(IOException::class)
    override fun disconnect() {
        socket?.close()
        socket = null
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // DDP does not have a native 'clear' notion in this context, send black.
    }

    @Throws(IOException::class)
    override fun clearAll() {
        // Send black.
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        // Placeholder
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        // Placeholder
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (socket == null || socket?.isClosed == true) {
            socket = DatagramSocket()
        }

        // Each DDP packet can hold up to 1440 bytes of pixel data
        val maxDataLen = 1440
        var offset = 0
        val totalLength = data.size

        while (offset < totalLength) {
            val chunkLen = min(maxDataLen, totalLength - offset)
            val isFinalPacket = (offset + chunkLen) == totalLength

            // Flags: 1 = ver1. 0x40 = PUSH. PUSH is only set on the final packet.
            val flags = if (isFinalPacket) 0x41.toByte() else 0x01.toByte()

            val packet = ByteArray(10 + chunkLen)
            
            // DDP Header
            packet[0] = flags // Flags
            packet[1] = sequenceNumber.toByte() // Sequence
            packet[2] = 1.toByte() // Data type: 1 = RGB, 0 = undefined
            packet[3] = 1.toByte() // Destination: 1 = default
            
            // Data offset (32-bit big-endian)
            packet[4] = (offset ushr 24).toByte()
            packet[5] = (offset ushr 16).toByte()
            packet[6] = (offset ushr 8).toByte()
            packet[7] = offset.toByte()

            // Data length (16-bit big-endian)
            packet[8] = (chunkLen ushr 8).toByte()
            packet[9] = chunkLen.toByte()

            // Copy data chunk
            System.arraycopy(data, offset, packet, 10, chunkLen)

            val datagram = DatagramPacket(packet, packet.size, address, port)
            socket?.send(datagram)

            offset += chunkLen
        }
        
        sequenceNumber = (sequenceNumber + 1) and 0x0F
        if (sequenceNumber == 0) sequenceNumber = 1
    }
}
