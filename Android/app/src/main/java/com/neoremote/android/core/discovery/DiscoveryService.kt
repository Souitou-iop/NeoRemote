package com.neoremote.android.core.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.DesktopPlatform
import com.neoremote.android.core.model.EndpointSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

interface DiscoveryService {
    var onUpdate: ((List<DesktopEndpoint>) -> Unit)?
    fun start()
    fun stop()
    fun refresh()
}

class AndroidNsdDiscoveryService(
    context: Context,
) : DiscoveryService {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NsdManager::class.java)
    private val multicastLock = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("NeoRemoteNsdDiscovery")
        ?.apply { setReferenceCounted(false) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val discovered = linkedMapOf<String, DesktopEndpoint>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpDiscoveryJob: Job? = null

    override var onUpdate: ((List<DesktopEndpoint>) -> Unit)? = null

    override fun start() {
        refresh()
    }

    override fun stop() {
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
        discoveryListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
                .onFailure { Log.w(TAG, "stopServiceDiscovery failed", it) }
        }
        discoveryListener = null
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
                Log.d(TAG, "Released Wi-Fi multicast lock")
            }
        }.onFailure { Log.w(TAG, "Failed to release Wi-Fi multicast lock", it) }
        synchronized(lock) {
            discovered.clear()
        }
        publish()
    }

    override fun refresh() {
        stop()
        runCatching {
            multicastLock?.acquire()
            Log.d(TAG, "Acquired Wi-Fi multicast lock: ${multicastLock?.isHeld == true}")
        }.onFailure { Log.w(TAG, "Failed to acquire Wi-Fi multicast lock", it) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "NSD discovery start failed: type=$serviceType error=$errorCode")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "NSD discovery stop failed: type=$serviceType error=$errorCode")
                stop()
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "NSD discovery started: type=$serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "NSD discovery stopped: type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                if (!serviceInfo.serviceType.isNeoRemoteServiceType()) {
                    return
                }
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                synchronized(lock) {
                    discovered.entries.removeAll { (key, _) -> key.startsWith(serviceInfo.serviceName) }
                }
                publish()
            }
        }
        discoveryListener = listener
        Log.d(TAG, "Starting NSD discovery for $SERVICE_TYPE")
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        startUdpFallbackDiscovery()
    }

    @SuppressLint("DeprecatedMethod")
    private fun resolve(serviceInfo: NsdServiceInfo) {
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "NSD resolve failed: name=${serviceInfo?.serviceName} error=$errorCode")
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                val host = resolvedServiceInfo.host?.hostAddress ?: resolvedServiceInfo.host?.hostName ?: return
                Log.d(
                    TAG,
                    "NSD service resolved: name=${resolvedServiceInfo.serviceName} host=$host port=${resolvedServiceInfo.port}",
                )
                val endpoint = DesktopEndpoint(
                    id = "${resolvedServiceInfo.serviceName}-$host-${resolvedServiceInfo.port}",
                    displayName = resolvedServiceInfo.serviceName.ifBlank { "Desktop" },
                    host = host,
                    port = resolvedServiceInfo.port,
                    platform = resolvedServiceInfo.serviceName.inferPlatform(),
                    lastSeenAt = System.currentTimeMillis(),
                    source = EndpointSource.DISCOVERED,
                )
                remember(endpoint)
            }
        })
    }

    private fun startUdpFallbackDiscovery() {
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = scope.launch {
            val targets = udpBroadcastTargets()
            if (targets.isEmpty()) {
                Log.w(TAG, "UDP fallback discovery has no broadcast targets")
                return@launch
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = UDP_RECEIVE_TIMEOUT_MS
                val request = UDP_DISCOVERY_REQUEST.encodeToByteArray()
                repeat(UDP_DISCOVERY_ATTEMPTS) { attempt ->
                    targets.forEach { target ->
                        runCatching {
                            socket.send(DatagramPacket(request, request.size, target, UDP_DISCOVERY_PORT))
                            Log.d(TAG, "Sent UDP fallback discovery to ${target.hostAddress}:$UDP_DISCOVERY_PORT")
                        }.onFailure { Log.w(TAG, "Failed to send UDP fallback discovery", it) }
                    }

                    val deadline = System.currentTimeMillis() + UDP_RECEIVE_WINDOW_MS
                    while (isActive && System.currentTimeMillis() < deadline) {
                        val buffer = ByteArray(512)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                            handleUdpFallbackResponse(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        } catch (error: Exception) {
                            Log.w(TAG, "UDP fallback discovery receive failed", error)
                            break
                        }
                    }

                    if (attempt < UDP_DISCOVERY_ATTEMPTS - 1) {
                        delay(UDP_DISCOVERY_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun handleUdpFallbackResponse(packet: DatagramPacket) {
        val payload = packet.data.decodeToString(0, packet.length).trim()
        if (!payload.startsWith(UDP_DISCOVERY_RESPONSE)) {
            return
        }

        val fields = payload
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val port = fields["port"]?.toIntOrNull() ?: return
        val name = fields["name"].orEmpty().ifBlank { "NeoRemote Windows" }
        val host = packet.address.hostAddress ?: return
        Log.d(TAG, "UDP fallback resolved: name=$name host=$host port=$port")
        remember(
            DesktopEndpoint(
                id = "$name-$host-$port-udp",
                displayName = name,
                host = host,
                port = port,
                platform = DesktopPlatform.WINDOWS,
                lastSeenAt = System.currentTimeMillis(),
                source = EndpointSource.DISCOVERED,
            ),
        )
    }

    private fun remember(endpoint: DesktopEndpoint) {
        synchronized(lock) {
            discovered[endpoint.id] = endpoint
        }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            discovered.values.sortedByDescending { it.lastSeenAt ?: Long.MIN_VALUE }
        }
        onUpdate?.invoke(snapshot)
    }

    private fun udpBroadcastTargets(): List<InetAddress> {
        val addresses = linkedSetOf("255.255.255.255")
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.asSequence() }
                .mapNotNull { it.broadcast?.hostAddress }
                .forEach { addresses += it }
        }.onFailure { Log.w(TAG, "Failed to enumerate network broadcast addresses", it) }

        val dhcpInfo = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.dhcpInfo
        if (dhcpInfo != null && dhcpInfo.ipAddress != 0 && dhcpInfo.netmask != 0) {
            val broadcast = (dhcpInfo.ipAddress and dhcpInfo.netmask) or dhcpInfo.netmask.inv()
            addresses += broadcast.toInetAddress().hostAddress.orEmpty()
        }
        return addresses.mapNotNull { address ->
            runCatching { InetAddress.getByName(address) }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "NeoRemoteDiscovery"
        const val SERVICE_TYPE = "_neoremote._tcp."
        const val UDP_DISCOVERY_PORT = 51101
        const val UDP_DISCOVERY_REQUEST = "NEOREMOTE_DISCOVER_V1"
        const val UDP_DISCOVERY_RESPONSE = "NEOREMOTE_DESKTOP_V1"
        const val UDP_DISCOVERY_ATTEMPTS = 3
        const val UDP_RECEIVE_TIMEOUT_MS = 700
        const val UDP_RECEIVE_WINDOW_MS = 900
        const val UDP_DISCOVERY_RETRY_DELAY_MS = 350L
    }
}

class FakeDiscoveryService(
    var cannedResults: List<DesktopEndpoint> = emptyList(),
) : DiscoveryService {
    override var onUpdate: ((List<DesktopEndpoint>) -> Unit)? = null

    override fun start() {
        onUpdate?.invoke(cannedResults)
    }

    override fun stop() = Unit

    override fun refresh() {
        onUpdate?.invoke(cannedResults)
    }
}

private fun String.inferPlatform(): DesktopPlatform =
    if (contains("win", ignoreCase = true)) DesktopPlatform.WINDOWS else DesktopPlatform.MAC_OS

private fun String?.isNeoRemoteServiceType(): Boolean {
    val normalized = this
        ?.trim()
        ?.removeSuffix(".local.")
        ?.removeSuffix(".local")
        ?.trimEnd('.')
        ?: return false
    return normalized == "_neoremote._tcp"
}

private fun Int.toInetAddress(): InetAddress =
    InetAddress.getByAddress(
        byteArrayOf(
            (this and 0xff).toByte(),
            (this shr 8 and 0xff).toByte(),
            (this shr 16 and 0xff).toByte(),
            (this shr 24 and 0xff).toByte(),
        ),
    )

