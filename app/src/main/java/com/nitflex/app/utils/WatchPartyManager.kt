package com.nitflex.app.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object WatchPartyManager {

    // OkHttp negotiates the WebSocket upgrade itself, so the request URL must use
    // the http/https scheme. Passing ws/wss throws IllegalArgumentException and crashes.
    private const val WS_URL = "https://streambert-xrmy.onrender.com"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    // ── State ─────────────────────────────────────────────────────────────────

    private val _inParty = MutableStateFlow(false)
    val inParty: StateFlow<Boolean> = _inParty.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    // ── Control events emitted to guest player ────────────────────────────────

    private val _controlEvents = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 8)
    val controlEvents: SharedFlow<ControlEvent> = _controlEvents.asSharedFlow()

    // ── Host tracking ─────────────────────────────────────────────────────────

    private var prevTimeMs: Long = -1L
    private var prevPlaying: Boolean = false

    // ── Models ────────────────────────────────────────────────────────────────

    data class Member(val name: String, val isHost: Boolean)

    sealed class ChatMessage {
        data class Chat(val name: String, val message: String, val timestamp: Long) : ChatMessage()
        data class System(val message: String, val timestamp: Long) : ChatMessage()
    }

    enum class SyncStatus { SYNCED, SYNCING }

    sealed class ControlEvent {
        data class Play(val timeMs: Long, val serverTimestamp: Long) : ControlEvent()
        data class Pause(val timeMs: Long) : ControlEvent()
        data class Seek(val timeMs: Long) : ControlEvent()
        data class Heartbeat(val timeMs: Long) : ControlEvent()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun createRoom(name: String) {
        connect(name) { ws, n ->
            _isHost.value = true
            _displayName.value = n
            ws.send(JSONObject().apply {
                put("type", "create")
                put("displayName", n)
            }.toString())
        }
    }

    fun joinRoom(name: String, code: String) {
        connect(name) { ws, n ->
            _isHost.value = false
            _displayName.value = n
            ws.send(JSONObject().apply {
                put("type", "join")
                put("roomCode", code.uppercase().trim())
                put("displayName", n)
            }.toString())
        }
    }

    fun leaveRoom() {
        webSocket?.close(1000, null)
        webSocket = null
        resetState()
    }

    fun sendChat(message: String) {
        send(JSONObject().apply {
            put("type", "chat")
            put("message", message)
        })
    }

    /**
     * Called by PlayerMobileFragment every 500ms while in party as host.
     * Detects play/pause/seek transitions and broadcasts them.
     */
    fun reportHostState(timeMs: Long, isPlaying: Boolean) {
        if (!_inParty.value || !_isHost.value) return

        val prev = prevTimeMs
        if (prev < 0) {
            prevTimeMs = timeMs
            prevPlaying = isPlaying
            return
        }

        val timeDelta = abs(timeMs - prev)
        val naturalAdvance = isPlaying && timeDelta <= 1500
        val seeked = timeDelta > 2000 && !naturalAdvance

        when {
            seeked -> send(JSONObject().apply {
                put("type", "seek")
                put("time", timeMs / 1000.0)
            })
            !prevPlaying && isPlaying -> send(JSONObject().apply {
                put("type", "play")
                put("time", timeMs / 1000.0)
            })
            prevPlaying && !isPlaying -> send(JSONObject().apply {
                put("type", "pause")
                put("time", timeMs / 1000.0)
            })
        }

        prevTimeMs = timeMs
        prevPlaying = isPlaying
    }

    /** Called by host every 5s while playing. */
    fun sendHeartbeat(timeMs: Long) {
        if (!_inParty.value || !_isHost.value) return
        send(JSONObject().apply {
            put("type", "heartbeat")
            put("time", timeMs / 1000.0)
        })
    }

    /** Broadcast to guests what content the host is watching. */
    fun broadcastMedia(tmdbId: String, type: String, title: String, season: Int? = null, ep: Int? = null) {
        if (!_inParty.value || !_isHost.value) return
        send(JSONObject().apply {
            put("type", "media")
            put("media", JSONObject().apply {
                put("tmdbId", tmdbId)
                put("type", type)
                put("title", title)
                if (season != null) put("season", season)
                if (ep != null) put("ep", ep)
            })
        })
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun connect(name: String, afterOpen: (WebSocket, String) -> Unit) {
        _connecting.value = true
        _connectError.value = null
        prevTimeMs = -1L
        prevPlaying = false

        val request = try {
            Request.Builder().url(WS_URL).build()
        } catch (e: Exception) {
            _connectError.value = "Could not reach the server. Try again."
            _connecting.value = false
            return
        }
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                afterOpen(webSocket, name)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        handleMessage(JSONObject(text))
                    } catch (_: Exception) {}
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (_inParty.value) resetState()
                    else _connecting.value = false
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    _connectError.value = "Could not reach the server. Try again."
                    _connecting.value = false
                }
            }
        })
    }

    private fun send(json: JSONObject) {
        try { webSocket?.send(json.toString()) } catch (_: Exception) {}
    }

    private suspend fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "created" -> {
                _roomCode.value = msg.optString("roomCode")
                _inParty.value = true
                _connecting.value = false
                _connectError.value = null
            }
            "joined" -> {
                _roomCode.value = msg.optString("roomCode")
                _members.value = parseMembers(msg.optJSONArray("members"))
                _inParty.value = true
                _connecting.value = false
                _connectError.value = null
            }
            "member-joined" -> {
                _members.value = parseMembers(msg.optJSONArray("members"))
                addSystem("${msg.optString("name")} joined")
            }
            "member-left" -> {
                _members.value = parseMembers(msg.optJSONArray("members"))
                addSystem("${msg.optString("name")} left")
            }
            "host-left" -> {
                addSystem("The host left — party ended.")
                delay(2500)
                leaveRoom()
            }
            "play" -> {
                if (_isHost.value) return
                val timeMs = (msg.optDouble("time", 0.0) * 1000).toLong()
                val serverTs = msg.optLong("serverTimestamp", System.currentTimeMillis())
                val latencyMs = System.currentTimeMillis() - serverTs
                _syncStatus.value = SyncStatus.SYNCING
                _controlEvents.emit(ControlEvent.Play(timeMs + latencyMs, serverTs))
                delay(800)
                _syncStatus.value = SyncStatus.SYNCED
            }
            "pause" -> {
                if (_isHost.value) return
                val timeMs = (msg.optDouble("time", 0.0) * 1000).toLong()
                _controlEvents.emit(ControlEvent.Pause(timeMs))
                _syncStatus.value = SyncStatus.SYNCED
            }
            "seek" -> {
                if (_isHost.value) return
                val timeMs = (msg.optDouble("time", 0.0) * 1000).toLong()
                _syncStatus.value = SyncStatus.SYNCING
                _controlEvents.emit(ControlEvent.Seek(timeMs))
                delay(800)
                _syncStatus.value = SyncStatus.SYNCED
            }
            "heartbeat" -> {
                if (_isHost.value) return
                val timeMs = (msg.optDouble("time", 0.0) * 1000).toLong()
                _controlEvents.emit(ControlEvent.Heartbeat(timeMs))
            }
            "chat" -> {
                _chatMessages.value = _chatMessages.value + ChatMessage.Chat(
                    name = msg.optString("name"),
                    message = msg.optString("message"),
                    timestamp = msg.optLong("timestamp", System.currentTimeMillis())
                )
            }
            "error" -> {
                _connectError.value = msg.optString("message", "Unknown error")
                _connecting.value = false
                leaveRoom()
            }
        }
    }

    private fun parseMembers(arr: JSONArray?): List<Member> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                Member(name = o.optString("name", "?"), isHost = o.optBoolean("isHost"))
            }
        }
    }

    private fun addSystem(text: String) {
        _chatMessages.value = _chatMessages.value + ChatMessage.System(
            message = text, timestamp = System.currentTimeMillis()
        )
    }

    private fun resetState() {
        _inParty.value = false
        _isHost.value = false
        _roomCode.value = null
        _displayName.value = null
        _members.value = emptyList()
        _chatMessages.value = emptyList()
        _syncStatus.value = SyncStatus.SYNCED
        _connectError.value = null
        _connecting.value = false
        prevTimeMs = -1L
        prevPlaying = false
    }
}
