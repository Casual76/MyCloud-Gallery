package dev.pampa.mycloudgallery.core.model

import java.security.MessageDigest

// Tipi della Fase 4 (docs/fase4-progetto.md §1.2, §1.3): firme fissate da P0 perche' li usano piu' pacchetti in
// parallelo (P1 accesso al NAS, P2 schema v3, P3/P4 indice, P5 scansione, P7 Libreria). Puro Kotlin, come il resto
// del modulo.

/**
 * Il **timbro** di un file del NAS: cio' che un elenco o uno `stat` dice senza leggere un byte. Serve a tre cose:
 * - la scansione decide se rileggere (uguale all'ultimo elenco → nessuna lettura, D9);
 * - la fusione dell'indice accetta un record solo se il suo timbro coincide con l'ultimo elenco (D6, E-N12);
 * - nella 4B le operazioni controllano sullo **stesso handle** che il file sia ancora quello confermato
 *   (`NasFileOps.rename(expect = ...)`, E-D13).
 *
 * [modifiedAtMillis] e' l'ora del NAS (`LastWriteTime`), mai quella del telefono. [fileId] e' facoltativo (vedi
 * [Location.Nas.fileId]): due timbri con `fileId` noti e diversi non sono lo stesso file.
 */
data class NasStamp(
  val size: Long,
  val modifiedAtMillis: Long,
  val fileId: Long? = null,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  /** Lo stesso file visto da fuori: dimensione e data uguali e, se entrambi noti, lo stesso `fileId`. */
  fun sameAs(other: NasStamp): Boolean =
    size == other.size && modifiedAtMillis == other.modifiedAtMillis &&
      (fileId == null || other.fileId == null || fileId == other.fileId)

  /** Il timbro generico del catalogo (la generazione e' solo del MediaStore). */
  fun toFileStamp(): FileStamp = FileStamp(size, modifiedAtMillis, generation = null)
}

/**
 * La **chiave di contenuto** di un media (D4, `docs/formato-nas.md` §5.5-bis): `SHA-256("mcg-ck\0" ‖ qh1 ‖
 * dimensione)`, con `qh1` come 32 byte grezzi ([QuickHash], `QuickHashAlgorithm` versione `qh1`) e la dimensione
 * come 8 byte big-endian. E' un **contratto tra dispositivi**: le miniature, le anteprime e i fotogrammi nell'indice
 * condiviso sono indicizzati con questa chiave, cosi' una rinomina fatta dal PC cambia il record del file ma non la
 * chiave, e la preparazione non si rifa'.
 *
 * Due file diversi solo nel mezzo hanno la stessa chiave (limite di `qh1`, L32): la chiave decide **cosa mostrare**,
 * mai un'unione d'identita' ne' "al sicuro" (D8).
 */
@JvmInline
value class ContentKey(val hex: String) {

  init {
    require(HEX_64.matches(hex)) { "Una chiave di contenuto sono 64 cifre esadecimali minuscole." }
  }

  /** I 32 byte della chiave (le chiavi dei segmenti dell'indice). */
  fun bytes(): ByteArray = hexBytes(hex)

  /**
   * I primi 8 byte, big-endian, come `Long`: la forma compatta della chiave nell'istantanea `MCGT2` e in
   * [MediaRef.Nas.contentKey]. Basta per cercare un blob nella replica locale; chi legge il blob confronta la
   * chiave intera scritta nel pacco.
   */
  val short: Long
    get() {
      var v = 0L
      for (i in 0 until 8) v = (v shl 8) or hex.substring(i * 2, i * 2 + 2).toLong(16)
      return v
    }

  override fun toString(): String = "ContentKey(${hex.take(8)}…)"

  companion object {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")
    private val DOMAIN: ByteArray = "mcg-ck".toByteArray(Charsets.US_ASCII) + 0

    /** La chiave dei byte con impronta rapida [quickHash] e dimensione [size]. */
    fun of(quickHash: QuickHash, size: Long): ContentKey {
      require(size >= 0) { "Dimensione negativa." }
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(DOMAIN)
      digest.update(hexBytes(quickHash.hex))
      digest.update(ByteArray(8) { i -> (size ushr (56 - 8 * i)).toByte() })
      return ContentKey(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    /** La chiave di un'impronta (dimensione e `qh1`; l'hash completo non entra). */
    fun of(fingerprint: Fingerprint): ContentKey = of(fingerprint.quickHash, fingerprint.size)
  }
}

private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

/**
 * Perche' una share e' **congelata** (D10, C11): una scansione ha visto troppi file spariti, cambiati o diventati "non
 * media" (es. rinominati in `.locked`). Il codice e' stabile: P2 lo salva nel cursore della scansione, P5 lo decide,
 * la Libreria (P7) e la Diagnostica (P11) lo mostrano.
 */
enum class FreezeReason(override val code: String) : StableCode {
  VANISHED("vanished"),
  CHANGED("changed"),
  NOT_MEDIA("not_media"),
}
