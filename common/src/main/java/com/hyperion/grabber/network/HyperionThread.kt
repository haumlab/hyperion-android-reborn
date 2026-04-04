package com.hyperion.grabber.common.network

import com.hyperion.grabber.common.HyperionScreenService.HyperionThreadBroadcaster
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HyperionThread(
    private val callback: HyperionThreadBroadcaster,
    private val host: String?,
    private val port: Int,
    private val priority: Int,
    reconnect: Boolean,
    delaySeconds: Int,
    private val wledEnabled: Boolean,
    private val wledIp: String?
) : Thread(TAG) {

    private val reconnectDelayMs = (delaySeconds * 1000).toLong()
    private val reconnectEnabled = AtomicBoolean(reconnect)
    private val connected = AtomicBoolean(false)
    private val clientRef = AtomicReference<HyperionClient>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    @Volatile
    private var pendingTask: Future<*>? = null
    
    @Volatile
    private var pendingFrame: FrameData? = null

    val receiver: HyperionThreadListener = object : HyperionThreadListener {
        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            val client = clientRef.get()
            if (client == null || !client.isConnected()) return

            pendingFrame = FrameData(data, width, height)

            val pending = pendingTask
            if (pending != null && !pending.isDone) {
                pending.cancel(false)
            }

            pendingTask = executor.submit { sendPendingFrame() }
        }

        private fun sendPendingFrame() {
            val frame = pendingFrame
            val client = clientRef.get()

            if (frame == null || client == null || !client.isConnected()) return

            try {
                client.setImage(frame.data, frame.width, frame.height, priority, FRAME_DURATION)

                if (client is HyperionFlatBuffers) {
                    client.cleanReplies()
                }
            } catch (e: IOException) {
                handleError(e)
            }
        }

        override fun clear() {
            val client = clientRef.get()
            if (client != null && client.isConnected()) {
                try {
                    client.clear(priority)
                } catch (e: IOException) {
                    callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")
                }
            }
        }

        override fun disconnect() {
            val pending = pendingTask
            if (pending != null) {
                pending.cancel(true)
                pendingTask = null
            }
            pendingFrame = null

            if (!executor.isShutdown) {
                executor.shutdownNow()
                try {
                    executor.awaitTermination(SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                }
            }

            val client = clientRef.getAndSet(null)
            if (client != null) {
                try {
                    client.disconnect()
                } catch (ignored: IOException) {
                }
            }

            connected.set(false)
        }

        override fun sendStatus(isGrabbing: Boolean) {
            callback.onReceiveStatus(isGrabbing)
        }
    }

    override fun run() {
        connect()
    }

    private fun createClient(): HyperionClient {
        return if (wledEnabled && wledIp != null) {
            WledDdpClient(InetAddress.getByName(wledIp), 4048)
        } else {
            HyperionFlatBuffers(host, port, priority)
        }
    }

    private fun connect() {
        do {
            try {
                val client = createClient()
                if (client.isConnected()) {
                    clientRef.set(client)
                    connected.set(true)
                    callback.onConnected()
                    return
                }
            } catch (e: IOException) {
                callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")
                if (reconnectEnabled.get() && connected.get()) {
                    sleepSafe(reconnectDelayMs)
                }
            }
        } while (reconnectEnabled.get() && connected.get())
    }

    private fun handleError(e: IOException) {
        callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")

        if (reconnectEnabled.get() && connected.get()) {
            sleepSafe(reconnectDelayMs)
            try {
                val newClient = createClient()
                if (newClient.isConnected()) {
                    clientRef.set(newClient)
                }
            } catch (ignored: IOException) {
            }
        }
    }

    private fun sleepSafe(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            reconnectEnabled.set(false)
            connected.set(false)
            currentThread().interrupt()
        }
    }

    private class FrameData(val data: ByteArray, val width: Int, val height: Int)

    interface HyperionThreadListener {
        fun sendFrame(data: ByteArray, width: Int, height: Int)
        fun clear()
        fun disconnect()
        fun sendStatus(isGrabbing: Boolean)
    }

    companion object {
        private const val TAG = "HyperionThread"
        private const val FRAME_DURATION = -1
        private const val SHUTDOWN_TIMEOUT_MS = 100
    }
}
