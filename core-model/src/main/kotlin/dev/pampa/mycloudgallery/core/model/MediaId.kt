package dev.pampa.mycloudgallery.core.model

import java.util.Locale
import java.util.UUID

private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** Il messaggio non contiene il valore: un identificativo malformato puo' venire da dati ostili. */
private fun requireCanonicalUuid(value: String, what: String) {
  require(CANONICAL_UUID.matches(value)) { "$what non e' un UUID canonico (minuscolo, con trattini)." }
}

/**
 * L'identita' stabile di un media (piano §1 "Media", sottofase 1.3).
 *
 * **Perche' un UUID casuale e non un hash del contenuto.** L'identita' deve esistere dal primo istante
 * in cui il media e' visto, quando dell'impronta c'e' solo l'hash rapido (l'hash completo di un video
 * da 9 GB sul NAS costa minuti), e deve restare la stessa quando i byte cambiano: una modifica o una
 * correzione creano una *versione*, non un media nuovo. Un hash del contenuto cambierebbe proprio in
 * quei momenti e spezzerebbe album, preferiti, vettori e stati di lavoro.
 *
 * **Perche' non l'`_ID` di MediaStore.** Non e' un'identita' (`docs/android.md`, scoperta 7): cambia
 * con una nuova scansione o un aggiornamento di One UI e ha numerazioni diverse su ogni dispositivo.
 * Resta solo come cache dentro [Location.Phone].
 *
 * Quando due media si rivelano lo stesso (impronte complete uguali) uno sopravvive e l'altro diventa
 * un *alias* che punta al sopravvissuto (`domain.MergeRules`): per questo l'ordine e' definito
 * ([compareTo]) e la scelta del sopravvissuto e' deterministica su ogni dispositivo.
 */
@JvmInline
value class MediaId(val value: String) : Comparable<MediaId> {

  init {
    requireCanonicalUuid(value, "L'identita' di un media")
  }

  override fun compareTo(other: MediaId): Int = value.compareTo(other.value)

  override fun toString(): String = value

  companion object {
    /** Un'identita' nuova: solo quando le regole di collegamento hanno escluso ogni media esistente. */
    fun random(): MediaId = MediaId(UUID.randomUUID().toString())

    /** Da testo esterno (database, NAS): accetta maiuscole e spazi ai bordi, salva la forma canonica. */
    fun parse(raw: String): MediaId = MediaId(raw.trim().lowercase(Locale.ROOT))
  }
}

/**
 * L'identita' di una versione di un media (originale, modificata, corretta). UUID per lo stesso motivo
 * di [MediaId]: nasce prima che si conosca l'hash completo dei suoi byte e, dalla Fase 4, viaggia
 * nell'indice condiviso tra dispositivi.
 */
@JvmInline
value class VersionId(val value: String) {

  init {
    requireCanonicalUuid(value, "L'identita' di una versione")
  }

  override fun toString(): String = value

  companion object {
    fun random(): VersionId = VersionId(UUID.randomUUID().toString())

    fun parse(raw: String): VersionId = VersionId(raw.trim().lowercase(Locale.ROOT))
  }
}

/** Un gruppo di media mostrati insieme (raffica, coppia RAW+JPEG): vedi [MediaGroup]. */
@JvmInline
value class MediaGroupId(val value: String) {

  init {
    requireCanonicalUuid(value, "L'identita' di un gruppo")
  }

  override fun toString(): String = value

  companion object {
    fun random(): MediaGroupId = MediaGroupId(UUID.randomUUID().toString())
  }
}

/** Un album manuale (dato personale). La Fase 7 lo completa; qui serve a non perdere riferimenti. */
@JvmInline
value class AlbumId(val value: String) {

  init {
    requireCanonicalUuid(value, "L'identita' di un album")
  }

  override fun toString(): String = value

  companion object {
    fun random(): AlbumId = AlbumId(UUID.randomUUID().toString())

    /** La lunghezza del nonce dell'id legato al creatore (D7). */
    const val NONCE_BYTES: Int = 16

    /**
     * Fase 7 (D7, `docs/fase7-progetto.md` §1.3): l'id **legato al creatore**, i primi 128 bit di
     * `SHA-256("mcg-album-id\0" ‖ account del creatore ‖ nonce di 16 byte)`, nella forma canonica di un UUID (le cifre esadecimali dei
     * 16 byte con i trattini: non e' un UUID "di versione", e' un nome da 128 bit). Chi legge una definizione d'album la ricalcola
     * dall'account e dal nonce che porta: un altro familiare non puo' "rivendicare" un album esistente (E-SH14). Vale anche per gli
     * album personali, cosi' un album nato personale si condivide senza cambiare id. Sul NAS lo stesso id compare in base32 (P2).
     */
    fun derived(creator: AccountId, nonce: ByteArray): AlbumId {
      require(nonce.size == NONCE_BYTES) { "Il nonce di un album sono $NONCE_BYTES byte." }
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      digest.update("mcg-album-id\u0000".toByteArray(Charsets.UTF_8))
      digest.update(creator.value.toByteArray(Charsets.UTF_8))
      digest.update(nonce)
      val hex = digest.digest().copyOf(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
      return AlbumId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
    }
  }
}

/**
 * La riga di una posizione nella replica locale. E' un numero locale e non un UUID di proposito: una
 * posizione e' un'osservazione di *questo* dispositivo (un file del suo MediaStore, un percorso visto
 * con il suo account), non un'identita' da far viaggiare. Nessun dato personale la referenzia.
 */
@JvmInline
value class LocationId(val value: Long) {

  init {
    require(value > 0) { "L'identificativo di una posizione deve essere positivo." }
  }

  override fun toString(): String = value.toString()
}

private val ACCOUNT_NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

/**
 * Un account del NAS (es. `alessio`), nella forma canonica minuscola. Le utenze di My Cloud OS non
 * distinguono maiuscole e minuscole: normalizzare qui evita due "account" per la stessa persona.
 */
@JvmInline
value class AccountId(val value: String) {

  init {
    require(ACCOUNT_NAME.matches(value)) { "Nome di account NAS non valido." }
  }

  override fun toString(): String = value

  companion object {
    fun of(raw: String): AccountId = AccountId(raw.trim().lowercase(Locale.ROOT))
  }
}
