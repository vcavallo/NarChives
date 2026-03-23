package com.narchives.reader.data.remote.nostr

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient as QuartzNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TAG = "NarchivesNostr"

/**
 * Thin wrapper around Quartz's NostrClient providing a simpler API
 * tailored to Narchives' needs (read-only, kind 30041 focused).
 */
class NostrClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val wsBuilder = BasicOkHttpWebSocket.Builder { okHttpClient }

    private val quartzClient = QuartzNostrClient(wsBuilder, scope)

    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 1000)
    val events: SharedFlow<RelayEvent> = _events

    private val _connectedRelays = MutableStateFlow<Set<String>>(emptySet())
    val connectedRelays: StateFlow<Set<String>> = _connectedRelays

    init {
        Log.d(TAG, "NostrClient init — registering listener")
        quartzClient.subscribe(object : IRelayClientListener {
            override fun onConnected(
                relay: IRelayClient,
                pingMillis: Int,
                compressed: Boolean,
            ) {
                Log.i(TAG, "Connected to relay: ${relay.url.url} (ping=${pingMillis}ms)")
                _connectedRelays.value = _connectedRelays.value + relay.url.url
            }

            override fun onIncomingMessage(
                relay: IRelayClient,
                msgStr: String,
                msg: Message,
            ) {
                if (msg is EventMessage) {
                    Log.d(TAG, "Event kind=${msg.event.kind} from ${relay.url.url} id=${msg.event.id.take(8)}")
                    scope.launch {
                        _events.emit(RelayEvent(relay.url.url, msg.event))
                    }
                }
            }

            override fun onDisconnected(relay: IRelayClient) {
                Log.w(TAG, "Disconnected from relay: ${relay.url.url}")
                _connectedRelays.value = _connectedRelays.value - relay.url.url
            }

            override fun onCannotConnect(
                relay: IRelayClient,
                errorMessage: String,
            ) {
                Log.e(TAG, "Cannot connect to relay ${relay.url.url}: $errorMessage")
                _connectedRelays.value = _connectedRelays.value - relay.url.url
            }
        })
    }

    fun connect() {
        Log.d(TAG, "connect() called")
        quartzClient.connect()
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        quartzClient.disconnect()
    }

    fun subscribe(
        subscriptionId: String,
        relayUrls: List<String>,
        filters: List<Filter>,
    ) {
        Log.i(TAG, "subscribe($subscriptionId) to ${relayUrls.size} relays, ${filters.size} filters")
        relayUrls.forEach { Log.d(TAG, "  relay: $it") }
        filters.forEach { Log.d(TAG, "  filter: kinds=${it.kinds} limit=${it.limit} authors=${it.authors?.size}") }

        val filterMap = relayUrls.associate { url ->
            val normalized = url.normalizeRelayUrl()
            Log.d(TAG, "  normalizeRelayUrl($url) -> $normalized")
            (normalized ?: NormalizedRelayUrl(url)) to filters
        }
        quartzClient.openReqSubscription(
            subId = subscriptionId,
            filters = filterMap,
        )
        Log.d(TAG, "openReqSubscription sent")
    }

    fun unsubscribe(subscriptionId: String) {
        Log.d(TAG, "unsubscribe($subscriptionId)")
        quartzClient.close(subscriptionId)
    }
}

data class RelayEvent(
    val relayUrl: String,
    val event: Event,
)
