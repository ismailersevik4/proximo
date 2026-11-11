package fr.proximo.app.mesh

import android.content.Context
import fr.proximo.app.core.Crypto
import fr.proximo.app.core.Hex
import fr.proximo.app.core.Identity

/**
 * Preuve d'identité à l'ouverture d'une liaison.
 *
 * Avant, un téléphone annonçait simplement « je suis Untel » et on le croyait. Maintenant, chaque côté envoie à l'autre
 * un nombre au hasard (la « question ») ; l'autre le signe avec sa clé privée et joint sa clé publique (la « réponse »).
 * L'identifiant d'un téléphone étant l'empreinte de sa clé publique, une réponse valide prouve qu'il est bien celui qu'il dit.
 *
 *   question : AUTH | [1][nombre au hasard (16)]
 *   réponse  : AUTH | [2][clé publique Ed25519 (32)][signature (64) de « proximo-link-v1| » + nombre + mon identifiant + le sien]
 *
 * Tant que la preuve n'est pas faite, la liaison est « limitée » : seules les trames de mise à jour y passent
 * (une ancienne version de Proximo peut ainsi toujours récupérer la nouvelle).
 */
class LinkAuth(ctx: Context) {
    private val me = Identity.get(ctx)
    private val myIdBytes = Hex.dec(me.id)

    fun newNonce(): ByteArray = Crypto.random(16)

    /** Ma question pour ce voisin. */
    fun challenge(nonce: ByteArray): ByteArray =
        Frame(Frame.AUTH, 0, 0, 0, Frame.newId(), myIdBytes, Frame.BROADCAST, byteArrayOf(ASK.toByte()) + nonce).encode()

    fun isChallenge(f: Frame) = f.type == Frame.AUTH && f.payload.size == 17 && f.payload[0].toInt() == ASK
    fun isAnswer(f: Frame) = f.type == Frame.AUTH && f.payload.size == 1 + 32 + 64 && f.payload[0].toInt() == ANSWER

    /** Ma réponse à la question d'un voisin (null si la question est mal formée). */
    fun answer(question: Frame): ByteArray? {
        if (!isChallenge(question)) return null
        val nonce = question.payload.copyOfRange(1, 17)
        val sig = Crypto.sign(me.sigPriv, signed(nonce, myIdBytes, question.from))
        return Frame(Frame.AUTH, 0, 0, 0, Frame.newId(), myIdBytes, Frame.BROADCAST, byteArrayOf(ANSWER.toByte()) + me.sigPub + sig).encode()
    }

    /** Vérifie la réponse d'un voisin à MA question : rend son identifiant prouvé, ou null si la preuve est fausse. */
    fun verify(answer: Frame, myNonce: ByteArray?): String? {
        if (myNonce == null || !isAnswer(answer)) return null
        val pub = answer.payload.copyOfRange(1, 33)
        val sig = answer.payload.copyOfRange(33, 97)
        val id = Identity.idOf(pub)
        if (id != answer.fromHex || id == me.id) return null
        return if (Crypto.verify(pub, signed(myNonce, answer.from, myIdBytes), sig)) id else null
    }

    private fun signed(nonce: ByteArray, responder: ByteArray, challenger: ByteArray): ByteArray = PREFIX + nonce + responder + challenger

    companion object {
        private const val ASK = 1
        private const val ANSWER = 2
        private val PREFIX = "proximo-link-v1|".toByteArray()
    }
}
