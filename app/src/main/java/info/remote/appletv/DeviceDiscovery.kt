package info.remote.appletv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

data class TVDevice(val id: String, val name: String, val host: String, val port: Int)

/** All mutable discovery state is confined to the main thread. Resolve one service at a time on API 26+. */
class DeviceDiscovery(context: Context, private val update: (List<TVDevice>, String) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicast: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, TVDevice>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0
    private var active: NsdManager.DiscoveryListener? = null
    private val available = mutableSetOf<String>()

    fun start() {
        if (active != null) return
        val epoch = ++generation
        devices.clear(); available.clear(); pending.clear()
        update(emptyList(), "正在查找同一网络上的 Apple TV…")
        try {
            multicast = wifi?.createMulticastLock("apple-tv-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            update(emptyList(), "无法启用组播接收；可以尝试手动连接")
        }
        handler.postDelayed({
            if (epoch == generation && devices.isEmpty())
                update(emptyList(), "未发现设备。请确认同一 Wi-Fi；卓易通内可尝试手动连接。")
        }, 12_000)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { if (epoch == generation) { stop(); update(emptyList(), "发现失败（$code），请检查 Wi-Fi 后重试") } } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { handler.post { if (epoch == generation) { available.add(info.serviceName); pending.add(info); resolveNext(epoch) } } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post { if (epoch == generation) { available.remove(info.serviceName); devices.remove(info.serviceName); publish() } } }
        }
        active = listener
        try { manager.discoverServices("_companion-link._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { stop(); update(emptyList(), "无法发现设备：${e.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(epoch: Int) {
        if (resolving || pending.isEmpty() || epoch != generation) return
        val info = pending.removeFirst()
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post { resolving = false; resolveNext(generation) } }
            override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                resolving = false
                if (epoch == generation && service.serviceName in available) {
                    service.host?.hostAddress?.let { host -> devices[service.serviceName] = TVDevice(service.serviceName, service.serviceName, host, service.port) }
                    publish()
                }
                resolveNext(generation)
            } }
        }
        try { manager.resolveService(info, callback) } catch (e: Exception) { resolving = false; resolveNext(epoch) }
    }
    private fun publish() = update(devices.values.sortedBy { it.name }, if (devices.isEmpty()) "未发现设备；请确认 Apple TV 已唤醒且在同一 Wi-Fi" else "发现 ${devices.size} 台 Companion 设备，请确认你的 Apple TV")
    fun stop() {
        generation++; pending.clear(); available.clear()
        active?.let { try { manager.stopServiceDiscovery(it) } catch (_: Exception) {} }
        active = null
        multicast?.let { runCatching { if (it.isHeld) it.release() } }
        multicast = null
    }
}
