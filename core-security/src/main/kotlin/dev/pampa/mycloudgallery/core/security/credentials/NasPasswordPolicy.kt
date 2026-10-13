package dev.pampa.mycloudgallery.core.security.credentials

import java.security.SecureRandom

/**
 * Quanto deve essere forte la password di un account del NAS per entrare nell'app (revisione 2, F1; C5).
 *
 * **Perche' una soglia e non solo un consiglio.** "Casa" e "il NAS" si riconoscono da dati che non sono segreti (ULA del
 * Wi-Fi, GUID della NEGOTIATE): un attaccante mirato che li ha copiati puo' farsi mandare un'autenticazione NTLMv2 da
 * un telefono fuori casa (Wi-Fi gemello) e poi provare a indovinare la password con un computer, senza limiti di
 * tentativi (`docs/sicurezza.md` L19). Contro questo difende solo la password. La soglia e' pensata per una famiglia:
 *
 *  - almeno [MIN_CHARS] caratteri **e** almeno [MIN_KINDS] tipi fra minuscole, maiuscole, cifre e simboli;
 *  - oppure una frase di almeno [PASSPHRASE_CHARS] caratteri, anche solo minuscole ("il-gatto-dorme-sul-divano");
 *  - in ogni caso: il nome dell'account e le sequenze ovvie (`123456`, `qwerty`, `password`...) non contano, e senza
 *    di loro devono restare almeno [MIN_CHARS] caratteri ("Mario-password-1" no, "una-password-per-le-foto-7" si');
 *    niente carattere ripetuto 4 volte di fila e almeno [MIN_DISTINCT] caratteri diversi.
 *
 * Con queste regole una password scelta a caso regge anni a un attacco con piu' schede grafiche; una scelta da una
 * persona regge molto meglio di "Mario1975". [generate] ne propone una (16 caratteri a caso, circa 79 bit).
 *
 * Si controlla **prima** di mandare la password al NAS: una password debole non parte nemmeno.
 */
object NasPasswordPolicy {

  const val MIN_CHARS = 12
  const val PASSPHRASE_CHARS = 16
  const val MIN_KINDS = 3
  const val MIN_DISTINCT = 6
  private const val MAX_RUN = 3
  private const val MIN_USER_CHARS = 3

  /** Che cosa non va, nell'ordine in cui si spiega all'utente. */
  enum class Weakness {
    /** Meno di [MIN_CHARS] caratteri. */
    TOO_SHORT,

    /** Contiene il nome dell'account. */
    CONTAINS_USER,

    /** Una sequenza ovvia, un carattere ripetuto, pochi caratteri diversi. */
    PREDICTABLE,

    /** Fra [MIN_CHARS] e [PASSPHRASE_CHARS] caratteri con meno di [MIN_KINDS] tipi di carattere. */
    TOO_SIMPLE,
  }

  /** Sequenze che chi indovina prova per prime (in minuscolo; si cercano dentro la password). */
  private val PREDICTABLE = listOf(
    "password", "passw0rd", "qwerty", "qwertz", "azerty", "asdfgh", "zxcvbn", "abcdef", "abc123", "iloveyou",
    "mycloud", "fritzbox", "fritz!box",
    "012345", "123456", "234567", "345678", "456789", "567890", "098765", "987654", "876543", "765432", "654321", "543210",
  )

  /** La debolezza di [password] per l'account [user] (nullo se l'account non si conosce), o null se va bene. */
  fun check(user: String?, password: CharArray): Weakness? {
    val length = Character.codePointCount(password, 0, password.size)
    if (length < MIN_CHARS) return Weakness.TOO_SHORT
    val lower = CharArray(password.size) { password[it].lowercaseChar() }
    try {
      // Le parti che chi indovina prova per prime non contano: senza di loro devono restare MIN_CHARS caratteri.
      val covered = BooleanArray(lower.size)
      val name = user?.trim()?.lowercase()
      if (name != null && name.length >= MIN_USER_CHARS) {
        lower.cover(name, covered)
        if (covered.count { !it } < MIN_CHARS) return Weakness.CONTAINS_USER
      }
      PREDICTABLE.forEach { lower.cover(it, covered) }
      if (covered.count { !it } < MIN_CHARS || password.hasRun() || password.distinctCount() < MIN_DISTINCT) {
        return Weakness.PREDICTABLE
      }
      if (length < PASSPHRASE_CHARS && password.kinds() < MIN_KINDS) return Weakness.TOO_SIMPLE
      return null
    } finally {
      lower.fill('\u0000')
    }
  }

  fun isAcceptable(user: String?, password: CharArray): Boolean = check(user, password) == null

  /** Caratteri della password proposta: minuscole e cifre senza quelli che si confondono (i, l, o, 0, 1). */
  private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

  /** Lunghezza della password proposta: [PASSPHRASE_CHARS] caratteri a caso da [ALPHABET], circa 79 bit. */
  const val GENERATED_CHARS = 16

  /**
   * Una password forte da impostare sul NAS (Dashboard, Utenti) e da scrivere poi nell'app. Si legge a gruppi di 4
   * ma non contiene separatori: va bene anche su un NAS che non accetta simboli. Chi la riceve la azzera dopo l'uso.
   */
  fun generate(random: SecureRandom = SecureRandom()): CharArray {
    while (true) {
      val candidate = CharArray(GENERATED_CHARS) { ALPHABET[random.nextInt(ALPHABET.length)] }
      if (check(null, candidate) == null) return candidate
      candidate.fill('\u0000')
    }
  }

  /** Segna in [covered] ogni occorrenza di [needle]. */
  private fun CharArray.cover(needle: String, covered: BooleanArray) {
    if (needle.isEmpty() || needle.length > size) return
    for (start in 0..size - needle.length) {
      var match = true
      for (i in needle.indices) {
        if (this[start + i] != needle[i]) {
          match = false
          break
        }
      }
      if (match) for (i in needle.indices) covered[start + i] = true
    }
  }

  private fun CharArray.hasRun(): Boolean {
    var run = 1
    for (i in 1 until size) {
      run = if (this[i] == this[i - 1]) run + 1 else 1
      if (run > MAX_RUN) return true
    }
    return false
  }

  private fun CharArray.distinctCount(): Int = asSequence().distinct().count()

  private fun CharArray.kinds(): Int {
    var lower = false
    var upper = false
    var digit = false
    var other = false
    for (c in this) {
      when {
        c.isLowerCase() -> lower = true
        c.isUpperCase() -> upper = true
        c.isDigit() -> digit = true
        else -> other = true
      }
    }
    return listOf(lower, upper, digit, other).count { it }
  }
}
