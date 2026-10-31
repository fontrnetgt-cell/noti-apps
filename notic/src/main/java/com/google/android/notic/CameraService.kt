package com.google.android.notic

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

// Starts at boot, registers with the server, waits quietly, streams when Noti asks.
class CameraService : Service() {
    private var sig: Signaling? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var vSrc: VideoSource? = null
    private var aSrc: AudioSource? = null
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var overlay: View? = null
    private var lastNet = ""
    private var cameraReady = false     // true once Android has allowed camera use for this service run
    private var pendingPeer = false
    private var frontFacing = false
    private val ui = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<IceCandidate>()
    private val egl by lazy { EglBase.create() }
    private val factory by lazy { makeFactory(this, egl) }

    override fun onBind(i: Intent?): IBinder? = null

    private fun devId(): String {
        val sp = getSharedPreferences("n", Context.MODE_PRIVATE)
        return sp.getString("id", null) ?: UUID.randomUUID().toString()
            .also { sp.edit().putString("id", it).apply() }
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

    // Idle mode needs no camera. Camera mode is only allowed while the app is visible (see WakeActivity).
    private fun goForeground(camera: Boolean) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("notic", "Notic", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "notic").setContentTitle("Notic is ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(1, n, if (camera) cam else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 -> startForeground(1, n, cam)
            else -> startForeground(1, n)
        }
    }

    // Tiny invisible window: lets the service open WakeActivity from the background on newer Android
    private fun addOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        runCatching {
            val v = View(this)
            val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT)
            getSystemService(WindowManager::class.java).addView(v, lp); overlay = v
        }
    }

    private fun wakeUp() {
        pendingPeer = true
        runCatching { startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        ui.postDelayed({
            if (pendingPeer) { pendingPeer = false; sig?.send(JSONObject().put("t", "nocam")) }
        }, 12000)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val wantCamera = i?.getBooleanExtra("camera", false) == true
        note("dbg_svc", stamp())
        ReviveJob.schedule(this)
        try {
            goForeground(wantCamera || cameraReady)
        } catch (e: Exception) {
            note("dbg_err", "${stamp()} service: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (wantCamera) {
            cameraReady = true
            if (pendingPeer) { pendingPeer = false; startStream() }
        }
        addOverlay()
        if (sig != null) return START_STICKY

        lastNet = netType(this)
        sig = Signaling({
            note("dbg_conn", stamp())
            JSONObject().put("t", "hello").put("role", "cam").put("room", ROOM)
                .put("id", devId()).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("net", netType(this))
        }) { m ->
            when (m.getString("t")) {
                "peer" -> if (cameraReady) startStream() else wakeUp()
                "bye" -> { pendingPeer = false; stopStream() }
                "switch" -> capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(front: Boolean) {
                        frontFacing = front
                        sig?.send(JSONObject().put("t", "facing").put("front", front))
                    }
                    override fun onCameraSwitchError(e: String?) {}
                })
                "answer" -> pc?.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() { flushIce() }
                }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
                "ice" -> addIce(m.toIce())
                "config" -> { turnUser = m.getString("turnUser"); turnPass = m.getString("turnPass") }
            }
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                val now = netType(this@CameraService)
                if (now != lastNet) { lastNet = now; sig?.send(JSONObject().put("t", "net").put("net", now)) }
            }
        }
        cm.registerDefaultNetworkCallback(netCb!!)
        return START_STICKY
    }

    @Synchronized private fun startStream() {
        stopStream()
        // Only keep the processor awake while actually filming and sending.
        // Safety timeout so a missed release can never hold it forever.
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notic:stream")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        synchronized(pending) { pending.clear() }
        val en = Camera2Enumerator(this)
        frontFacing = false
        val cap = en.createCapturer(en.deviceNames.first { en.isBackFacing(it) }, null)
        helper = SurfaceTextureHelper.create("cap", egl.eglBaseContext)
        val vs = factory.createVideoSource(false)
        cap.initialize(helper, this, vs.capturerObserver)
        cap.startCapture(1280, 720, 30)
        capturer = cap; vSrc = vs
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        aSrc = factory.createAudioSource(audioConstraints)

        val p = factory.createPeerConnection(rtcConfig(this), object : SimplePc() {
            override fun onIceCandidate(c: IceCandidate) { sig?.send(c.toJson()) }
        })!!
        val sender = p.addTrack(factory.createVideoTrack("v", vs), listOf("s"))
        val aSender = p.addTrack(factory.createAudioTrack("a", aSrc), listOf("s"))
        val prm = sender.parameters
        prm.encodings.forEach { it.maxBitrateBps = 2_800_000; it.minBitrateBps = 300_000; it.maxFramerate = 30 }
        prm.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        sender.parameters = prm
        val aPrm = aSender.parameters
        aPrm.encodings.forEach { it.maxBitrateBps = 96_000 }
        aSender.parameters = aPrm
        pc = p

        p.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(SimpleSdp(), d)
                sig?.send(JSONObject().put("t", "offer").put("sdp", d.description).put("front", frontFacing))
            }
        }, MediaConstraints())
    }

    @Synchronized private fun stopStream() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); helper?.dispose(); vSrc?.dispose(); aSrc?.dispose()
        pc?.close()
        capturer = null; helper = null; vSrc = null; aSrc = null; pc = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onDestroy() {
        stopStream(); sig?.close(); sig = null
        netCb?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
    }
}
