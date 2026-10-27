package dev.pampa.mycloudgallery.core.model

import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

// Fase 8 (`docs/fase8-progetto.md` §1.2 "core-model", §1.3, D3, D7, D12, D19, D22, D23, D27): i tipi e i codici stabili dei VOLTI.
// Firme di P0, condivise dai pacchetti che partono in parallelo: il formato firmato dei dati dei volti (P3, core-trust), i motori (P2,
// core-ai), il calcolo (P4), il raggruppamento (P5), il database dei volti (P6), l'attivazione (P7), la cancellazione (P8), le persone
// (P9), la ricerca e gli album smart (P10). Puro Kotlin, come il resto del modulo.
//
// **Dati biometrici** (L14, C20): nessun tipo di questo file mostra il suo valore in `toString()` (D37: mai nomi, id di persone o di
// volti, chiavi di contenuto, riquadri, vettori o generazione nei log). I codici sono contratti fra dispositivi: si **aggiungono**, non
// si cambiano e non si riusano (`StableCodesTest`, `Phase8TypesTest`).

/**
 * Base32 di Crockford, minuscolo e senza riempimento: **la stessa codifica** di `core-trust` (`codec/Base32`, formato §3.1), qui per i
 * nomi dei volti che `core-model` deve saper scrivere e leggere senza vedere `core-trust` (generazione, id delle persone nei file,
 * localizzatori). La decodifica e' stretta: un solo modo di scrivere ogni valore. `FacesSignaturesTest` di core-trust prova che le due
 * codifiche coincidono.
 */
internal object FaceBase32 {
  private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
  private val DECODE = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

  fun encodedLength(byteCount: Int): Int = (byteCount * 8 + 4) / 5

  fun encode(bytes: ByteArray): String {
    val out = StringBuilder(encodedLength(bytes.size))
    var buffer = 0
    var bits = 0
    for (b in bytes) {
      buffer = (buffer shl 8) or (b.toInt() and 0xff)
      bits += 8
      while (bits >= 5) {
        out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
        bits -= 5
      }
      buffer = buffer and 0xff
    }
    if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    return out.toString()
  }

  /** Esattamente [byteCount] byte da [text], o null se [text] non e' la forma canonica (lunghezza, alfabeto, bit di coda a zero). */
  fun decodeExact(text: String, byteCount: Int): ByteArray? {
    if (text.length != encodedLength(byteCount)) return null
    val out = ByteArray(byteCount)
    var buffer = 0
    var bits = 0
    var index = 0
    for (c in text) {
      val v = if (c.code < 128) DECODE[c.code] else -1
      if (v < 0) return null
      buffer = (buffer shl 5) or v
      bits += 5
      if (bits >= 8) {
        out[index++] = (buffer shr (bits - 8)).toByte()
        bits -= 8
      }
      buffer = buffer and 0xff
    }
    if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
    return out
  }
}

private val CANONICAL_PERSON_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun bytesOfHex(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

/**
 * L'identita' stabile di una **persona** (D22): 128 bit casuali, nati con la persona (dal custode, D21) o con "Dividi" (D24). Nell'app e'
 * nella forma canonica di un UUID (minuscolo, con i trattini: un nome da 128 bit, non un UUID "di versione"); nei file dei volti in
 * base32 (26 caratteri, [base32]). **Unire non cambia mai un id**: la persona che sparisce diventa un alias dell'altra (D22).
 *
 * L'ordine ([compareTo]) e' quello dei 16 byte (la forma canonica minuscola si ordina come i byte): serve agli spareggi deterministici
 * del raggruppamento e alla radice di un ciclo di alias ("l'id minore del ciclo", §2.5).
 */
@JvmInline
value class PersonId(val value: String) : Comparable<PersonId> {

  init {
    // Il messaggio non contiene il valore: un id malformato puo' venire da dati ostili.
    require(CANONICAL_PERSON_UUID.matches(value)) { "L'id di una persona non e' nella forma canonica." }
  }

  /** I 16 byte dell'id. */
  fun bytes(): ByteArray = bytesOfHex(value.replace("-", ""))

  /** La forma nei file dei volti: 26 caratteri base32 (formato §2.4). */
  fun base32(): String = FaceBase32.encode(bytes())

  override fun compareTo(other: PersonId): Int = value.compareTo(other.value)

  /** D37: mai l'id di una persona nei log. */
  override fun toString(): String = "PersonId"

  companion object {
    const val BYTES: Int = 16

    /** Un id nuovo (casuale, 128 bit). */
    fun random(): PersonId = PersonId(UUID.randomUUID().toString())

    /** Dai 16 byte (nei corpi binari delle istantanee, §2.4). */
    fun fromBytes(bytes: ByteArray): PersonId {
      require(bytes.size == BYTES) { "L'id di una persona sono $BYTES byte." }
      val hex = hexOf(bytes)
      return PersonId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
    }

    /** Dalla forma base32 dei file (26 caratteri), o null se non e' canonica. */
    fun fromBase32(text: String): PersonId? = FaceBase32.decodeExact(text, BYTES)?.let { fromBytes(it) }

    /** Da testo esterno nella forma dell'app: accetta maiuscole e spazi ai bordi; null se non e' un id. */
    fun parse(raw: String): PersonId? = raw.trim().lowercase(Locale.ROOT).takeIf { CANONICAL_PERSON_UUID.matches(it) }?.let { PersonId(it) }
  }
}

/**
 * La **generazione** dei dati dei volti di un account (D3, "G" nel progetto): 128 bit casuali, **nuova a ogni attivazione** di Persone.
 * Sta nella voce `faces` dei dati personali e nel nome della cartella `faces/<G>/` dell'area privata. Rende impossibile che dati di
 * un'attivazione vecchia tornino dopo una cancellazione (E-F6, E-F33): ogni lettura e ogni scrittura dei dati dei volti porta la
 * generazione, e una generazione diversa da quella corrente non si legge mai (D30).
 *
 * [base32] e' la forma canonica (26 caratteri `[0-9a-z]` di Crockford); la classe non esiste con una forma diversa.
 */
@JvmInline
value class FaceGeneration(val base32: String) {

  init {
    require(FaceBase32.decodeExact(base32, BYTES) != null) { "Generazione dei volti non canonica." }
  }

  fun bytes(): ByteArray = FaceBase32.decodeExact(base32, BYTES)!!

  /** D37: mai la generazione nei log ne' nella Diagnostica. */
  override fun toString(): String = "FaceGeneration"

  companion object {
    const val BYTES: Int = 16
    const val CHARS: Int = 26

    private val random = SecureRandom()

    /** Una generazione nuova (attivazione, D3). */
    fun random(): FaceGeneration = fromBytes(ByteArray(BYTES).also { synchronized(random) { random.nextBytes(it) } })

    fun fromBytes(bytes: ByteArray): FaceGeneration {
      require(bytes.size == BYTES) { "Una generazione sono $BYTES byte." }
      return FaceGeneration(FaceBase32.encode(bytes))
    }

    /** Da un nome di cartella o da una voce: null se non e' la forma canonica (si ignora, formato §2.4). */
    fun parse(text: String): FaceGeneration? = if (FaceBase32.decodeExact(text, BYTES) != null) FaceGeneration(text) else null
  }
}
