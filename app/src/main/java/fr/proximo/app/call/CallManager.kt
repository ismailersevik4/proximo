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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.proximo.app.MainActivity
import fr.proximo.app.R
import fr.proximo.app.core.B64
import fr.proximo.app.core.Crypto
import fr.proximo.app.core.Db
import fr.proximo.app.core.Hex
import fr.proximo.app.core.Identity
import fr.proximo.app.mesh.Frame
import fr.proximo.app.mesh.MeshCore
import fr.proximo.app.mesh.MeshService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CallState(
    val phase: Int, val peerId: String, val peerName: String, val since: Long,
    val speaker: Boolean = true, val muted: Boolean = false,
    /** Voisin qui relaie l'appel (null = direct). */
    val via: String? = null, val viaName: String? = null,
) {
    companion object { const val IDLE = 0; const val RINGING_IN = 1; const val RINGING_OUT = 2; const val ACTIVE = 3; const val SEARCHING = 4 }
}

/**
 * Appels vocaux (bêta) : son PCM 8 kHz, chiffré paquet par paquet (AES-GCM, clé de session X25519),
 * envoyé par Wi-Fi direct, en direct ou via UN voisin relais (2 sauts).
 */
@SuppressLint("MissingPermission")
class CallManager(private val ctx: Context, private val core: MeshCore) {
    private val tag = "ProximoAppel"
    private val db = Db.get(ctx)
    private val me = Identity.get(ctx)
    val state = MutableStateFlow(CallState(CallState.IDLE, "", "", 0))
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val playQueue = LinkedBlockingQueue<ByteArray>()

    // Chiffrement de la session
    private var key: ByteArray? = null
    private var salt = ByteArray(7)        // aléa de l'appelant (dans l'IV)
    private var mySalt = ByteArray(0)      // mon aléa pour la clé
    private var peerSalt = ByteArray(0)    // l'aléa de l'autre pour la clé
    private var lastPacketAt = 0L
    private var sendDir: Byte = 1
    private var seq = 0
    private var lastRecvSeq = -1

    /** Un appel est possible en direct (prise Wi-Fi) ou peut-être via un voisin (il faut au moins une prise Wi-Fi). */
    fun canCall(peerId: String): Boolean = contactKey(peerId) != null && (core.hasWifiLink(peerId) || core.wifiLinkedPeers().isNotEmpty())

    private fun contactKey(peerId: String): ByteArray? = db.contact(peerId)?.dhPub?.takeIf { it.size == 32 } ?: MeshService.instance?.messenger?.cards?.get(peerId)?.dhPub
    private fun contactSigKey(peerId: String): ByteArray? = db.contact(peerId)?.sigPub?.takeIf { it.size == 32 } ?: MeshService.instance?.messenger?.cards?.get(peerId)?.sigPub

    // ---- signalisation signée ----
    /** Identifiant de l'appel en cours : l'aléa de l'appelant (le mien si j'appelle, le sien si on m'appelle). */
    private fun callId(): ByteArray = if (sendDir == 1.toByte()) mySalt else peerSalt
    /** Invitations déjà vues : une invitation rejouée ne fait pas sonner une deuxième fois. */
    private val seenInvites = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun callSigned(c: String, fromId: String, toId: String, cid: String, ks: String, salt: String) = "proximo-call-v1|$c|$fromId|$toId|$cid|$ks|$salt".toByteArray()
    /** Envoie un message d'appel signé : il ne peut être ni fabriqué ni rejoué par un autre téléphone. */
    private fun sendCall(peerId: String, j: JSONObject, cid: ByteArray, via: String?) {
        val cidS = B64.enc(cid)
        j.put("cid", cidS)
        j.put("sg", B64.enc(Crypto.sign(me.sigPriv, callSigned(j.optString("c"), me.id, peerId, cidS, j.optString("ks"), j.optString("salt")))))
        sendJson(peerId, j, via)
    }
    /**
     * Le message d'appel vient-il vraiment de `from` ? Signé : la signature doit être juste. Non signé (version 1.5.0) :
     * accepté seulement s'il arrive en direct du voisin prouvé lui-même, jamais par un relais.
     */
    private fun callAuthentic(j: JSONObject, from: String, fromPeer: String): Boolean {
        val sg = j.optString("sg")
        if (sg.isEmpty()) return fromPeer == from
        val key = contactSigKey(from) ?: return false
        return try { Crypto.verify(key, callSigned(j.optString("c"), from, me.id, j.optString("cid"), j.optString("ks"), j.optString("salt")), B64.dec(sg)) } catch (e: Exception) { false }
    }

    private fun micOk() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun call(peerId: String) {
        if (state.value.phase != CallState.IDLE) return
        if (!micOk()) { MeshService.instance?.messenger?.event("Micro refusé : autorise-le dans les réglages Android de Proximo pour appeler"); return }
        val name = db.contact(peerId)?.name ?: core.peer(peerId)?.name ?: "Contact"
        if (contactKey(peerId) == null) { MeshService.instance?.messenger?.event("Ajoute d'abord $name en contact pour l'appeler"); return }
        if (db.contact(peerId)?.blocked == true) { MeshService.instance?.messenger?.event("$name est bloqué : débloque-le pour l'appeler"); return }
        if (db.contact(peerId)?.accepted != true) { MeshService.instance?.messenger?.event("L'appel marchera quand $name sera dans tes contacts (demande acceptée)"); return }
        if (core.hasWifiLink(peerId)) { startOutgoing(peerId, name, null); return }
        // Pas de prise directe : on demande aux voisins Wi-Fi s'ils ont une prise avec lui.
        val candidates = core.wifiLinkedPeers().filter { it != peerId }
        if (candidates.isEmpty()) { MeshService.instance?.messenger?.event("Trop loin pour un appel : utilise le talkie-walkie"); return }
        state.value = CallState(CallState.SEARCHING, peerId, name, System.currentTimeMillis())
        for (r in candidates) sendJson(r, JSONObject().put("c", "probe").put("to", peerId), null)
        handler.postDelayed({
            val s = state.value
            if (s.phase == CallState.SEARCHING && s.peerId == peerId) { state.value = CallState(CallState.IDLE, "", "", 0); MeshService.instance?.messenger?.event("Trop loin pour un appel, même via un voisin : utilise le talkie-walkie") }
        }, 4000)
    }

    private fun startOutgoing(peerId: String, name: String, via: String?) {
        handler.removeCallbacksAndMessages(null) // les minuteries d'un appel précédent ne doivent pas raccrocher celui-ci
        salt = Crypto.random(7); mySalt = Crypto.random(16); peerSalt = ByteArray(0); sendDir = 1; seq = 0; lastRecvSeq = -1
        key = null // la clé sera dérivée quand l'appelé aura renvoyé son aléa (accept)
        val viaName = via?.let { db.contact(it)?.name ?: core.peer(it)?.name }
        state.value = CallState(CallState.RINGING_OUT, peerId, name, System.currentTimeMillis(), via = via, viaName = viaName)
        val j = JSONObject().put("c", "invite").put("salt", B64.enc(salt)).put("ks", B64.enc(mySalt)).apply { if (via != null) put("via", via) }
        sendCall(peerId, j, mySalt, via)
        handler.postDelayed({ val st = state.value; if (st.phase == CallState.RINGING_OUT && st.peerId == peerId) { hangup(); MeshService.instance?.messenger?.event("Pas de réponse"); MeshService.instance?.messenger?.callNote(peerId, "📞 Appel sans réponse", false) } }, 45_000)
    }

    fun accept() {
        val s = state.value; if (s.phase != CallState.RINGING_IN) return
        if (!micOk()) { MeshService.instance?.messenger?.event("Micro refusé : autorise-le dans les réglages Android de Proximo pour répondre"); reject(); return }
        handler.removeCallbacksAndMessages(null)
        stopRing()
        mySalt = Crypto.random(16)
        key = deriveKey(s.peerId, peerSalt, mySalt)
        if (key == null) { reject(); return }
        sendCall(s.peerId, JSONObject().put("c", "accept").put("ks", B64.enc(mySalt)), peerSalt, s.via)
        startAudio(s)
    }

    fun reject() {
        val s = state.value; if (s.phase != CallState.RINGING_IN) return
        handler.removeCallbacksAndMessages(null)
        stopRing(); sendCall(s.peerId, JSONObject().put("c", "reject"), peerSalt, s.via)
        state.value = CallState(CallState.IDLE, "", "", 0)
        NotificationManagerCompat.from(ctx).cancel(NOTIF)
    }

    fun hangup() {
        val s = state.value
        if (s.phase == CallState.IDLE) return
        if (s.phase != CallState.SEARCHING) sendCall(s.peerId, JSONObject().put("c", "hangup"), callId(), s.via)
        endCall()
    }

    fun toggleSpeaker() { val s = state.value; state.value = s.copy(speaker = !s.speaker); audio.isSpeakerphoneOn = !s.speaker }
    fun toggleMute() { val s = state.value; state.value = s.copy(muted = !s.muted) }

    /** Envoie une trame d'appel au destinataire, directement ou en la confiant au relais. */
    private fun sendFrame(peerId: String, payload: ByteArray, via: String?) {
        val f = Frame(Frame.CALL, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), payload)
        core.sendTo(via ?: peerId, f.encode())
    }
    private fun sendJson(peerId: String, j: JSONObject, via: String?) = sendFrame(peerId, j.toString().toByteArray(), via)

    /** Clé de session, différente à chaque appel : X25519(ma clé, sa clé) + aléa de l'appelant + aléa de l'appelé -> HKDF. */
    private fun deriveKey(peerId: String, callerSalt: ByteArray, calleeSalt: ByteArray): ByteArray? {
        val pub = contactKey(peerId) ?: return null
        if (callerSalt.size != 16 || calleeSalt.size != 16) return null
        val ids = listOf(me.id, peerId).sorted().joinToString("|")
        return Crypto.hkdf(Crypto.agree(me.dhPriv, pub), callerSalt + calleeSalt, ("proximo-call|" + ids).toByteArray())
    }

    /** Paquet son : [1][dir][seq(4)][AES-GCM(iv = dir + salt(7) + seq(4), aad = seq)]. */
    private fun sealAudio(pcm: ByteArray, n: Int): ByteArray? {
        val k = key ?: return null
        val s = seq++
        val iv = ByteBuffer.allocate(12).put(sendDir).put(salt).putInt(s).array()
        val aad = ByteBuffer.allocate(4).putInt(s).array()
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            val ct = c.doFinal(pcm, 0, n)
            ByteBuffer.allocate(6 + ct.size).put(1).put(sendDir).putInt(s).put(ct).array()
        } catch (e: Exception) { null }
    }

    private fun openAudio(p: ByteArray): ByteArray? {
        val k = key ?: return null
        if (p.size < 6 + 16) return null
        val dir = p[1]
        if (dir == sendDir) return null // mon propre sens
        val s = ByteBuffer.wrap(p, 2, 4).int
        if (s <= lastRecvSeq - 50) return null // trop vieux
        val iv = ByteBuffer.allocate(12).put(dir).put(salt).putInt(s).array()
        val aad = ByteBuffer.allocate(4).putInt(s).array()
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            val out = c.doFinal(p, 6, p.size - 6)
            if (s > lastRecvSeq) lastRecvSeq = s
            out
        } catch (e: Exception) { null }
    }

    /** Rend true si la trame concernait un appel. */
    fun handle(f: Frame, fromPeer: String): Boolean {
        if (f.type != Frame.CALL) return false
        val p = f.payload
        val from = f.fromHex
        if (p.isNotEmpty() && p[0] == 1.toByte()) { // son
            val s = state.value
            if (s.phase == CallState.ACTIVE && s.peerId == from && playQueue.size < 50) openAudio(p)?.let { lastPacketAt = System.currentTimeMillis(); playQueue.offer(it) }
            return true
        }
        val j = try { JSONObject(String(p)) } catch (e: Exception) { return true }
        val c = j.optString("c")
        val s = state.value
        if (c in SIGNED) {
            if (!callAuthentic(j, from, fromPeer)) { Log.w(tag, "message d'appel « $c » non vérifiable de $from : ignoré"); return true }
            val cid = j.optString("cid")
            if (cid.isNotEmpty()) {
                // Une invitation ne sert qu'une fois ; les autres messages doivent porter l'identifiant de l'appel en cours.
                if (c == "invite") { if (cid != j.optString("ks") || !seenInvites.add(cid)) return true; if (seenInvites.size > 500) seenInvites.clear() }
                else if (s.phase == CallState.IDLE || cid != B64.enc(callId())) return true
            }
        }
        // Relais fixe : ce téléphone ne prend pas d'appel.
        if (c == "invite" && fr.proximo.app.core.Settings.get(ctx).relayMode) { sendCall(from, JSONObject().put("c", "reject"), try { B64.dec(j.optString("ks")) } catch (e: Exception) { ByteArray(0) }, j.optString("via").ifEmpty { null }); return true }
        when (c) {
            // Un voisin cherche un relais vers quelqu'un : je réponds si j'ai une prise Wi-Fi avec lui.
            "probe" -> { val to = j.optString("to"); if (core.hasWifiLink(to)) sendJson(from, JSONObject().put("c", "probe_ok").put("to", to), null) }
            "probe_ok" -> if (s.phase == CallState.SEARCHING && j.optString("to") == s.peerId) startOutgoing(s.peerId, s.peerName, from)
            "invite" -> {
                val inviteCid = try { B64.dec(j.optString("ks")) } catch (e: Exception) { ByteArray(0) }
                if (s.phase != CallState.IDLE) { sendCall(from, JSONObject().put("c", "busy"), inviteCid, j.optString("via").ifEmpty { null }); return true }
                val name = db.contact(from)?.name ?: core.peer(from)?.name ?: "Inconnu"
                val via = j.optString("via").ifEmpty { null }
                // Le relais annoncé doit être celui par qui la trame est arrivée (sinon on répond en direct si possible).
                val useVia = if (via != null && fromPeer == via) via else if (core.hasWifiLink(from)) null else fromPeer
                salt = try { B64.dec(j.optString("salt")) } catch (e: Exception) { ByteArray(0) }
                peerSalt = try { B64.dec(j.optString("ks")) } catch (e: Exception) { ByteArray(0) }
                // Invitation sans aléa valide (ancienne version ou trame abîmée) : refusée, jamais de clé prévisible.
                if (salt.size != 7 || peerSalt.size != 16 || contactKey(from) == null) { sendCall(from, JSONObject().put("c", "reject"), inviteCid, useVia); return true }
                // Seuls mes contacts peuvent faire sonner le téléphone (ou tout le monde si j'accepte les messages privés des inconnus).
                if (db.contact(from)?.blocked == true || (db.contact(from)?.accepted != true && !fr.proximo.app.core.Settings.get(ctx).allowUnknownDm)) { sendCall(from, JSONObject().put("c", "reject"), inviteCid, useVia); return true }
                sendDir = 2; seq = 0; lastRecvSeq = -1
                key = null
                val viaName = useVia?.let { db.contact(it)?.name ?: core.peer(it)?.name }
                state.value = CallState(CallState.RINGING_IN, from, name, System.currentTimeMillis(), via = useVia, viaName = viaName)
                ring(); notifyIncoming(name)
                // Personne ne décroche, ou l'appelant est parti sans pouvoir le dire : la sonnerie s'arrête au bout d'une minute.
                val since = state.value.since
                handler.postDelayed({
                    val st = state.value
                    if (st.phase == CallState.RINGING_IN && st.peerId == from && st.since == since) {
                        stopRing(); NotificationManagerCompat.from(ctx).cancel(NOTIF)
                        state.value = CallState(CallState.IDLE, "", "", 0)
                        MeshService.instance?.messenger?.event("Appel manqué de $name")
                        MeshService.instance?.messenger?.callNote(from, "📞 Appel manqué", true)
                    }
                }, 60_000)
            }
            "accept" -> if (s.phase == CallState.RINGING_OUT && s.peerId == from) {
                peerSalt = try { B64.dec(j.optString("ks")) } catch (e: Exception) { ByteArray(0) }
                key = deriveKey(from, mySalt, peerSalt)
                if (key == null) { hangup(); MeshService.instance?.messenger?.event("Appel impossible : version incompatible") } else startAudio(s)
            }
            "reject", "busy" -> if (s.peerId == from) { endCall(); MeshService.instance?.messenger?.event(if (c == "busy") "Occupé" else "Appel refusé") }
            "hangup" -> if (s.peerId == from) {
                // Il a raccroché avant que je décroche : c'est un appel manqué, noté dans la discussion.
                if (s.phase == CallState.RINGING_IN) MeshService.instance?.messenger?.callNote(from, "📞 Appel manqué", true)
                endCall()
            }
        }
        return true
    }

    private fun startAudio(s: CallState) {
        state.value = s.copy(phase = CallState.ACTIVE, since = System.currentTimeMillis())
        MeshService.instance?.setCallActive(true)
        NotificationManagerCompat.from(ctx).cancel(NOTIF)
        lastPacketAt = System.currentTimeMillis()
        watchdog()
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
            playQueue.clear()
            // Lecture : fil dédié pour ne jamais bloquer la réception des autres trames.
            Thread {
                while (running.get()) {
                    val d = playQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    try { track?.write(d, 0, d.size) } catch (_: Exception) {}
                }
            }.start()
            // Capture : micro -> chiffrement -> réseau.
            Thread {
                val buf = ByteArray(CHUNK)
                val silence = ByteArray(CHUNK)
                while (running.get()) {
                    val n = record?.read(buf, 0, CHUNK) ?: -1
                    if (n <= 0) continue
                    val st = state.value
                    val payload = sealAudio(if (st.muted) silence else buf, n) ?: continue
                    sendFrame(st.peerId, payload, st.via)
                }
            }.start()
        } catch (e: Exception) { Log.e(tag, "audio", e); hangup() }
    }

    /** Plus aucun paquet depuis 10 s : la liaison est perdue, on raccroche proprement. */
    private fun watchdog() {
        handler.postDelayed({
            if (state.value.phase != CallState.ACTIVE) return@postDelayed
            if (System.currentTimeMillis() - lastPacketAt > 10_000) { hangup(); MeshService.instance?.messenger?.event("Liaison perdue : appel terminé. Le talkie-walkie reste disponible.") }
            else watchdog()
        }, 2000)
    }

    private fun endCall() {
        handler.removeCallbacksAndMessages(null)
        val wasActive = state.value.phase == CallState.ACTIVE
        running.set(false)
        stopRing()
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        record = null; track = null; key = null
        try { audio.mode = AudioManager.MODE_NORMAL; audio.isSpeakerphoneOn = false } catch (_: Exception) {}
        NotificationManagerCompat.from(ctx).cancel(NOTIF)
        state.value = CallState(CallState.IDLE, "", "", 0)
        if (wasActive) MeshService.instance?.setCallActive(false)
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

    companion object {
        const val RATE = 8000; const val CHUNK = 320; const val NOTIF = 7
        /** Messages d'appel qui doivent être signés (ou venir en direct du voisin prouvé, pour la version 1.5.0). */
        private val SIGNED = setOf("invite", "accept", "reject", "busy", "hangup")
    }
}
