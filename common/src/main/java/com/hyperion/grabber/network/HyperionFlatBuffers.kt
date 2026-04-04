package com.hyperion.grabber.common.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperionnet.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class HyperionFlatBuffers(address: String?, port: Int, priority: Int) : HyperionClient {
    private val TIMEOUT = 1000
    private val mSocket: Socket = Socket()
    private val mPriority: Int
    private val mBuilder: FlatBufferBuilder

    init {
        mSocket.tcpNoDelay = true // Disable Nagle's algorithm for low latency
        mSocket.sendBufferSize = 8192 // Smaller buffer for faster sends
        mSocket.receiveBufferSize = 4096
        mSocket.connect(InetSocketAddress(address, port), TIMEOUT)
        mSocket.soTimeout = 10 // Very short timeout for non-blocking behavior
        mPriority = priority
        mBuilder = FlatBufferBuilder(1024)
        register()
    }

    @Throws(IOException::class)
    private fun register() {
        mBuilder.clear()
        val originOffset = mBuilder.createString("HyperionAndroidGrabber")
        val registerOffset = Register.createRegister(mBuilder, originOffset, mPriority)
        val requestOffset = Request.createRequest(mBuilder, Command.Register, registerOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    override fun isConnected(): Boolean {
        return mSocket.isConnected
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (isConnected()) {
            mSocket.close()
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        mBuilder.clear()
        val clearOffset = Clear.createClear(mBuilder, priority)
        val requestOffset = Request.createRequest(mBuilder, Command.Clear, clearOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(-1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        mBuilder.clear()
        val colorOffset = Color.createColor(mBuilder, color, duration_ms)
        val requestOffset = Request.createRequest(mBuilder, Command.Color, colorOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        mBuilder.clear()
        val dataOffset = RawImage.createDataVector(mBuilder, data)
        val rawImageOffset = RawImage.createRawImage(mBuilder, dataOffset, width, height)
        val imageOffset = Image.createImage(mBuilder, ImageType.RawImage, rawImageOffset, duration_ms)
        val requestOffset = Request.createRequest(mBuilder, Command.Image, imageOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    private fun sendRequest(bb: ByteBuffer) {
        if (isConnected()) {
            val size = bb.remaining()
            val header = ByteArray(4)
            header[0] = ((size shr 24) and 0xFF).toByte()
            header[1] = ((size shr 16) and 0xFF).toByte()
            header[2] = ((size shr 8) and 0xFF).toByte()
            header[3] = (size and 0xFF).toByte()

            val output = mSocket.getOutputStream()
            output.write(header)

            val data = ByteArray(bb.remaining())
            bb[data]
            output.write(data)
            output.flush()
            
            // Don't wait for reply - fire and forget for minimal latency
            // Replies will be handled asynchronously if needed
        }
    }

    fun cleanReplies() {
        receiveReply()
    }

    private fun receiveReply() {
        // Non-blocking reply consumption to keep socket clean
        // This is called separately and doesn't block frame sending
        try {
            while (mSocket.getInputStream().available() >= 4) {
                val header = ByteArray(4)
                val read = mSocket.getInputStream().read(header, 0, 4)
                if (read == 4) {
                    val size = (header[0].toInt() and 0xFF shl 24) or
                            (header[1].toInt() and 0xFF shl 16) or
                            (header[2].toInt() and 0xFF shl 8) or
                            (header[3].toInt() and 0xFF)
                    if (size > 0 && mSocket.getInputStream().available() >= size) {
                        val data = ByteArray(size)
                        mSocket.getInputStream().read(data, 0, size)
                    } else {
                        break // Not enough data yet, will consume later
                    }
                } else {
                    break
                }
            }
        } catch (e: IOException) {
            // Ignore - non-blocking read
        }
    }
}