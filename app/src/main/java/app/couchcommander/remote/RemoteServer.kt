package app.couchcommander.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * Listens on the home network for the phone (Garbled) and advertises itself over mDNS as
 * `_couchcommander._tcp`, so the phone finds the TV without an address typed in. Requests are few and small:
 * each connection gets one request and one answer, on a small pool of threads.
 */
class RemoteServer(private val context: Context, private val api: RemoteApi, private val name: () -> String) {
    private val pool = Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "remote").apply { isDaemon = true } }
    @Volatile private var socket: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun start() {
        if (socket != null) return
        thread(name = "remote-accept", isDaemon = true) {
            // The usual port when it's free, so curl and the phone can also reach it by address; else any.
            val server = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
            socket = server
            Log.i(TAG, "Listening on port ${server.localPort}")
            advertise(server.localPort)
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                pool.execute { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) = client.use { s ->
        runCatching {
            s.soTimeout = READ_TIMEOUT_MS
            val request = MiniHttp.read(s.getInputStream()) ?: return@runCatching MiniHttp.write(s.getOutputStream(), Response.error(400, "Bad request"))
            val response = runBlocking { api.handle(request) }
            Log.i(TAG, "${request.method} ${request.path} → ${response.status}")
            MiniHttp.write(s.getOutputStream(), response)
        }.onFailure { Log.w(TAG, "Request failed: ${it.message}") }
    }

    private fun advertise(port: Int) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name()
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("api", RemoteApi.API_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Advertised as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "mDNS registration failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { Log.w(TAG, "mDNS: ${it.message}") }
    }

    companion object {
        const val TAG = "Remote"
        const val PORT = 8765
        const val SERVICE_TYPE = "_couchcommander._tcp"
        private const val THREADS = 4
        private const val READ_TIMEOUT_MS = 10_000
    }
}
