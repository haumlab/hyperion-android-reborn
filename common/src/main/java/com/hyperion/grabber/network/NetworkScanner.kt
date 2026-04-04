package com.hyperion.grabber.common.network

import android.util.Log
import androidx.annotation.WorkerThread
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import kotlin.math.abs

/** Scans the local network for running Hyperion servers */
class NetworkScanner {
    private val ipsToTry: Array<String>
    private var lastTriedIndex = -1

    init {
        ipsToTry = getIPsToTry()
    }

    /** Scan the next ip address in the list of addresses to try
     * @return the hostname (or an ip represented as String) when a Hyperion server was found
     */
    @WorkerThread
    fun tryNext(): String? {
        check(hasNextAttempt()) { "No more ip addresses to try" }

        val socket = Socket()
        val ip = ipsToTry[++lastTriedIndex]
        try {
            socket.connect(InetSocketAddress(ip, PORT), ATTEMPT_TIMEOUT_MS)
            if (socket.isConnected) {
                socket.close()
                return ip
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    /** An indication of how many of the total ip's have been tried
     * @return progress in in the range [0.0 .. 1.0]
     */
    val progress: Float
        get() {
            if (ipsToTry.isEmpty()) {
                return 1f
            }
            return lastTriedIndex / ipsToTry.size.toFloat()
        }

    /** True if not all ip's have been tried yet */
    fun hasNextAttempt(): Boolean {
        return ipsToTry.isNotEmpty() && lastTriedIndex + 1 < ipsToTry.size
    }

    private fun getIPsToTry(): Array<String> {
        try {
            val localIpV4Addresses = getIPAddresses(true)
            val allIpsToTry = arrayOfNulls<String>(localIpV4Addresses.size * 254)

            for (localIpIdx in localIpV4Addresses.indices) {
                val localIpV4Address = localIpV4Addresses[localIpIdx]
                val ipsToTry = arrayOfNulls<String>(254)
                val ipParts = localIpV4Address.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val ipPrefix = ipParts[0] + "." + ipParts[1] + "." + ipParts[2] + "."
                
                for (i in 1..254) {
                    ipsToTry[i - 1] = ipPrefix + i
                }

                val localNumberInSubnet = ipParts[3].toInt()

                // sort in such a way that ips close to the local ip will be tried first
                ipsToTry.sortWith { lhs, rhs ->
                    val lhsNumberInSubnet = lhs!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[3].toInt()
                    val rhsNumberInSubnet = rhs!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[3].toInt()
                    val lhsDistance = abs(lhsNumberInSubnet - localNumberInSubnet)
                    val rhsDistance = abs(rhsNumberInSubnet - localNumberInSubnet)
                    lhsDistance - rhsDistance
                }

                for (i in ipsToTry.indices) {
                    // interleave with previously found ip addresses
                    val allIndex = localIpV4Addresses.size * i + localIpIdx
                    allIpsToTry[allIndex] = ipsToTry[i]
                }
            }
            @Suppress("UNCHECKED_CAST")
            return allIpsToTry as Array<String>
        } catch (e: Exception) {
            Log.e("HYPERION SCANNER", "Error while building list of subnet ip's", e)
            return arrayOf()
        }
    }

    companion object {
        const val PORT = 19400
        private const val ATTEMPT_TIMEOUT_MS = 50

        /**
         * Get IP addresses for non-localhost interfaces
         * @param useIPv4 true=return ipv4, false=return ipv6
         * @return a list of found addresses (may be empty)
         */
        private fun getIPAddresses(useIPv4: Boolean): List<String> {
            val foundAddresses: MutableList<String> = ArrayList()
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress ?: continue
                            val isIPv4 = sAddr.indexOf(':') < 0

                            if (useIPv4) {
                                if (isIPv4) foundAddresses.add(sAddr)
                            } else {
                                if (!isIPv4) {
                                    val delim = sAddr.indexOf('%') // drop ip6 zone suffix
                                    val v6Addr = if (delim < 0) sAddr.uppercase() else sAddr.substring(0, delim).uppercase()
                                    foundAddresses.add(v6Addr)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HYPERION SCANNER", "Could not get ip address", e)
            }
            return foundAddresses
        }
    }
}