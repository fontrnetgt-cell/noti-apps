package com.example.noti

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import org.webrtc.*

// Noti: pick a camera device (online/offline + WiFi/Cellular), Start stream, Switch camera.
class ViewerActivity : Activity() {
    private class Dev(val id: String, val name: String, val online: Boolean, val net: String)

    private var devices = listOf<Dev>()
    private var selectedId: String? = null
    private var wantedId: String? = null
    private var connecting = false
    private var live = false
    private var relayNote = ""
    private var pc: PeerConnection? = null
    private var sig: Signaling? = null
    private val pending = mutableListOf<IceCandidate>()
    private lateinit var factory: PeerConnectionFactory
    private lateinit var view: SurfaceViewRenderer
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val egl = EglBase.create()
        factory = makeFactory(this, egl)
        view = SurfaceViewRenderer(this).apply { init(egl.eglBaseContext, null) }
        adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner = Spinner(this).apply {
            this.adapter = this@ViewerActivity.adapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedId = devices.getOrNull(pos)?.id
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        status = TextView(this).apply { text = "Waiting for server…"; setPadding(24, 12, 24, 12) }
        val start = Button(this).apply {
            text = "Start stream"
            setOnClickListener {
                wantedId = selectedId
                if (wantedId == null) { setStatus("Select a camera device"); return@setOnClickListener }
                pc?.close(); pc = null; live = false
                requestConnect()
            }
        }
        val stop = Button(this).apply {
            text = "Stop stream"
            setOnClickListener { stopStream() }
        }
        val switch = Button(this).apply {
            text = "Switch camera"
            setOnClickListener { sig?.send(JSONObject().put("t", "switch")) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spinner)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status); addView(start); addView(stop); addView(switch)
        })
        connectServer()
    }

    private fun label(d: Dev): String {
        if (!d.online) return "○ ${d.name} · offline"
        val n = when (d.net) { "wifi" -> "WiFi"; "cell" -> "Cellular"; else -> "Online" }
        return "● ${d.name} · $n"
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun updateList(list: List<Dev>) = runOnUiThread {
        val keep = selectedId ?: wantedId
        devices = list
        adapter.clear(); adapter.addAll(list.map { label(it) })
        val idx = list.indexOfFirst { it.id == keep }
        if (idx >= 0) spinner.setSelection(idx) else if (list.isNotEmpty()) spinner.setSelection(0)
        if (list.isEmpty()) status.text = "No camera online right now"
        else if (!connecting && !live && wantedId == null) status.text = "Select a device and tap Start"
    }

    private fun addIce(c: IceCandidate) {
        val p = pc
        if (p != null && p.remoteDescription != null) p.addIceCandidate(c)
        else synchronized(pending) { pending += c }
    }
    private fun flushIce() {
        val p = pc ?: return
        synchronized(pending) { pending.forEach { p.addIceCandidate(it) }; pending.clear() }
    }

    private fun requestConnect() {
        val id = wantedId ?: return
        val d = devices.firstOrNull { it.id == id }
        if (d == null || !d.online) { setStatus("Camera offline, waiting…"); return }
        connecting = true; live = false
        setStatus("Connecting…")
        sig?.send(JSONObject().put("t", "connect").put("id", id))
    }

    private fun stopStream() {
        wantedId = null; connecting = false; live = false
        sig?.send(JSONObject().put("t", "stop"))   // camera stops filming and sending
        pc?.close(); pc = null
        view.clearImage()
        setStatus("Stopped")
    }

    private fun connectServer() {
        sig = Signaling(
            { JSONObject().put("t", "hello").put("role", "view").put("room", ROOM) },
            { connecting = false; live = false; pc?.close(); pc = null }
        ) { m ->
            when (m.getString("t")) {
                "list" -> {
                    val arr = m.getJSONArray("devices")
                    updateList((0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        Dev(o.getString("id"), o.getString("name"), o.getBoolean("online"), o.optString("net"))
                    })
                    if (wantedId != null && !connecting && !live) requestConnect()
                }
                "offer" -> {
                    pc?.close()
                    synchronized(pending) { pending.clear() }
                    relayNote = ""
                    val p = newPc(); pc = p
                    p.setRemoteDescription(object : SimpleSdp() {
                        override fun onSetSuccess() {
                            flushIce()
                            p.createAnswer(object : SimpleSdp() {
                                override fun onCreateSuccess(d: SessionDescription) {
                                    p.setLocalDescription(SimpleSdp(), d)
                                    sig?.send(JSONObject().put("t", "answer").put("sdp", d.description))
                                }
                            }, MediaConstraints())
                        }
                    }, SessionDescription(SessionDescription.Type.OFFER, m.getString("sdp")))
                }
                "ice" -> addIce(m.toIce())
                "bye" -> {
                    live = false; connecting = false; pc?.close(); pc = null
                    setStatus("Camera offline, waiting…")
                }
                "busy" -> { connecting = false; wantedId = null; setStatus("Camera is used by another viewer") }
                "offline" -> { connecting = false; setStatus("Camera offline") }
                "nocam" -> { connecting = false; setStatus("Camera phone could not start: open Notic once there") }
            }
        }
    }

    private fun newPc() = factory.createPeerConnection(rtcConfig(this), object : SimplePc() {
        override fun onIceCandidate(c: IceCandidate) {
            if (c.sdp.contains(" typ relay")) relayNote = " (relay ready)"
            sig?.send(c.toJson())
        }
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE && relayNote.isEmpty())
                relayNote = " (NO relay: check TURN login)"
        }
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {
            (r?.track() as? VideoTrack)?.addSink(view)
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    live = true; connecting = false; setStatus("Live$relayNote")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> setStatus("Reconnecting…")
                PeerConnection.IceConnectionState.FAILED -> {
                    live = false; connecting = false; runOnUiThread { requestConnect() }
                }
                else -> {}
            }
        }
    })!!

    override fun onDestroy() { super.onDestroy(); sig?.close(); pc?.close() }
}
