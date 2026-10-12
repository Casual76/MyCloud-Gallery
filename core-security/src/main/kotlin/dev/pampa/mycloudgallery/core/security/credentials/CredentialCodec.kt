package dev.pampa.mycloudgallery.core.security.credentials

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** I byte decifrati non descrivono delle credenziali valide. */
internal class CredentialFormatException(message: String) : Exception(message)

/**
 * Il testo in chiaro che finisce dentro il blocco cifrato:
 *
 * ```
 * [versione: 1][lunghezza utente: 2][utente UTF-8][lunghezza password: 2][password UTF-8]
 * ```
 *
 * Niente JSON e niente `String` per la password: si lavora su array che si possono azzerare.
 * Lettura rigida: lunghezze fuori limite, byte in piu' o UTF-8 non valido sono errori. I messaggi
 * d'errore non contengono mai i valori.
 */
internal object CredentialCodec {

  const val VERSION: Int = 1
  const val MAX_USERNAME_BYTES = 255
  const val MAX_PASSWORD_BYTES = 1024

  fun requireValid(username: String, password: CharArray) {
    require(username.isNotEmpty()) { "utente vuoto" }
    require(password.isNotEmpty()) { "password vuota" }
    require(username.toByteArray(StandardCharsets.UTF_8).size <= MAX_USERNAME_BYTES) { "utente troppo lungo" }
    val passwordBytes = encodeChars(password)
    try {
      require(passwordBytes.size <= MAX_PASSWORD_BYTES) { "password troppo lunga" }
    } finally {
      passwordBytes.fill(0)
    }
  }

  fun encode(credentials: NasCredentials): ByteArray {
    val user = credentials.username.toByteArray(StandardCharsets.UTF_8)
    val pass = encodeChars(credentials.password.value)
    try {
      val out = ByteArray(1 + 2 + user.size + 2 + pass.size)
      val buffer = ByteBuffer.wrap(out)
      buffer.put(VERSION.toByte())
      buffer.putShort(user.size.toShort())
      buffer.put(user)
      buffer.putShort(pass.size.toShort())
      buffer.put(pass)
      return out
    } finally {
      user.fill(0)
      pass.fill(0)
    }
  }

  fun decode(bytes: ByteArray): NasCredentials {
    val user: ByteArray
    val pass: ByteArray
    try {
      val buffer = ByteBuffer.wrap(bytes)
      if (buffer.remaining() < 1 || buffer.get().toInt() != VERSION) {
        throw CredentialFormatException("versione sconosciuta")
      }
      user = ByteArray(readLength(buffer, MAX_USERNAME_BYTES)).also { buffer.get(it) }
      pass = ByteArray(readLength(buffer, MAX_PASSWORD_BYTES)).also { buffer.get(it) }
      if (buffer.hasRemaining()) throw CredentialFormatException("byte in piu'")
    } catch (e: BufferUnderflowException) {
      throw CredentialFormatException("dati troncati")
    }
    var userChars: CharArray? = null
    var passChars: CharArray? = null
    try {
      userChars = decodeStrict(user)
      passChars = decodeStrict(pass)
      return try {
        NasCredentials(String(userChars), passChars)
      } catch (e: IllegalArgumentException) {
        throw CredentialFormatException("credenziali non valide")
      }
    } finally {
      user.fill(0)
      pass.fill(0)
      userChars?.fill('\u0000')
      passChars?.fill('\u0000')
    }
  }

  private fun readLength(buffer: ByteBuffer, max: Int): Int {
    if (buffer.remaining() < 2) throw CredentialFormatException("dati troncati")
    val length = buffer.short.toInt() and 0xFFFF
    if (length == 0 || length > max || length > buffer.remaining()) {
      throw CredentialFormatException("lunghezza non valida")
    }
    return length
  }

  private fun encodeChars(chars: CharArray): ByteArray {
    val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
    val out = ByteArray(encoded.remaining())
    encoded.get(out)
    // Il buffer interno contiene la password in chiaro: lo azzeriamo.
    if (encoded.hasArray()) encoded.array().fill(0)
    return out
  }

  /** UTF-8 -> char, rifiutando sequenze non valide. Il chiamante azzera l'array restituito. */
  private fun decodeStrict(bytes: ByteArray): CharArray {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val chars = CharBuffer.allocate(bytes.size)
    val result = decoder.decode(ByteBuffer.wrap(bytes), chars, true)
    if (result.isError || result.isOverflow) {
      chars.array().fill('\u0000')
      throw CredentialFormatException("UTF-8 non valido")
    }
    chars.flip()
    val out = CharArray(chars.remaining())
    chars.get(out)
    chars.array().fill('\u0000')
    return out
  }
}
