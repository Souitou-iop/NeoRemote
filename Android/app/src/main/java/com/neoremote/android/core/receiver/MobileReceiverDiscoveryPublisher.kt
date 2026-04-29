package com.neoremote.android.core.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

class MobileReceiverDiscoveryPublisher(
    context: Context,
    private val port: Int = MobileReceiverServer.DEFAULT_PORT,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? = appContext.getSystemService(NsdManager::class.java)
    private val multicastLock = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("NeoRemoteMobileReceiver")
        ?.apply { setReferenceCounted(false) }
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var udpJob: Job? = null
    private val serviceName: String
        get() = "NeoRemote Android ${Build.MODEL.orEmpty()}".trim()

    fun start() {
        registerBonjour()
        startUdpResponder()
    }

    fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null
        udpJob?.cancel()
        udpJob = null
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    fun closeScope() {
        stop()
        scope.cancel()
    }

    private fun registerBonjour() {
        val manager = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = this@MobileReceiverDiscoveryPublisher.serviceName
            port = this@MobileReceiverDiscoveryPublisher.port
            setAttribute("platform", "android")
            setAttribute("role", "mobileReceiver")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered Android receiver NSD service: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Android receiver NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Unregistered Android receiver NSD service: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Android receiver NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun startUdpResponder() {
        if (udpJob?.isActive == true) return
        udpJob = scope.launch {
            runCatching {
                multicastLock?.acquire()
                DatagramSocket(UDP_DISCOVERY_PORT).use { socket ->
                    socket.broadcast = true
                    val buffer = ByteArray(512)
                    while (isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val request = packet.data.decodeToString(0, packet.length).trim()
                        if (request != UDP_DISCOVERY_REQUEST) continue
                        val response = udpResponse().encodeToByteArray()
                        socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                }
            }.onFailure { error ->
                if (isActive) {
                    Log.w(TAG, "Android receiver UDP responder failed", error)
                }
            }
        }
    }

    private fun udpResponse(): String =
        buildString {
            appendLine(UDP_DISCOVERY_RESPONSE)
            appendLine("name=$serviceName")
            appendLine("port=$port")
            appendLine("platform=android")
            appendLine("role=mobileReceiver")
        }

    private companion object {
        const val TAG = "NeoRemoteReceiverNsd"
        const val SERVICE_TYPE = "_neoremote._tcp."
        const val UDP_DISCOVERY_PORT = 51101
        const val UDP_DISCOVERY_REQUEST = "NEOREMOTE_DISCOVER_V1"
        const val UDP_DISCOVERY_RESPONSE = "NEOREMOTE_DESKTOP_V1"
    }
}
