package com.narchives.reader.data.remote.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient as QuartzNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EndOfStoredEventsMessage
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

/**
 * Thin wrapper around Quartz's NostrClient providing a simpler API
 * tailored to Narchives' needs (read-only, kind 30041 focused).
 */
class NostrClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val wsBuilder = BasicOkHttpWebSocket.Builder { okHttpClient }

    private val quartzClient = QuartzNostrClient(wsBuilder, scope)

    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 1000)
    val events: SharedFlow<RelayEvent> = _events

    private val _eoseSignals = MutableSharedFlow<EoseSignal>(extraBufferCapacity = 100)
    val eoseSignals: SharedFlow<EoseSignal> = _eoseSignals

    private val _connectedRelays = MutableStateFlow<Set<String>>(emptySet())
    val connectedRelays: StateFlow<Set<String>> = _connectedRelays

    init {
        quartzClient.subscribe(object : IRelayClientListener {
            override fun onConnected(
                relay: NormalizedRelayUrl,
                pingMillis: Int,
                compressed: Boolean,
            ) {
                _connectedRelays.value = _connectedRelays.value + relay.url
            }

            override fun onIncomingMessage(
                relay: NormalizedRelayUrl,
                msgStr: String,
                msg: Message,
            ) {
                scope.launch {
                    when (msg) {
                        is EventMessage -> {
                            val event = msg.event
                            _events.emit(RelayEvent(relay.url, event))
                        }
                        is EndOfStoredEventsMessage -> {
                            _eoseSignals.emit(EoseSignal(relay.url, msg.subscriptionId))
                        }
                        else -> { /* NOTICE, OK, etc — ignore for read-only client */ }
                    }
                }
            }

            override fun onDisconnected(relay: NormalizedRelayUrl) {
                _connectedRelays.value = _connectedRelays.value - relay.url
            }

            override fun onCannotConnect(
                relay: NormalizedRelayUrl,
                errorMessage: String?,
            ) {
                _connectedRelays.value = _connectedRelays.value - relay.url
            }
        })
    }

    fun connect() {
        quartzClient.connect()
    }

    fun disconnect() {
        quartzClient.disconnect()
    }

    /**
     * Subscribe to events matching filters on specific relays.
     * Returns the subscription ID for later closing.
     */
    fun subscribe(
        subscriptionId: String,
        relayUrls: List<String>,
        filters: List<Filter>,
    ) {
        val filterMap = relayUrls.associate { url ->
            val normalized = url.normalizeRelayUrl() ?: NormalizedRelayUrl(url)
            normalized to filters
        }
        quartzClient.openReqSubscription(
            subId = subscriptionId,
            filters = filterMap,
        )
    }

    fun unsubscribe(subscriptionId: String) {
        quartzClient.close(subscriptionId)
    }
}

data class RelayEvent(
    val relayUrl: String,
    val event: Event,
)

data class EoseSignal(
    val relayUrl: String,
    val subscriptionId: String,
)
