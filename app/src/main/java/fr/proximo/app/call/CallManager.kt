package fr.proximo.app.call

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.proximo.app.MainActivity
import fr.proximo.app.R
import fr.proximo.app.core.Db
import fr.proximo.app.core.Hex
import fr.proximo.app.mesh.Frame
import fr.proximo.app.mesh.MeshCore
import fr.proximo.app.mesh.MeshService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class CallState(val phase: Int, val peerId: String, val peerName: String, val since: Long, val speaker: Boolean = true, val muted: Boolean = false) {
    companion object { const val IDLE = 0; const val RINGING_IN = 1; const val RINGING_OUT = 2; const val ACTIVE = 3 }
}

/**
 * Appels vocaux en direct (bêta) : son PCM 8 kHz envoyé par petits paquets au voisin, par Wi-Fi direct.
 * Pas de relais : il faut que l'autre téléphone soit à portée Wi-Fi.
 */
@SuppressLint("MissingPermission")
class CallManager(private val ctx: Context, private val core: MeshCore) {
    private val tag = "ProximoAppel"
    private val db = Db.get(ctx)
    val state = MutableStateFlow(CallState(CallState.IDLE, "", "", 0))
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun canCall(peerId: String): Boolean = core.peer(peerId)?.wifi == true

    fun call(peerId: String) {
        if (state.value.phase != CallState.IDLE) return
        if (!canCall(peerId)) return
        val name = db.contact(peerId)?.name ?: core.peer(peerId)?.name ?: "Contact"
        state.value = CallState(CallState.RINGING_OUT, peerId, name, System.currentTimeMillis())
        signal(peerId, "invite")
    }

    fun accept() {
        val s = state.value; if (s.phase != CallState.RINGING_IN) return
        stopRing()
        signal(s.peerId, "accept")
        startAudio(s)
    }

    fun reject() {
        val s = state.value; if (s.phase != CallState.RINGING_IN) return
        stopRing(); signal(s.peerId, "reject")
        state.value = CallState(CallState.IDLE, "", "", 0)
        NotificationManagerCompat.from(ctx).cancel(NOTIF)
    }

    fun hangup() {
        val s = state.value
        if (s.phase == CallState.IDLE) return
        signal(s.peerId, "hangup")
        endCall()
    }

    fun toggleSpeaker() { val s = state.value; state.value = s.copy(speaker = !s.speaker); audio.isSpeakerphoneOn = !s.speaker }
    fun toggleMute() { val s = state.value; state.value = s.copy(muted = !s.muted) }

    private fun signal(peerId: String, c: String) {
        core.send(Frame(Frame.CALL, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), JSONObject().put("c", c).toString().toByteArray()))
    }

    /** Rend true si la trame concernait un appel. */
    fun handle(f: Frame, fromPeer: String): Boolean {
        if (f.type != Frame.CALL) return false
        val p = f.payload
        if (p.isNotEmpty() && p[0] == 1.toByte()) { // son
            val s = state.value
            if (s.phase == CallState.ACTIVE && s.peerId == f.fromHex) track?.write(p, 1, p.size - 1)
            return true
        }
        val c = try { JSONObject(String(p)).optString("c") } catch (e: Exception) { return true }
        val from = f.fromHex
        val s = state.value
        when (c) {
            "invite" -> {
                if (s.phase != CallState.IDLE) { signal(from, "busy"); return true }
                val name = db.contact(from)?.name ?: core.peer(from)?.name ?: "Inconnu"
                state.value = CallState(CallState.RINGING_IN, from, name, System.currentTimeMillis())
                ring(); notifyIncoming(name)
            }
            "accept" -> if (s.phase == CallState.RINGING_OUT && s.peerId == from) startAudio(s)
            "reject", "busy" -> if (s.peerId == from) { endCall(); MeshService.instance?.messenger?.events?.value = if (c == "busy") "Occupé" else "Appel refusé" }
            "hangup" -> if (s.peerId == from) endCall()
        }
        return true
    }

    private fun startAudio(s: CallState) {
        state.value = s.copy(phase = CallState.ACTIVE, since = System.currentTimeMillis())
        NotificationManagerCompat.from(ctx).cancel(NOTIF)
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = s.speaker
            val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minOut, CHUNK * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
            track?.play()
            val minIn = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, CHUNK * 4))
            record?.startRecording()
            running.set(true)
            Thread {
                val buf = ByteArray(CHUNK)
                val silence = ByteArray(CHUNK)
                while (running.get()) {
                    val n = record?.read(buf, 0, CHUNK) ?: -1
                    if (n <= 0) continue
                    val src = if (state.value.muted) silence else buf
                    val payload = ByteArray(1 + n); payload[0] = 1; System.arraycopy(src, 0, payload, 1, n)
                    core.sendTo(state.value.peerId, Frame(Frame.CALL, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(state.value.peerId), payload).encode())
                }
            }.start()
        } catch (e: Exception) { Log.e(tag, "audio", e); hangup() }
    }

    private fun endCall() {
        running.set(false)
        stopRing()
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        record = null; track = null
        try { audio.mode = AudioManager.MODE_NORMAL; audio.isSpeakerphoneOn = false } catch (_: Exception) {}
        NotificationManagerCompat.from(ctx).cancel(NOTIF)
        state.value = CallState(CallState.IDLE, "", "", 0)
    }

    private fun ring() {
        try {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.play()
        } catch (_: Exception) {}
    }
    private fun stopRing() { try { ringtone?.stop() } catch (_: Exception) {}; ringtone = null }

    private fun notifyIncoming(name: String) {
        try {
            val i = Intent(ctx, MainActivity::class.java).putExtra("call", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, MeshService.CH_CALLS).setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Appel de $name").setContentText("Ouvre Proximo pour répondre")
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true).setContentIntent(pi).setOngoing(true).build()
            NotificationManagerCompat.from(ctx).notify(NOTIF, n)
        } catch (_: SecurityException) {}
    }

    companion object { const val RATE = 8000; const val CHUNK = 320; const val NOTIF = 7 }
}
