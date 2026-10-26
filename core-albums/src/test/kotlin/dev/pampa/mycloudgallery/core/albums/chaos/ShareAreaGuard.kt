package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

// LA GUARDIA DEL BANCO SUL NAS VERO (docs/prova-500.md Parte E, §E4; stesse regole di `ChaosAreaGuard` della 4B, F4-D30/D33, ma piu' strette): il banco
// della condivisione lavora SOLO sotto `mcg-it/<giro>/` delle share di prova `test` (area privata) e `test-comune` (area comune) con l'account `test`, e
// rifiuta qualunque altra share, anche `test2`. Due livelli, entrambi PRIMA di qualunque chiamata SMB:
//   1. la configurazione (account e share del file delle credenziali) si valida prima di aprire un socket: un errore nel file FA FALLIRE la prova, non
//      allarga il recinto;
//   2. ogni chiamata del motore e dell'oracolo passa dal trasporto recintato ([AreaTransport]), che controlla la share e radica ogni percorso in
//      `mcg-it/<giro>/r<N>/` (la cartella dell'app compresa): non esiste una chiamata che esca dall'area.
// Il file delle credenziali lo legge solo questo codice, in memoria: niente di cio' che contiene finisce in un log o in un rapporto.

/** Un accesso fuori dall'area di prova: lanciata PRIMA della chiamata, mai dopo. */
class AreaViolation(message: String) : RuntimeException(message)

/** Un errore di autenticazione: la prova si ferma subito, senza nuovi tentativi (il NAS blocca l'account). */
class AreaAuthStop(message: String) : RuntimeException(message)

/**
 * Le chiavi del file delle credenziali che il banco usa (lo stesso file di `NasIntegration` e dei banchi della 4B): `user`, `password`, `share` (area
 * privata), `share2` (area comune), `host`, `guid` (facoltativa). Le altre chiavi (`user2`, `password2`, `privateShare2`, `chaos.rounds`...) non si leggono
 * nemmeno: questo banco non usa nessun altro account.
 */
class ShareAreaCredentials(
  val host: String,
  val user: String,
  val password: CharArray,
  val privateShare: String,
  val commonShare: String,
  val guid: String?,
) {
  override fun toString(): String = "ShareAreaCredentials(nascoste)"

  companion object {
    const val DEFAULT_HOST = "192.168.178.58"

    /** Null se mancano le chiavi obbligatorie (`user`, `password`, `share`, `share2`). */
    fun parse(properties: Properties): ShareAreaCredentials? {
      fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
      return ShareAreaCredentials(
        host = value("host") ?: DEFAULT_HOST,
        user = value("user") ?: return null,
        password = (value("password") ?: return null).toCharArray(),
        privateShare = value("share") ?: return null,
        commonShare = value("share2") ?: return null,
        guid = value("guid"),
      )
    }
  }
}

/** Quante chiamate sono arrivate al trasporto vero attraverso la guardia: l'autotest le vuole a zero per ogni rifiuto. */
class DelegateCounter {
  val calls = AtomicInteger(0)
}

/** "Il login e' stato rifiutato": da quel momento nessuna chiamata di autenticazione arriva piu' al NAS. */
class AreaAuthLatch {
  @Volatile
  var failed: Boolean = false
    private set

  fun trip() {
    failed = true
  }
}

/** Il recinto di un giro del banco. Si costruisce solo con [of], che valida la configurazione. */
class ShareAreaGuard private constructor(val runId: String, val account: String) {

  /** La radice del giro: `mcg-it/<giro>`. Tutto cio' che il banco scrive sul NAS vero sta li' sotto. */
  val root: SharePath = SharePath.of(ROOT, runId)

  /** La radice del giro [round] del banco: `mcg-it/<giro>/r<round>` (ogni giro ha il suo mondo, la cartella dell'app compresa). */
  fun roundRoot(round: Int): SharePath = root.child("r$round")

  fun checkAccount(user: String) {
    if (TestShares.fold(user.trim()) in FORBIDDEN_ACCOUNTS || user.lowercase(Locale.ROOT) != account) throw AreaViolation("account non di prova")
  }

  fun checkShare(share: String): String {
    if (share.lowercase(Locale.ROOT) !in SHARES || !TestShares.isTestShare(share)) throw AreaViolation("share fuori dall'area di prova")
    return share
  }

  companion object {
    const val ROOT = "mcg-it"

    /** `aaaammgg-hhmmss-<8 esadecimali>`: il nome di un giro (la data e' solo un nome). */
    val RUN_ID = Regex("^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$")

    /** Le sole share toccate, in minuscolo: la privata e la comune di prova. Mai `test2`, `alessio`, `pampa`, `NAS`, `Public`. */
    val SHARES: Set<String> = setOf("test", "test-comune")

    /** Mai questi account, in nessuna forma (NFKC, maiuscole). */
    val FORBIDDEN_ACCOUNTS: Set<String> = setOf("admin", "alessio", "pampa", "root", "administrator")

    /** La guardia per [credentials], PRIMA di qualunque connessione: se qualcosa non va lancia [AreaViolation] e la prova fallisce subito. */
    fun of(credentials: ShareAreaCredentials, runId: String): ShareAreaGuard {
      require(RUN_ID.matches(runId)) { "giro non valido" }
      val user = credentials.user.trim()
      if (TestShares.fold(user) in FORBIDDEN_ACCOUNTS || user.lowercase(Locale.ROOT) != "test" || !TestShares.isTestAccount(user)) {
        throw AreaViolation("il file delle credenziali nomina un account che non e' quello di prova `test`")
      }
      if (credentials.privateShare.lowercase(Locale.ROOT) != "test" || !TestShares.isTestShare(credentials.privateShare)) {
        throw AreaViolation("l'area privata del file delle credenziali non e' la share di prova `test`")
      }
      if (credentials.commonShare.lowercase(Locale.ROOT) != "test-comune" || !TestShares.isTestShare(credentials.commonShare)) {
        throw AreaViolation("l'area comune del file delle credenziali non e' la share di prova `test-comune`")
      }
      NasAddress.parseOrNull(credentials.host) ?: throw AreaViolation("host non e' un IP letterale")
      return ShareAreaGuard(runId, user.lowercase(Locale.ROOT))
    }

    /** Un giro nuovo: data e ora (solo un nome) piu' otto cifre esadecimali casuali. */
    fun newRunId(nowUtc: java.time.ZonedDateTime, random: java.util.Random): String =
      java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(nowUtc) + "-" +
        ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
  }
}
