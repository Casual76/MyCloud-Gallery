package dev.pampa.mycloudgallery.core.common.diagnostics

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

/**
 * Un valore che puo' finire nell'esportazione della Diagnostica (piano 2.7, docs/fase2-progetto.md §2.6).
 *
 * **Niente testo libero, per costruzione.** L'esportazione e' un file che l'utente manda a qualcuno: non
 * deve poter contenere un nome, un utente del NAS, un indirizzo, un percorso. Invece di affidarsi alla
 * disciplina di chi scrive (che un giorno sbaglia), il tipo non ha un caso "stringa":
 *  - [Num], [Flag] e [Dur] portano solo numeri;
 *  - [Enum] si costruisce solo da una costante di un `enum` dell'app ([Enum.of]): il nome viene dal codice,
 *    mai da un dato;
 *  - [Hashed] si costruisce solo con [DiagHasher], che produce un'impronta HMAC con una chiave locale:
 *    serve a dire "e' lo stesso account / lo stesso IP di prima" senza dire quale.
 *
 * Il test dell'esportazione (P8) inserisce nomi, IP e utenti e verifica che non compaiano; questo tipo e'
 * la ragione per cui quel test puo' passare.
 */
sealed interface ExportValue {

  /** Un numero: conteggi, versioni, byte, codici di stato SMB. */
  data class Num(val value: Long) : ExportValue

  /** Si' o no. */
  data class Flag(val value: Boolean) : ExportValue

  /** Una durata in millisecondi (tempi delle fasi del test di connessione, attese). */
  data class Dur(val millis: Long) : ExportValue {
    companion object {
      fun of(duration: Duration): Dur = Dur(duration.inWholeMilliseconds)
    }
  }

  /**
   * Il nome di una costante di un enum dell'app, con il nome (semplice) del suo tipo. Il costruttore e'
   * privato: l'unico ingresso e' [of], che accetta solo un enum vero.
   */
  class Enum private constructor(val type: String, val constant: String) : ExportValue {

    override fun equals(other: Any?): Boolean = other is Enum && other.type == type && other.constant == constant

    override fun hashCode(): Int = 31 * type.hashCode() + constant.hashCode()

    override fun toString(): String = "Enum($type.$constant)"

    companion object {
      fun of(value: kotlin.Enum<*>): Enum {
        val declaring = value.javaClass.let { if (it.isEnum) it else it.superclass }
        return Enum(declaring.simpleName, value.name)
      }

      /**
       * Solo per rileggere l'anello salvato da questo stesso modulo. Il file e' un dato (potrebbe essere manomesso):
       * si accetta solo la **forma** di un enum dell'app, il tipo in `UpperCamel` (almeno una minuscola) e la costante
       * in `MAIUSCOLO_CON_TRATTINI_BASSI`, come fa l'esportatore (P8). Un nome come «Mario.Rossi» non e' una costante
       * e l'evento si scarta gia' qui, non solo all'esportazione (P9, nota di P8 in docs/decisioni.md).
       */
      internal fun restore(type: String, constant: String): Enum? =
        if (type.length <= MAX_NAME && TYPE.matches(type) && CONSTANT.matches(constant)) Enum(type, constant) else null

      private const val MAX_NAME = 64

      /** `UpperCamel` con almeno una minuscola (un tipo, non una sigla o una parola in maiuscolo). */
      private val TYPE = Regex("[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*")
      private val CONSTANT = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
  }

  /**
   * Un elenco di **identificativi del codice** dell'app: nomi di classe e punti `classe.metodo(File.kt:riga)`. Serve a una sola cosa, il registro
   * locale dei crash (C472): dice dove si e' rotto, mai perche' (il messaggio di un'eccezione puo' portare un percorso, un nome di file, un indirizzo).
   *
   * **Niente testo libero neanche qui.** Il costruttore e' privato e l'unico ingresso, [of], accetta solo voci che hanno la forma di un identificativo
   * ([ENTRY]: segmenti che cominciano con una lettera, separati da punti, con in coda l'eventuale `(File.kt:riga)`): niente spazi, barre, virgolette,
   * virgole, cifre iniziali (un indirizzo IP o un numero non passano), e un tetto di lunghezza e di voci. Una voce che non ha la forma fa rifiutare
   * tutto l'elenco: meglio un campo in meno che un campo con un dato.
   */
  class Trace private constructor(val entries: List<String>) : ExportValue {

    override fun equals(other: Any?): Boolean = other is Trace && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "Trace(${entries.size} voci)"

    companion object {
      /** Le voci di un elenco: bastano le prime cornici nostre e la catena delle classi. */
      const val MAX_ENTRIES = 40

      /** Una cornice nostra e' lunga di solito meno di 120 caratteri. */
      const val MAX_ENTRY_LENGTH = 220

      private const val SEGMENT = "[A-Za-z_$<][A-Za-z0-9_$<>-]*"

      /** Una classe (`java.io.IOException`), un punto (`dev.pampa.Foo\$bar\$1.invokeSuspend`) o un punto con file e riga (`...(Foo.kt:12)`). */
      val ENTRY = Regex("$SEGMENT(?:\\.$SEGMENT)*(?:\\([A-Za-z0-9_$]{1,80}\\.(?:kt|java):[0-9]{1,6}\\))?")

      /** L'elenco, o null se una voce non ha la forma di un identificativo del codice o se sono troppe. */
      fun of(entries: List<String>): Trace? =
        if (entries.size <= MAX_ENTRIES && entries.all { isEntry(it) }) Trace(entries.toList()) else null

      fun isEntry(text: String): Boolean = text.length <= MAX_ENTRY_LENGTH && ENTRY.matches(text)
    }
  }

  /**
   * Un'impronta (12 cifre esadecimali, 48 bit di HMAC-SHA256). Il costruttore e' interno: solo [DiagHasher]
   * (e la rilettura dell'anello) ne creano.
   */
  class Hashed internal constructor(val hex: String) : ExportValue {
    init {
      require(HEX.matches(hex)) { "impronta non valida" }
    }

    override fun equals(other: Any?): Boolean = other is Hashed && other.hex == hex

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = "Hashed($hex)"

    internal companion object {
      val HEX = Regex("[0-9a-f]{12}")
    }
  }
}

/**
 * Trasforma un dato personale (account, nome del dispositivo, IP, prefisso ULA) in un [ExportValue.Hashed].
 *
 * **HMAC con una chiave, non un hash semplice.** Un IP di casa o un nome utente hanno poca entropia: lo
 * SHA-256 di `192.168.178.58` si ritrova provando tutti gli indirizzi privati in un secondo. Con una chiave
 * casuale che non lascia il telefono l'impronta non si inverte, ma resta stabile per la stessa chiave: due
 * righe con la stessa impronta parlano dello stesso account. Chi usa questa classe decide la vita della chiave
 * (per installazione o per singola esportazione: P8).
 */
class DiagHasher(key: ByteArray) {

  private val key: ByteArray = key.copyOf()

  init {
    require(key.size >= MIN_KEY_BYTES) { "chiave troppo corta" }
  }

  fun hash(value: String): ExportValue.Hashed {
    val mac = Mac.getInstance(ALGORITHM)
    mac.init(SecretKeySpec(key, ALGORITHM))
    val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8))
    val hex = buildString(HEX_CHARS) {
      for (i in 0 until HEX_CHARS / 2) {
        val b = digest[i].toInt() and 0xff
        append(HEX_DIGITS[b ushr 4])
        append(HEX_DIGITS[b and 0x0f])
      }
    }
    return ExportValue.Hashed(hex)
  }

  companion object {
    private const val ALGORITHM = "HmacSHA256"
    private const val MIN_KEY_BYTES = 16
    private const val KEY_BYTES = 32
    private const val HEX_CHARS = 12
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Una chiave nuova, casuale (per esempio: una per ogni esportazione). */
    fun withRandomKey(random: SecureRandom = SecureRandom()): DiagHasher =
      DiagHasher(ByteArray(KEY_BYTES).also(random::nextBytes))
  }
}
