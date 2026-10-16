package com.piner.pinertv.nas

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class WakeOnLanService {

    suspend fun wakeNas(macAddress: String, broadcastAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val macBytes = parseMacAddress(macAddress)
                val magicPacket = buildMagicPacket(macBytes)
                val address = InetAddress.getByName(broadcastAddress)
                val packet = DatagramPacket(magicPacket, magicPacket.size, address, 9)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(packet)
                }
                Log.d(TAG, "WoL enviado a $macAddress")
                true
            } catch (e: Exception) {
                Log.e(TAG, "WoL error", e)
                false
            }
        }
    }

    suspend fun isNasReachable(host: String, port: Int = 5000, timeoutMs: Int = 3000): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun parseMacAddress(mac: String): ByteArray {
        val clean = mac.replace("[:-]".toRegex(), "")
        require(clean.length == 12) { "MAC inválida: $mac" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun buildMagicPacket(macBytes: ByteArray): ByteArray {
        val packet = ByteArray(102)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
        return packet
    }

    companion object {
        private const val TAG = "PinerTV-WoL"
    }
}
