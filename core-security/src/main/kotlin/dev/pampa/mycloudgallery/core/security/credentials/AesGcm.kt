package dev.pampa.mycloudgallery.core.security.credentials

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Il blocco cifrato non ha il formato atteso (troppo corto, versione sconosciuta): dati danneggiati. */
internal class SealedFormatException(message: String) : GeneralSecurityException(message)

/**
 * AES-256-GCM con un formato proprio e versionato:
 *
 * ```
 * [versione: 1 byte][IV: 12 byte][testo cifrato + tag di autenticazione: n + 16 byte]
 * ```
 *
 * L'IV lo sceglie il Keystore a ogni cifratura (mai riusato). Il [aad] (dati associati) lega il blocco
 * al suo posto: un blocco copiato in un'altra voce non si decifra. Nessuna eccezione contiene il
 * testo in chiaro.
 */
internal object AesGcm {

  const val VERSION: Byte = 1
  const val IV_BYTES = 12
  const val TAG_BITS = 128
  private const val TAG_BYTES = TAG_BITS / 8
  private const val HEADER_BYTES = 1 + IV_BYTES
  private const val TRANSFORMATION = "AES/GCM/NoPadding"

  fun seal(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD(aad)
    val iv = cipher.iv
    if (iv == null || iv.size != IV_BYTES) throw SealedFormatException("IV inatteso dal provider")
    val encrypted = cipher.doFinal(plaintext)
    val out = ByteArray(HEADER_BYTES + encrypted.size)
    out[0] = VERSION
    System.arraycopy(iv, 0, out, 1, IV_BYTES)
    System.arraycopy(encrypted, 0, out, HEADER_BYTES, encrypted.size)
    return out
  }

  fun open(key: SecretKey, sealed: ByteArray, aad: ByteArray): ByteArray {
    if (sealed.size < HEADER_BYTES + TAG_BYTES) throw SealedFormatException("blocco troppo corto")
    if (sealed[0] != VERSION) throw SealedFormatException("versione del blocco sconosciuta")
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
    cipher.updateAAD(aad)
    return cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
  }
}
