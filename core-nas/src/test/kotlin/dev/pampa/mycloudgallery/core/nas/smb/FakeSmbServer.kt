package dev.pampa.mycloudgallery.core.nas.smb

import com.hierynomus.ntlm.functions.NtlmFunctions
import com.hierynomus.ntlm.functions.NtlmV2Functions
import com.hierynomus.smbj.SmbConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Un **impostore SMB 3.1.1** su `127.0.0.1` per la domanda aperta 1 della revisione 2 (`docs/review/2-fase2.md`): un NAS
 * finto che risponde alla NEGOTIATE come quello vero (3.1.1, SHA-512, AES-128-GCM, GUID scelto), conduce un login
 * NTLMv2 e poi "completa" la sessione con `STATUS_SUCCESS`.
 *
 * Come chiude la sessione lo decide [finalSignature]:
 * - [FinalSignature.NONE]: risposta finale senza firma (l'impostore che ha solo raccolto la risposta NTLMv2);
 * - [FinalSignature.GARBAGE]: flag "firmata" ma firma inventata;
 * - [FinalSignature.VALID]: firma giusta, calcolata conoscendo la password (cioe' il NAS vero, o chi ha rotto la
 *   password): serve a provare la verifica in positivo e il rifiuto delle risposte in chiaro **dopo** il login.
 *
 * Dopo il login risponde a ogni richiesta (cifrata, quindi illeggibile per lui) **in chiaro**, indovinando il
 * `MessageId` (crescono di uno): una `TREE_CONNECT` riuscita su una share di dischi.
 *
 * Solo per i test JVM: niente cifratura lato server, una connessione alla volta.
 */
internal class FakeSmbServer(
  private val finalSignature: FinalSignature,
  /** Il flag di sessione `ENCRYPT_DATA` nella risposta finale (smbj smette allora di pretendere la firma). */
  private val encryptFlag: Boolean = true,
  private val user: String = "test",
  private val password: String = "password-di-prova",
  val guid: UUID = UUID.fromString("6c63796d-0000-4000-8000-000000000001"),
) : AutoCloseable {

  enum class FinalSignature { NONE, GARBAGE, VALID }

  private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
  val port: Int get() = server.localPort

  /** I comandi in chiaro ricevuti (codici SMB2) e le richieste cifrate (illeggibili) ricevute dopo il login. */
  val plainCommands: MutableList<Int> = CopyOnWriteArrayList()
  val encryptedRequests = AtomicInteger(0)
  val connections = AtomicInteger(0)

  /** La risposta finale e' stata mandata. */
  @Volatile var sessionCompleted = false
    private set

  private val provider = SmbConfig.createDefaultConfig().securityProvider
  private val sessionId = 0x0000_4400_0000_0011L

  init {
    thread(isDaemon = true, name = "fake-smb") {
      while (!server.isClosed) {
        val socket = try {
          server.accept()
        } catch (_: IOException) {
          break
        }
        connections.incrementAndGet()
        try {
          socket.use { serve(it) }
        } catch (_: IOException) {
          // il client ha chiuso: e' cio' che ci si aspetta quando rifiuta la sessione
        }
      }
    }
  }

  override fun close() {
    server.close()
  }

  // --- Una connessione ----------------------------------------------------------------------------------------

  private fun serve(socket: Socket) {
    val input = DataInputStream(socket.getInputStream())
    val output = socket.getOutputStream()
    var preauth = ByteArray(PREAUTH_BYTES)
    var nextMessageId = 0L
    var challenge = ByteArray(0)
    while (true) {
      val frame = readFrame(input) ?: return
      if (frame.size >= 4 && frame[0] == 0xFD.toByte()) {
        // Una richiesta cifrata: l'impostore non la sa leggere. Indovina il MessageId e risponde in chiaro.
        encryptedRequests.incrementAndGet()
        val reply = treeConnectResponse(nextMessageId++)
        writeFrame(output, reply)
        continue
      }
      val command = le16(frame, 12)
      val messageId = le64(frame, 24)
      nextMessageId = messageId + 1
      plainCommands += command
      when (command) {
        CMD_NEGOTIATE -> {
          val response = negotiateResponse(messageId)
          preauth = sha512(sha512(preauth, frame), response)
          writeFrame(output, response)
        }
        CMD_SESSION_SETUP -> {
          val token = securityBuffer(frame)
          if (!sessionCompletedHalf) {
            challenge = Random.nextBytes(8)
            val response = sessionSetupMoreProcessing(messageId, challenge)
            sessionPreauth = sha512(sha512(preauth, frame), response)
            sessionCompletedHalf = true
            writeFrame(output, response)
          } else {
            val finalPreauth = sha512(sessionPreauth, frame)
            val response = sessionSetupSuccess(messageId)
            if (finalSignature != FinalSignature.NONE) sign(response, token, challenge, finalPreauth)
            sessionCompleted = true
            writeFrame(output, response)
          }
        }
        else -> writeFrame(output, errorResponse(command, messageId, STATUS_NOT_SUPPORTED))
      }
    }
  }

  @Volatile private var sessionCompletedHalf = false
  private var sessionPreauth = ByteArray(PREAUTH_BYTES)

  // --- Risposte -----------------------------------------------------------------------------------------------

  private fun header(command: Int, messageId: Long, status: Long, session: Long, treeId: Int = 0): ByteBuffer =
    ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
      put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
      putShort(HEADER_BYTES.toShort())
      putShort(1) // credit charge
      putInt(status.toInt())
      putShort(command.toShort())
      putShort(CREDITS.toShort())
      putInt(FLAG_SERVER_TO_REDIR)
      putInt(0) // next command
      putLong(messageId)
      putInt(0) // process id
      putInt(treeId)
      putLong(session)
      put(ByteArray(SIGNATURE_BYTES))
    }

  private fun negotiateResponse(messageId: Long): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header(CMD_NEGOTIATE, messageId, 0, 0).array())
    val body = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(65) // structure size
      putShort(1) // security mode: firma abilitata
      putShort(0x0311) // dialetto
      putShort(2) // contesti
      putLong(0) // GUID del server: 16 byte, copiati sotto
      putLong(0)
      putInt(0) // capabilities
      putInt(MAX_SIZE)
      putInt(MAX_SIZE)
      putInt(MAX_SIZE)
      putLong(fileTimeNow())
      putLong(0)
      putShort(128) // security buffer offset
      putShort(0) // security buffer length: vuoto (smbj sceglie NTLM)
      putInt(128) // negotiate context offset
    }.array()
    guidBytes().copyInto(body, 8)
    out.write(body)
    // Contesto 1: preautenticazione SHA-512 con sale (46 byte, poi allineamento a 8).
    val preauth = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(1)
      putShort(38)
      putInt(0)
      putShort(1)
      putShort(32)
      putShort(1)
      put(Random.nextBytes(32))
    }.array()
    out.write(preauth)
    // Contesto 2: cifratura AES-128-GCM.
    val encryption = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(2)
      putShort(4)
      putInt(0)
      putShort(1)
      putShort(2)
    }.array()
    out.write(encryption)
    return out.toByteArray()
  }

  private fun guidBytes(): ByteArray {
    // GUID di MS-DTYP: i primi tre campi in little-endian, il resto in ordine.
    val msb = guid.mostSignificantBits
    val lsb = guid.leastSignificantBits
    val b = ByteBuffer.allocate(16)
    b.order(ByteOrder.LITTLE_ENDIAN).putInt((msb ushr 32).toInt()).putShort((msb ushr 16).toShort()).putShort(msb.toShort())
    b.order(ByteOrder.BIG_ENDIAN).putLong(lsb)
    return b.array()
  }

  private fun sessionSetupMoreProcessing(messageId: Long, challenge: ByteArray): ByteArray {
    val token = negTokenResp(ntlmChallenge(challenge), accepted = false)
    return sessionSetupResponse(messageId, STATUS_MORE_PROCESSING_REQUIRED, flags = 0, token)
  }

  private fun sessionSetupSuccess(messageId: Long): ByteArray =
    sessionSetupResponse(messageId, 0, flags = if (encryptFlag) SESSION_FLAG_ENCRYPT_DATA else 0, negTokenResp(null, accepted = true))

  private fun sessionSetupResponse(messageId: Long, status: Long, flags: Int, token: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header(CMD_SESSION_SETUP, messageId, status, sessionId).array())
    out.write(
      ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(9).putShort(flags.toShort()).putShort((HEADER_BYTES + 8).toShort()).putShort(token.size.toShort()).array(),
    )
    out.write(token)
    return out.toByteArray()
  }

  private fun treeConnectResponse(messageId: Long): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header(CMD_TREE_CONNECT, messageId, 0, sessionId, treeId = 1).array())
    out.write(
      ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(16).put(1).put(0).putInt(0).putInt(0).putInt(0x001F01FF).array(),
    )
    return out.toByteArray()
  }

  private fun errorResponse(command: Int, messageId: Long, status: Long): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header(command, messageId, status, sessionId).array())
    out.write(ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putShort(9).array())
    return out.toByteArray()
  }

  // --- NTLM e SPNEGO ------------------------------------------------------------------------------------------

  private fun ntlmChallenge(serverChallenge: ByteArray): ByteArray {
    val target = "NAS".toByteArray(Charsets.UTF_16LE)
    val info = ByteArrayOutputStream().apply {
      avPair(1, "NAS".toByteArray(Charsets.UTF_16LE))
      avPair(2, "WORKGROUP".toByteArray(Charsets.UTF_16LE))
      avPair(7, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(fileTimeNow()).array())
      avPair(0, ByteArray(0))
    }.toByteArray()
    val fixed = 56
    val flags = 0x00000001 or 0x00000004 or 0x00000010 or 0x00000200 or 0x00008000 or 0x00020000 or 0x00080000 or
      0x00800000 or 0x02000000 or 0x20000000 or 0x40000000 or 0x80000000.toInt()
    return ByteBuffer.allocate(fixed + target.size + info.size).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("NTLMSSP\u0000".toByteArray(Charsets.US_ASCII))
      putInt(2)
      putShort(target.size.toShort()).putShort(target.size.toShort()).putInt(fixed)
      putInt(flags)
      put(serverChallenge)
      putLong(0)
      putShort(info.size.toShort()).putShort(info.size.toShort()).putInt(fixed + target.size)
      put(byteArrayOf(6, 1, 0, 0, 0, 0, 0, 15)) // versione
      put(target)
      put(info)
    }.array()
  }

  private fun ByteArrayOutputStream.avPair(id: Int, value: ByteArray) {
    write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(id.toShort()).putShort(value.size.toShort()).array())
    write(value)
  }

  /** `NegTokenResp` di SPNEGO (DER): stato e, se c'e', il token NTLM. */
  private fun negTokenResp(ntlm: ByteArray?, accepted: Boolean): ByteArray {
    val state = der(0xA0, der(0x0A, byteArrayOf(if (accepted) 0 else 1)))
    val mech = der(0xA1, der(0x06, byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0A)))
    val token = ntlm?.let { der(0xA2, der(0x04, it)) } ?: ByteArray(0)
    return der(0xA1, der(0x30, state + mech + token))
  }

  private fun der(tag: Int, content: ByteArray): ByteArray {
    val length = when {
      content.size < 0x80 -> byteArrayOf(content.size.toByte())
      content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
      else -> byteArrayOf(0x82.toByte(), (content.size ushr 8).toByte(), content.size.toByte())
    }
    return byteArrayOf(tag.toByte()) + length + content
  }

  // --- La firma giusta (solo per FinalSignature.VALID / GARBAGE) ----------------------------------------------

  private fun sign(response: ByteArray, authenticateToken: ByteArray, serverChallenge: ByteArray, preauthHash: ByteArray) {
    val flags = le32(response, 16) or FLAG_SIGNED
    ByteBuffer.wrap(response, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(flags)
    val signature = if (finalSignature == FinalSignature.GARBAGE) {
      Random.nextBytes(SIGNATURE_BYTES)
    } else {
      val signingKey = kdf(sessionKey(authenticateToken), "SMBSigningKey\u0000".toByteArray(Charsets.US_ASCII), preauthHash)
      aesCmac(signingKey, response)
    }
    signature.copyInto(response, 48)
  }

  /** La chiave di sessione NTLMv2 come la calcola il server che conosce la password (MS-NLMP 3.3.2). */
  private fun sessionKey(token: ByteArray): ByteArray {
    val start = indexOf(token, "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII))
    val message = token.copyOfRange(start, token.size)
    fun field(at: Int): ByteArray {
      val len = le16(message, at)
      val off = le32(message, at + 4)
      return message.copyOfRange(off, off + len)
    }
    val ntResponse = field(20)
    val encryptedKey = field(52)
    val flags = le32(message, 60)
    val ntowf = NtlmV2Functions(java.util.Random(), provider).NTOWFv2(password, user, "")
    val sessionBaseKey = NtlmFunctions.hmac_md5(provider, ntowf, ntResponse.copyOf(16))
    return if (flags and 0x40000000 != 0 && encryptedKey.isNotEmpty()) NtlmFunctions.rc4k(provider, sessionBaseKey, encryptedKey) else sessionBaseKey
  }

  /** SP800-108 in modalita' contatore con HMAC-SHA256 (MS-SMB2 3.1.4.2), 128 bit. */
  private fun kdf(key: ByteArray, label: ByteArray, context: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(byteArrayOf(0, 0, 0, 1))
    mac.update(label)
    mac.update(0)
    mac.update(context)
    mac.update(byteArrayOf(0, 0, 0, 0x80.toByte()))
    return mac.doFinal().copyOf(16)
  }

  /** AES-CMAC (RFC 4493), scritto a parte: la verifica del client non si prova con il suo stesso codice. */
  private fun aesCmac(key: ByteArray, message: ByteArray): ByteArray {
    val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
    fun shift(b: ByteArray): ByteArray {
      val out = ByteArray(16)
      var carry = 0
      for (i in 15 downTo 0) {
        val v = b[i].toInt() and 0xff
        out[i] = ((v shl 1) or carry).toByte()
        carry = v ushr 7
      }
      if (b[0].toInt() and 0x80 != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
      return out
    }
    val k1 = shift(aes.doFinal(ByteArray(16)))
    val k2 = shift(k1)
    val blocks = maxOf(1, (message.size + 15) / 16)
    val complete = message.isNotEmpty() && message.size % 16 == 0
    val last = ByteArray(16)
    val lastStart = (blocks - 1) * 16
    if (complete) {
      for (i in 0 until 16) last[i] = (message[lastStart + i].toInt() xor k1[i].toInt()).toByte()
    } else {
      val rest = message.size - lastStart
      for (i in 0 until 16) {
        val m = if (i < rest) message[lastStart + i].toInt() else if (i == rest) 0x80 else 0
        last[i] = (m xor k2[i].toInt()).toByte()
      }
    }
    var x = ByteArray(16)
    for (block in 0 until blocks - 1) {
      val y = ByteArray(16) { (x[it].toInt() xor message[block * 16 + it].toInt()).toByte() }
      x = aes.doFinal(y)
    }
    return aes.doFinal(ByteArray(16) { (x[it].toInt() xor last[it].toInt()).toByte() })
  }

  // --- Utilita' -----------------------------------------------------------------------------------------------

  private fun securityBuffer(frame: ByteArray): ByteArray {
    val offset = le16(frame, HEADER_BYTES + 12)
    val length = le16(frame, HEADER_BYTES + 14)
    return frame.copyOfRange(offset, offset + length)
  }

  private fun readFrame(input: DataInputStream): ByteArray? {
    val head = try {
      input.readInt()
    } catch (_: IOException) {
      return null
    }
    val buffer = ByteArray(head and 0x00FFFFFF)
    input.readFully(buffer)
    return buffer
  }

  private fun writeFrame(output: java.io.OutputStream, message: ByteArray) {
    output.write(ByteBuffer.allocate(4).putInt(message.size).array())
    output.write(message)
    output.flush()
  }

  private fun sha512(previous: ByteArray, message: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").apply {
      update(previous)
      update(message)
    }.digest()

  private fun fileTimeNow(): Long = (System.currentTimeMillis() + 11_644_473_600_000L) * 10_000L

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    error("token NTLM non trovato")
  }

  private companion object {
    const val HEADER_BYTES = 64
    const val SIGNATURE_BYTES = 16
    const val PREAUTH_BYTES = 64
    const val CREDITS = 64
    const val MAX_SIZE = 8 * 1024 * 1024
    const val CMD_NEGOTIATE = 0
    const val CMD_SESSION_SETUP = 1
    const val CMD_TREE_CONNECT = 3
    const val FLAG_SERVER_TO_REDIR = 0x1
    const val FLAG_SIGNED = 0x8
    const val SESSION_FLAG_ENCRYPT_DATA = 0x4
    const val STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016L
    const val STATUS_NOT_SUPPORTED = 0xC00000BBL

    fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    fun le32(b: ByteArray, at: Int): Int = le16(b, at) or (le16(b, at + 2) shl 16)

    fun le64(b: ByteArray, at: Int): Long = (le32(b, at).toLong() and 0xffffffffL) or (le32(b, at + 4).toLong() shl 32)
  }
}
