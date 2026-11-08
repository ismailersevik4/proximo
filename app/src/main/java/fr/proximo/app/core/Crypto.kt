package fr.proximo.app.core

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement de Proximo.
 *  - Identité : Ed25519 (signature) + X25519 (échange de clés).
 *  - Message privé : clé éphémère X25519 -> secret partagé -> HKDF -> AES-256-GCM.
 *  - Groupe : clé de groupe AES-256 partagée, AES-GCM.
 * Tout est fait avec Bouncy Castle (API légère) et javax.crypto.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    // ---- Ed25519 ----
    fun newSigningKey(): ByteArray = Ed25519PrivateKeyParameters(rng).encoded
    fun signingPublic(priv: ByteArray): ByteArray = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
    fun sign(priv: ByteArray, data: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(priv, 0))
        s.update(data, 0, data.size)
        return s.generateSignature()
    }
    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(pub, 0))
        s.update(data, 0, data.size)
        s.verifySignature(sig)
    } catch (e: Exception) { false }

    // ---- X25519 ----
    fun newDhKey(): ByteArray = X25519PrivateKeyParameters(rng).encoded
    fun dhPublic(priv: ByteArray): ByteArray = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
    fun agree(priv: ByteArray, peerPub: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(X25519PrivateKeyParameters(priv, 0))
        val out = ByteArray(a.agreementSize)
        a.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), out, 0)
        return out
    }
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(len)
        g.generateBytes(out, 0, len)
        return out
    }

    // ---- AES-GCM ----
    fun aesEncrypt(key: ByteArray, plain: ByteArray, aad: ByteArray?): ByteArray {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }
    fun aesDecrypt(key: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        if (aad != null) c.updateAAD(aad)
        c.doFinal(data, 12, data.size - 12)
    } catch (e: Exception) { null }

    /**
     * Enveloppe privée : [ephPub 32][aesgcm(iv+ct)]
     * La clé AES est dérivée du secret X25519(eph, destinataire) avec HKDF, info = "proximo-dm".
     */
    fun sealFor(peerDhPub: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val eph = newDhKey()
        val ephPub = dhPublic(eph)
        val shared = agree(eph, peerDhPub)
        val key = hkdf(shared, ephPub + peerDhPub, "proximo-dm".toByteArray())
        return ephPub + aesEncrypt(key, plain, aad)
    }
    fun openWith(myDhPriv: ByteArray, myDhPub: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 32 + 12 + 16) return null
        val ephPub = sealed.copyOfRange(0, 32)
        val shared = agree(myDhPriv, ephPub)
        val key = hkdf(shared, ephPub + myDhPub, "proximo-dm".toByteArray())
        return aesDecrypt(key, sealed.copyOfRange(32, sealed.size), aad)
    }
}
