package dev.pampa.mycloudgallery.core.nas.integration.security

import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.nas.integration.ItCredentials
import dev.pampa.mycloudgallery.core.nas.integration.SmbAccess
import dev.pampa.mycloudgallery.core.nas.integration.SmbAreaGuard
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaGuard
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaViolation
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.util.Collections
import java.util.Locale

// La REGOLA NUOVA della seconda parte di P11 (decisione 2 dell'utente del 02/11; C470): i file ostili delle prove sull'app possono stare
// anche FUORI da `mcg-it/<giro>/`, ma solo
//   - in `test-comune/mcg-ostile/` (le immagini malformate di S3 (c), che l'app decodifica come foto dell'area comune);
//   - nell'AREA DELLA FAMIGLIA DI PROVA, cioe' `.mycloudgallery/` di `test` e `test-comune`, e li' solo nelle cartelle dei dispositivi
//     di prova di QUESTO giro (`devices/<id>/`, `albums/<album>/<id>/`, la loro richiesta in `family/requests/<id>`), nel file del manifest
//     (`release/manifest.mcgr`, con quello che c'era messo da parte e rimesso) e nella rinomina di `family` in `family-vecchia-<giro>`
//     (il reset della famiglia di prova, "Ricominciare da zero");
// MAI su `test2` (si legge, non si scrive), MAI su `NAS`, `Public`, `alessio`, `pampa` (in nessuna forma), MAI altrove. Si cancella solo
// cio' che il giro ha creato (il REGISTRO della guardia, salvato sul PC fra un comando e l'altro): un file che c'era non si tocca, la
// cartella `family` non si cancella nemmeno dallo handle. Le letture: elenchi e stato ovunque sulle due share (per la fotografia), il
// contenuto solo sotto `.mycloudgallery/` e `mcg-ostile/`. Tutto PRIMA della chiamata SMB (TestAreaTransport), come TestAreaGuard.

internal class FamilyAreaGuard private constructor(
  val runId: String,
  private val accounts: Set<String>,
  /** Gli id dei dispositivi di prova di questo giro (minuscolo). */
  private val devices: Set<String>,
  ledger: Collection<String>,
) : SmbAreaGuard {

  /** Cio' che questo giro ha creato: `f|share|a/b/c` (file) o `d|share|a/b` (cartelle), minuscolo. */
  private val created: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet(ledger))

  /** Il registro, nell'ordine di creazione (per la pulizia: al contrario). */
  fun ledger(): List<String> = synchronized(created) { created.toList() }

  /**
   * Toglie una voce dal registro: dopo una rinomina che **non** crea un file della prova (la famiglia vecchia messa da parte, il manifest
   * che c'era messo da parte o rimesso al suo posto) e dopo una pulizia riuscita. Cosi' la pulizia non tocca mai un file che non e' suo.
   */
  fun forget(kind: Char, share: String, path: String) {
    created.remove("$kind|${share.lowercase(Locale.ROOT)}|${path.lowercase(Locale.ROOT)}")
  }

  val familyArchive: String get() = "family-vecchia-$runId"
  val manifestArchive: String get() = "manifest-prima-$runId.mcgr"

  override fun checkAccount(user: String) {
    if (TestShares.fold(user.trim()) in TestAreaGuard.FORBIDDEN_ACCOUNTS || user.lowercase(Locale.ROOT) !in accounts) {
      throw TestAreaViolation("account non di prova")
    }
  }

  override fun checkShare(share: String): String {
    if (!TestShares.isTestShare(share) || share.lowercase(Locale.ROOT) !in SHARES) throw TestAreaViolation("share fuori dall'area della famiglia di prova")
    return share
  }

  override fun checkSmb(share: String, raw: String, op: SmbAccess) {
    checkShare(share)
    val parts = split(raw) ?: throw TestAreaViolation("percorso non valido")
    if (!allowed(share.lowercase(Locale.ROOT), parts.map { it.lowercase(Locale.ROOT) }, op)) {
      throw TestAreaViolation("percorso fuori dall'area della famiglia di prova ($op)")
    }
  }

  override fun created(share: String, raw: String, op: SmbAccess) {
    val parts = split(raw) ?: return
    val key = share.lowercase(Locale.ROOT) + "|" + parts.joinToString("/").lowercase(Locale.ROOT)
    when (op) {
      SmbAccess.MKDIR -> created += "d|$key"
      SmbAccess.CREATE, SmbAccess.RENAME -> created += "f|$key"
      else -> Unit
    }
  }

  private fun mine(kind: Char, share: String, parts: List<String>): Boolean = "$kind|$share|${parts.joinToString("/")}" in created

  private fun allowed(share: String, p: List<String>, op: SmbAccess): Boolean {
    val common = share == COMMON
    return when (op) {
      SmbAccess.LIST, SmbAccess.STAT -> true
      SmbAccess.SPACE -> true
      SmbAccess.OPEN_READ -> p.size >= 2 && (p[0] == APP || (common && p[0] == HOSTILE))
      SmbAccess.MKDIR -> writableDir(common, p)
      SmbAccess.ADD_FILE -> writableDir(common, p)
      SmbAccess.CREATE -> writableFile(common, p)
      SmbAccess.DELETE -> mine('f', share, p)
      SmbAccess.RMDIR -> mine('d', share, p)
      SmbAccess.CHANGE -> mine('f', share, p) || (common && (p == FAMILY || p == MANIFEST || p == listOf(APP, "release", manifestArchive.lowercase(Locale.ROOT))))
      SmbAccess.RENAME -> common && (p == listOf(APP, familyArchive.lowercase(Locale.ROOT)) || p == MANIFEST || p == listOf(APP, "release", manifestArchive.lowercase(Locale.ROOT)))
    }
  }

  /** Le cartelle che il giro puo' creare (o trovare gia' create): i prefissi delle zone scrivibili. */
  private fun writableDir(common: Boolean, p: List<String>): Boolean = when {
    p.isEmpty() -> false
    common && p[0] == HOSTILE -> true
    p[0] != APP -> false
    p.size == 1 -> true
    p[1] == "devices" -> p.size == 2 || p[2] in devices
    p[1] == "albums" -> p.size == 2 || (ALBUM_ID.matches(p[2]) && (p.size == 3 || p[3] in devices))
    common && p[1] == "family" -> p.size == 2 || (p.size == 3 && p[2] == "requests")
    common && p[1] == "release" -> p.size == 2
    else -> false
  }

  /** I file che il giro puo' creare (sempre in esclusiva: un file che c'e' gia' non si sovrascrive). */
  private fun writableFile(common: Boolean, p: List<String>): Boolean = when {
    p.size < 2 -> false
    common && p[0] == HOSTILE -> true
    p[0] != APP -> false
    p[1] == "devices" -> p.size >= 4 && p[2] in devices
    p[1] == "albums" -> p.size >= 5 && ALBUM_ID.matches(p[2]) && p[3] in devices
    common && p[1] == "family" -> p.size == 4 && p[2] == "requests" && p[3] in devices
    common -> p == MANIFEST
    else -> false
  }

  /** Come TestAreaGuard: nessun componente vuoto, `.`, `..` o di controllo (nessuna normalizzazione che possa uscire). */
  private fun split(raw: String): List<String>? {
    if (raw.isEmpty()) return emptyList()
    val parts = raw.split('/', '\\')
    if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 0x20 } }) return null
    return parts
  }

  companion object {
    const val COMMON = "test-comune"
    const val HOSTILE = "mcg-ostile"
    const val APP = ".mycloudgallery"

    /** Le sole share della regola: `test2` resta fuori (si legge con le prove di S1, non si scrive mai). */
    val SHARES: Set<String> = setOf("test", COMMON)

    private val FAMILY = listOf(APP, "family")
    private val MANIFEST = listOf(APP, "release", "manifest.mcgr")

    /**
     * La guardia, **prima di qualunque connessione**: account di prova (solo il primo, `test`: scrive lui), share del file fra le due
     * della regola, host letterale, id dei dispositivi validi. [ledger] e' il registro di un comando precedente dello stesso giro.
     */
    fun of(credentials: ItCredentials, runId: String, devices: Collection<String>, ledger: Collection<String> = emptyList()): FamilyAreaGuard {
      require(TestAreaGuard.RUN_ID.matches(runId)) { "giro non valido" }
      val user = credentials.user
      if (TestShares.fold(user.trim()) in TestAreaGuard.FORBIDDEN_ACCOUNTS || !TestShares.isTestAccount(user)) {
        throw TestAreaViolation("il file delle credenziali nomina un account che non e' di prova")
      }
      for (share in listOfNotNull(credentials.share, credentials.share2)) {
        if (!TestShares.isTestShare(share) || share.lowercase(Locale.ROOT) !in SHARES) {
          throw TestAreaViolation("l'area della famiglia di prova vuole share=test e share2=test-comune")
        }
      }
      if (credentials.share2 == null) throw TestAreaViolation("l'area della famiglia di prova vuole l'area comune (share2=test-comune)")
      NasAddress.parseOrNull(credentials.host) ?: throw TestAreaViolation("host non e' un IP letterale")
      for (id in devices) require(TrustIds.isValid(id)) { "id di dispositivo non valido" }
      for (line in ledger) require(LEDGER.matches(line)) { "riga del registro non valida" }
      return FamilyAreaGuard(runId, setOf(user.lowercase(Locale.ROOT)), devices.map { it.lowercase(Locale.ROOT) }.toSet(), ledger)
    }

    /** L'id di un album e' un UUID canonico (`AlbumId`), in minuscolo come lo scrive l'app. */
    private val ALBUM_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private val LEDGER = Regex("^[fd]\\|(test|test-comune)\\|[^|]+$")
  }
}
