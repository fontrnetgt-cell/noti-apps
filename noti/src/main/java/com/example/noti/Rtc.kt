package com.example.noti

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

const val SERVER = "wss://dubiously-jovial-pouncing.ngrok-free.dev"   // your fixed ngrok domain
const val ROOM = "wdmqtrled8Z3CT4OnmVyk9t9YRf1"          // pairing secret (same in server.js)

private fun turn(url: String) = PeerConnection.IceServer.builder(url)
    .setUsername("e1cc27883cdccb00c6304fb4").setPassword("lDgCkPUtH2axgaA8").createIceServer()

// Few servers = faster connection setup
val ICE = listOf(
    PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
    turn("turn:standard.relay.metered.ca:80"),
    turn("turn:standard.relay.metered.ca:443?transport=tcp")
)

/** "wifi", "cell" or "other" for the phone's current network. */
fun netType(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "other"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
        else -> "other"
    }
}

/** On cellular, skip slow direct attempts and go straight through the relay. */
fun rtcConfig(ctx: Context) = PeerConnection.RTCConfiguration(ICE).apply {
    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    iceCandidatePoolSize = 1
    if (netType(ctx) == "cell") iceTransportsType = PeerConnection.IceTransportsType.RELAY
}

/** WebSocket to your server: sends hello on every (re)connect, keep-alive pings, auto-reconnect. */
class Signaling(
    private val hello: () -> JSONObject,
    private val opened: () -> Unit = {},
    private val onMsg: (JSONObject) -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile private var closed = false
    private var delay = 3000L
    private val handler = Handler(Looper.getMainLooper())

    init {
        connect()
        handler.post(object : Runnable {
            override fun run() {
                if (closed) return
                ws?.send("{\"t\":\"ping\"}"); handler.postDelayed(this, 25000)
            }
        })
    }

    private fun connect() {
        val req = Request.Builder().url(SERVER)
            .header("ngrok-skip-browser-warning", "true").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                delay = 3000L
                w.send(hello().toString()); opened()
            }
            override fun onMessage(w: WebSocket, text: String) = onMsg(JSONObject(text))
            override fun onClosed(w: WebSocket, c: Int, r: String) = retry()
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = retry()
        })
    }
    private fun retry() {
        if (closed) return
        handler.postDelayed({ if (!closed) connect() }, delay)
        delay = minOf(delay * 2, 60000L)
    }
    fun send(o: JSONObject) { ws?.send(o.toString()) }
    fun close() { closed = true; ws?.close(1000, null) }
}

fun makeFactory(ctx: Context, egl: EglBase): PeerConnectionFactory {
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
    return PeerConnectionFactory.builder()
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        .createPeerConnectionFactory()
}

open class SimplePc : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(c: IceCandidate) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
}

open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

fun IceCandidate.toJson() = JSONObject().put("t", "ice").put("m", sdpMid)
    .put("i", sdpMLineIndex).put("c", sdp)
fun JSONObject.toIce() = IceCandidate(getString("m"), getInt("i"), getString("c"))
