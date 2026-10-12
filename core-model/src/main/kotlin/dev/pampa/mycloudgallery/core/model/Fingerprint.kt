package dev.pampa.mycloudgallery.core.model

import java.security.MessageDigest
import java.util.Locale

private val HEX_64 = Regex("^[0-9a-f]{64}$")

private fun ByteArray.toHex(): String {
  val digits = "0123456789abcdef"
  val out = CharArray(size * 2)
  forEachIndexed { i, b ->
    val v = b.toInt() and 0xff
    out[i * 2] = digits[v ushr 4]
    out[i * 2 + 1] = digits[v and 0x0f]
  }
  return String(out)
}

/**
 * SHA-256 dell'intero contenuto di un file (l'hash completo), in esadecimale minuscolo.
 *
 * E' l'unica prova di "identico byte per byte" che il piano accetta (proposta 5, 5.3): solo due hash
 * completi uguali permettono di unire due media o di dire che una copia privata e' verificata.
 */
@JvmInline
value class Sha256(val hex: String) {

  init {
    require(HEX_64.matches(hex)) { "Un SHA-256 sono 64 cifre esadecimali minuscole." }
  }

  override fun toString(): String = hex

  companion object {
    fun of(content: ByteArray): Sha256 =
      Sha256(MessageDigest.getInstance("SHA-256").digest(content).toHex())

    fun parse(raw: String): Sha256 = Sha256(raw.trim().lowercase(Locale.ROOT))
  }
}

/**
 * L'hash rapido di un file: vedi [QuickHashAlgorithm]. Tipo distinto da [Sha256] apposta, cosi' il
 * compilatore impedisce di confrontare per sbaglio un hash rapido con uno completo.
 */
@JvmInline
value class QuickHash(val hex: String) {

  init {
    require(HEX_64.matches(hex)) { "Un hash rapido sono 64 cifre esadecimali minuscole." }
  }

  override fun toString(): String = hex

  companion object {
    fun parse(raw: String): QuickHash = QuickHash(raw.trim().lowercase(Locale.ROOT))
  }
}

/**
 * L'algoritmo dell'hash rapido, versione `qh1`. **E' un contratto tra dispositivi** (dalla Fase 4 le
 * impronte viaggiano nell'indice condiviso): cambiarlo richiede un `qh2`, non una modifica di questo.
 *
 * `SHA-256( "mcg-qh1\0" || dimensione (8 byte, big-endian) || testa || coda )`, dove:
 * - per file fino a 128 KiB la testa e' **tutto** il contenuto e la coda e' vuota;
 * - oltre, la testa sono i primi 64 KiB e la coda gli ultimi 64 KiB.
 *
 * Perche' cosi': costa due letture da 64 KiB anche su un video da 9 GB via SMB (15-20 ms l'una,
 * `docs/inventario-nas.md` §4) e prende le parti che cambiano davvero tra due foto (EXIF, miniatura,
 * fine dei dati). Il prefisso separa questo hash dall'SHA-256 dell'intero file anche per i file piccoli.
 *
 * Limite, dichiarato: due file della stessa dimensione che differiscono **solo nel mezzo** hanno lo
 * stesso hash rapido (il test `FingerprintTest` lo dimostra). Per questo l'hash rapido da solo dice
 * "probabilmente uguali" ([FingerprintMatch.PROBABLY_SAME]) e non basta mai per unire due media.
 */
object QuickHashAlgorithm {

  const val ID: String = "qh1"

  /** Quanto si legge in testa e in coda. */
  const val CHUNK_BYTES: Int = 64 * 1024

  private val DOMAIN: ByteArray = "mcg-qh1".toByteArray(Charsets.US_ASCII) + 0

  /**
   * Gli intervalli di byte da leggere per un file di [size] byte: uno solo (tutto il file) fino a
   * 128 KiB, altrimenti testa e coda. Chi legge da MediaStore o da SMB chiede solo questi.
   */
  fun regions(size: Long): List<LongRange> {
    require(size >= 0) { "Dimensione negativa." }
    return when {
      size == 0L -> emptyList()
      size <= 2L * CHUNK_BYTES -> listOf(0L until size)
      else -> listOf(0L until CHUNK_BYTES.toLong(), (size - CHUNK_BYTES) until size)
    }
  }

  /** Calcola l'hash dai byte delle [regions]: [tail] e' vuota per i file fino a 128 KiB. */
  fun compute(size: Long, head: ByteArray, tail: ByteArray): QuickHash {
    val regions = regions(size)
    if (regions.size <= 1) {
      require(head.size.toLong() == size && tail.isEmpty()) {
        "Per un file fino a 128 KiB la testa e' tutto il contenuto e la coda e' vuota."
      }
    } else {
      require(head.size == CHUNK_BYTES && tail.size == CHUNK_BYTES) {
        "Testa e coda devono essere di $CHUNK_BYTES byte."
      }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(DOMAIN)
    digest.update(ByteArray(8) { i -> (size ushr (56 - 8 * i)).toByte() })
    digest.update(head)
    digest.update(tail)
    return QuickHash(digest.digest().toHex())
  }

  /** Comodita' per contenuti gia' in memoria (test, file piccoli). */
  fun ofContent(content: ByteArray): QuickHash {
    val size = content.size.toLong()
    return if (size <= 2L * CHUNK_BYTES) {
      compute(size, content, ByteArray(0))
    } else {
      compute(size, content.copyOfRange(0, CHUNK_BYTES), content.copyOfRange(content.size - CHUNK_BYTES, content.size))
    }
  }
}

/** Esito del confronto tra due impronte. */
enum class FingerprintMatch {
  /** Hash completi uguali: gli stessi byte. Basta per collegare e per unire. */
  SAME,

  /** Stessa dimensione e stesso hash rapido, ma almeno un hash completo manca: serve una prova in piu'. */
  PROBABLY_SAME,

  /** Dimensione, hash rapido o hash completo diversi: certamente byte diversi. */
  DIFFERENT,
}

/**
 * L'impronta dei byte di una versione: dimensione, hash rapido (sempre) e hash completo (quando
 * qualcuno l'ha calcolato: chi carica lo calcola sempre, piano 5.2).
 *
 * Le impronte multiple esistono per il costo (piano 1.3): la dimensione e' gratis, l'hash rapido
 * costa due letture, quello completo rilegge tutto il file. Si sale di livello solo quando il livello
 * sotto non basta a decidere.
 */
data class Fingerprint(
  val size: Long,
  val quickHash: QuickHash,
  val fullHash: Sha256? = null,
) {

  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  /** Confronto conservativo: l'hash completo, quando c'e' da entrambe le parti, decide da solo. */
  fun match(other: Fingerprint): FingerprintMatch = when {
    size != other.size -> FingerprintMatch.DIFFERENT
    fullHash != null && other.fullHash != null ->
      if (fullHash == other.fullHash) FingerprintMatch.SAME else FingerprintMatch.DIFFERENT
    quickHash != other.quickHash -> FingerprintMatch.DIFFERENT
    else -> FingerprintMatch.PROBABLY_SAME
  }

  /**
   * Unisce cio' che si sa di due impronte compatibili: se l'altra conosce l'hash completo e questa no,
   * lo prende. Rifiuta impronte diverse (sarebbe un errore di chi chiama).
   */
  fun withKnowledgeOf(other: Fingerprint): Fingerprint {
    require(match(other) != FingerprintMatch.DIFFERENT) { "Impronte di byte diversi." }
    return if (fullHash == null && other.fullHash != null) copy(fullHash = other.fullHash) else this
  }

  companion object {
    /** L'impronta completa di un contenuto in memoria (test, file piccoli). */
    fun ofContent(content: ByteArray): Fingerprint =
      Fingerprint(content.size.toLong(), QuickHashAlgorithm.ofContent(content), Sha256.of(content))
  }
}
