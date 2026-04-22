package com.neoremote.android.core.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.DesktopPlatform
import com.neoremote.android.core.model.EndpointSource

interface DiscoveryService {
    var onUpdate: ((List<DesktopEndpoint>) -> Unit)?
    fun start()
    fun stop()
    fun refresh()
}

class AndroidNsdDiscoveryService(
    context: Context,
) : DiscoveryService {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val discovered = linkedMapOf<String, DesktopEndpoint>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override var onUpdate: ((List<DesktopEndpoint>) -> Unit)? = null

    override fun start() {
        refresh()
    }

    override fun stop() {
        discoveryListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        discovered.clear()
        publish()
    }

    override fun refresh() {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = stop()

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = stop()

            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discovered.entries.removeAll { (key, _) -> key.startsWith(serviceInfo.serviceName) }
                publish()
            }
        }
        discoveryListener = listener
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @SuppressLint("DeprecatedMethod")
    private fun resolve(serviceInfo: NsdServiceInfo) {
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                val host = resolvedServiceInfo.host?.hostAddress ?: resolvedServiceInfo.host?.hostName ?: return
                val endpoint = DesktopEndpoint(
                    id = "${resolvedServiceInfo.serviceName}-$host-${resolvedServiceInfo.port}",
                    displayName = resolvedServiceInfo.serviceName.ifBlank { "Desktop" },
                    host = host,
                    port = resolvedServiceInfo.port,
                    platform = resolvedServiceInfo.serviceName.inferPlatform(),
                    lastSeenAt = System.currentTimeMillis(),
                    source = EndpointSource.DISCOVERED,
                )
                discovered[endpoint.id] = endpoint
                publish()
            }
        })
    }

    private fun publish() {
        onUpdate?.invoke(
            discovered.values.sortedByDescending { it.lastSeenAt ?: Long.MIN_VALUE },
        )
    }

    private companion object {
        const val SERVICE_TYPE = "_neoremote._tcp."
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

