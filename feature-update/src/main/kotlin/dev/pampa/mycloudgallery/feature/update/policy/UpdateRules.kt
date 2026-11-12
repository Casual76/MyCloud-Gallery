package dev.pampa.mycloudgallery.feature.update.policy

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig

// LE REGOLE DELL'AGGIORNAMENTO (pacchetto U, 12/11/2026; docs/sicurezza.md C480-C486). Funzioni pure, provate sul computer (UpdateRulesTest): ogni
// regola ha una prova che cade se la si toglie. Un installatore puo' fare una cosa sola davvero grave, installare il pacchetto sbagliato: queste
// regole stanno fra l'elenco e lo scaricamento ([offerProblem]) e fra lo scaricamento e la sessione di installazione dell'engine
// ([checksumProblem], [archiveProblem]).

/** Perche' un aggiornamento non si scarica o non si installa. [code] e' stabile (prove, Diagnostica); le parole stanno nelle risorse. */
enum class UpdateProblem(val code: String) {
  /** L'elenco non dice lo sha256 dell'APK (o non e' un esadecimale da 64 cifre): senza, il file non si puo' confrontare con quello pubblicato. */
  CHECKSUM_MISSING("checksum_missing"),

  /** L'elenco non dice quanto pesa l'APK: senza, un download troncato o gonfiato non si riconosce. */
  SIZE_MISSING("size_missing"),

  /** L'APK dichiarato pesa piu' del tetto ([UpdateSourceConfig.MAX_APK_BYTES]). */
  TOO_LARGE("too_large"),

  /** Tag o nome dell'asset con caratteri non ammessi (una barra, `..`, uno spazio) o un asset che non e' un `.apk`. */
  BAD_NAME("bad_name"),

  /** L'indirizzo dell'APK non e' quello del repository delle release scritto nell'app. */
  OTHER_SOURCE("other_source"),

  /** Lo scaricamento non e' finito (rete, host non ammesso, peso diverso, annullato). */
  DOWNLOAD_FAILED("download_failed"),

  /** Il file ricevuto non ha lo sha256 dell'elenco. */
  CHECKSUM_MISMATCH("checksum_mismatch"),

  /** Android non riesce a leggere il file come APK. */
  UNREADABLE("unreadable"),

  /** L'APK e' di un'altra app. */
  OTHER_APP("other_app"),

  /** L'APK non e' piu' nuovo di quello installato (versionCode uguale o minore): niente ritorni indietro. */
  NOT_NEWER("not_newer"),

  /** L'APK non e' firmato con il certificato dell'app installata: Android lo rifiuterebbe comunque. */
  OTHER_SIGNATURE("other_signature"),

  /** L'installazione di Android non e' riuscita (il messaggio e' quello dell'engine). */
  INSTALL_FAILED("install_failed"),
}

/**
 * Cio' che l'app sa di un pacchetto: quello installato o un APK scaricato. [signers] sono le impronte SHA-256 (esadecimali, minuscole) dei certificati
 * con cui e' firmato adesso (`SigningInfo.apkContentsSigners`), mai il certificato intero.
 */
data class PackageFacts(
  val packageName: String,
  val versionCode: Long,
  val versionName: String?,
  val signers: Set<String>,
)

object UpdateRules {

  private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

  /** Tag e asset: lettere, cifre, punto, trattino e trattino basso; niente barre, spazi o percorsi. `publisher.py` scrive `beta-<app>-v1.0.0`, `<app>-1.0.0.apk`. */
  private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

  /**
   * Perche' [update] (offerto dall'elenco) non va nemmeno scaricato, o null. **sha256 e peso obbligatori**: l'engine salta il confronto quando lo sha256 e'
   * vuoto, il controllo in piu' sta qui, prima di chiamare l'installazione. Poi i nomi (l'asset diventa un nome di file nella cache) e l'indirizzo, che deve
   * essere quello del repository delle release scritto nell'app.
   */
  fun offerProblem(update: AvailableAppUpdate, maxBytes: Long = UpdateSourceConfig.MAX_APK_BYTES): UpdateProblem? = when {
    !SHA256.matches(update.sha256.trim()) -> UpdateProblem.CHECKSUM_MISSING
    update.sizeBytes <= 0L -> UpdateProblem.SIZE_MISSING
    update.sizeBytes > maxBytes -> UpdateProblem.TOO_LARGE
    !safeName(update.releaseTag) -> UpdateProblem.BAD_NAME
    !safeName(update.apkAsset) || !update.apkAsset.endsWith(".apk") -> UpdateProblem.BAD_NAME
    update.downloadUrl != UpdateSourceConfig.downloadUrl(update.releaseTag, update.apkAsset) -> UpdateProblem.OTHER_SOURCE
    else -> null
  }

  private fun safeName(name: String): Boolean = NAME.matches(name) && ".." !in name

  /** Il file ricevuto contro lo sha256 dell'elenco: senza maiuscole ne' spazi, come l'engine. */
  fun checksumProblem(expected: String, actual: String): UpdateProblem? =
    if (expected.trim().equals(actual.trim(), ignoreCase = true) && actual.isNotBlank()) null else UpdateProblem.CHECKSUM_MISMATCH

  /**
   * Perche' l'APK scaricato ([archive], null se Android non lo legge) non va installato sopra quello [installed], o null. Nell'ordine: leggibile, **questa
   * app**, **versionCode strettamente maggiore** (niente ritorni indietro, nemmeno alla stessa versione), **stessi certificati di firma**. Android
   * rifiuterebbe comunque una firma diversa, ma solo dopo la sua finestra di conferma e con parole sue: qui lo si dice prima, chiaro.
   */
  fun archiveProblem(installed: PackageFacts, archive: PackageFacts?): UpdateProblem? = when {
    archive == null -> UpdateProblem.UNREADABLE
    archive.packageName != installed.packageName -> UpdateProblem.OTHER_APP
    archive.versionCode <= installed.versionCode -> UpdateProblem.NOT_NEWER
    installed.signers.isEmpty() || archive.signers.isEmpty() -> UpdateProblem.OTHER_SIGNATURE
    normalized(archive.signers) != normalized(installed.signers) -> UpdateProblem.OTHER_SIGNATURE
    else -> null
  }

  private fun normalized(signers: Set<String>): Set<String> = signers.map { it.trim().lowercase() }.toSet()

  /**
   * E' ora di un controllo silenzioso? Mai controllato, **24 ore** passate, oppure un'ora salvata nel futuro (l'orologio del telefono e' tornato
   * indietro: altrimenti non si controllerebbe piu' fino a quel giorno).
   */
  fun isDue(lastCheckedAt: Long?, now: Long, interval: Long = UpdateSourceConfig.CHECK_INTERVAL_MILLIS): Boolean =
    lastCheckedAt == null || now < lastCheckedAt || now - lastCheckedAt >= interval

  /** L'avviso all'apertura **una volta per versione**: c'e' una versione nuova e non e' quella gia' annunciata. */
  fun shouldPrompt(found: String?, promptedVersion: String?): Boolean = found != null && found != promptedVersion
}
