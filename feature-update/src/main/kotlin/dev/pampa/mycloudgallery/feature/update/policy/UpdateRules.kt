package dev.pampa.mycloudgallery.feature.update.policy

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig

// LE REGOLE DELL'AGGIORNAMENTO (pacchetto U, 12/11/2026; docs/sicurezza.md C480-C489). Funzioni pure, provate sul computer (UpdateRulesTest): ogni
// regola ha una prova che cade se la si toglie. Un installatore puo' fare una cosa sola davvero grave, installare il pacchetto sbagliato: queste
// regole stanno fra l'elenco e lo scaricamento ([offerProblem], [cleanOffer]) e fra lo scaricamento e la sessione di installazione dell'engine
// ([checksumProblem], [archiveProblem]). In piu' (giro 1 delle critiche): quando l'avviso torna dopo «Dopo» ([shouldPrompt]) e quali guasti si
// riprovano ([UpdateProblem.retryable]).

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

  /** Il nome della versione non ha la forma ammessa (`1.2.0`, oppure `1.2.0-beta.03`): vedi [UpdateRules.VERSION]. */
  BAD_VERSION("bad_version"),

  /** L'indirizzo dell'APK non e' quello del repository delle release scritto nell'app. */
  OTHER_SOURCE("other_source"),

  /** Lo scaricamento non e' finito (rete, host non ammesso, peso diverso, annullato). */
  DOWNLOAD_FAILED("download_failed"),

  /** Il file ricevuto non ha lo sha256 dell'elenco (rovinato per strada, o un altro file). */
  CHECKSUM_MISMATCH("checksum_mismatch"),

  /** Android non riesce a leggere il file come APK. */
  UNREADABLE("unreadable"),

  /** L'APK e' di un'altra app. */
  OTHER_APP("other_app"),

  /** L'APK dice un `versionName` diverso da quello dell'elenco: e' un'altra versione di quella annunciata. */
  WRONG_VERSION("wrong_version"),

  /** L'APK non e' piu' nuovo di quello installato (versionCode uguale o minore): niente ritorni indietro. */
  NOT_NEWER("not_newer"),

  /** L'APK non e' firmato con il certificato dell'app installata: Android lo rifiuterebbe comunque. */
  OTHER_SIGNATURE("other_signature"),

  /** Android non ha spazio per installarla. */
  NO_SPACE("no_space"),

  /** Android (o una regola del telefono) ha bloccato l'installazione. */
  BLOCKED("blocked"),

  /** L'APK non funziona su questo telefono. */
  INCOMPATIBLE("incompatible"),

  /** L'installazione di Android non e' riuscita per un motivo che l'app non riconosce. */
  INSTALL_FAILED("install_failed"),
  ;

  /**
   * Ha senso «Riprova»? Si' per la rete, il file rovinato per strada, lo spazio e cio' che l'app non riconosce; no per cio' che riprovando fallisce
   * uguale (un'altra app, un'altra firma, una versione non piu' nuova, l'elenco scritto male): li' si dice di avvisare chi ha dato l'app.
   */
  val retryable: Boolean get() = this in RETRYABLE

  /** Il file verificato resta per «Riprova» (che allora non riscarica): i guasti di Android che non dipendono dal file. */
  val keepsFile: Boolean get() = this == NO_SPACE || this == INSTALL_FAILED

  private companion object {
    val RETRYABLE = setOf(DOWNLOAD_FAILED, CHECKSUM_MISMATCH, NO_SPACE, INSTALL_FAILED)
  }
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
   * La forma ammessa di una versione (US-3): tre numeri senza zeri davanti, al piu' quattro cifre l'uno, e per una beta `-beta.` con **due** cifre.
   * Niente `v`, niente `+`, niente quarto segmento: sono i nomi che il confronto dell'engine (`compareVersions`) mette in un ordine sbagliato.
   * La regola di pubblicazione che la rispetta e' in docs/aggiornamenti-in-app.md, «Le versioni».
   */
  val VERSION = Regex("^(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})(-beta\\.[0-9]{2})?$")

  /** Le note si tagliano a questo numero di caratteri PRIMA di ogni trasformazione (US-3): il resto dell'elenco non arriva nemmeno al Markdown. */
  const val MAX_NOTES_CHARS: Int = 2_000

  /**
   * Perche' [update] (offerto dall'elenco) non va nemmeno scaricato, o null. **sha256 e peso obbligatori**: l'engine salta il confronto quando lo sha256 e'
   * vuoto, il controllo in piu' sta qui, prima di chiamare l'installazione. Poi la forma della versione, i nomi (l'asset diventa un nome di file nella
   * cache) e l'indirizzo, che deve essere quello del repository delle release scritto nell'app.
   */
  fun offerProblem(update: AvailableAppUpdate, maxBytes: Long = UpdateSourceConfig.MAX_APK_BYTES): UpdateProblem? = when {
    !SHA256.matches(update.sha256.trim()) -> UpdateProblem.CHECKSUM_MISSING
    update.sizeBytes <= 0L -> UpdateProblem.SIZE_MISSING
    update.sizeBytes > maxBytes -> UpdateProblem.TOO_LARGE
    !VERSION.matches(update.version) -> UpdateProblem.BAD_VERSION
    !safeName(update.releaseTag) -> UpdateProblem.BAD_NAME
    !safeName(update.apkAsset) || !update.apkAsset.endsWith(".apk") -> UpdateProblem.BAD_NAME
    update.downloadUrl != UpdateSourceConfig.downloadUrl(update.releaseTag, update.apkAsset) -> UpdateProblem.OTHER_SOURCE
    else -> null
  }

  private fun safeName(name: String): Boolean = NAME.matches(name) && ".." !in name

  /**
   * L'offerta come la si tiene e la si mostra (US-3): le note tagliate a [MAX_NOTES_CHARS] e senza caratteri di controllo ne' di direzione del testo
   * (quelli che girano una riga o la nascondono); lo sha256 in minuscolo. Il resto non cambia: i controlli di [offerProblem] restano quelli.
   */
  fun cleanOffer(update: AvailableAppUpdate): AvailableAppUpdate =
    update.copy(changelog = cleanNotes(update.changelog), sha256 = update.sha256.trim().lowercase())

  /** Le note: prima il taglio, poi via i caratteri di controllo (tranne l'a capo) e quelli invisibili di direzione o di larghezza zero. */
  fun cleanNotes(raw: String): String {
    val cut = if (raw.length > MAX_NOTES_CHARS) raw.substring(0, MAX_NOTES_CHARS) else raw
    val out = StringBuilder(cut.length)
    for (ch in cut) {
      when {
        ch == '\n' -> out.append(ch)
        ch == '\t' -> out.append(' ')
        Character.isISOControl(ch) -> Unit
        ch in INVISIBLE -> Unit
        else -> out.append(ch)
      }
    }
    return out.toString()
  }

  /** I caratteri che cambiano la direzione o non si vedono: LRM/RLM, gli incorporamenti e gli isolamenti bidi, quelli a larghezza zero, il BOM. */
  private val INVISIBLE: Set<Char> = buildSet {
    addAll(listOf('\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u061C', '\u2060', '\uFEFF'))
    for (c in '\u202A'..'\u202E') add(c)
    for (c in '\u2066'..'\u2069') add(c)
  }

  /** Il file ricevuto contro lo sha256 dell'elenco: senza maiuscole ne' spazi, come l'engine. */
  fun checksumProblem(expected: String, actual: String): UpdateProblem? =
    if (expected.trim().equals(actual.trim(), ignoreCase = true) && actual.isNotBlank()) null else UpdateProblem.CHECKSUM_MISMATCH

  /**
   * Perche' l'APK scaricato ([archive], null se Android non lo legge) non va installato sopra quello [installed], o null. Nell'ordine: leggibile, **questa
   * app**, la versione annunciata ([expectedVersionName], se l'APK la dice), **versionCode strettamente maggiore** (niente ritorni indietro, nemmeno
   * alla stessa versione), **stessi certificati di firma**. Android rifiuterebbe comunque una firma diversa, ma solo dopo la sua finestra di conferma e
   * con parole sue: qui lo si dice prima, chiaro.
   */
  fun archiveProblem(installed: PackageFacts, archive: PackageFacts?, expectedVersionName: String? = null): UpdateProblem? = when {
    archive == null -> UpdateProblem.UNREADABLE
    archive.packageName != installed.packageName -> UpdateProblem.OTHER_APP
    expectedVersionName != null && !archive.versionName.isNullOrBlank() && archive.versionName != expectedVersionName -> UpdateProblem.WRONG_VERSION
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

  /** Quanto «Dopo» rimanda l'avviso: tre giorni. */
  const val SNOOZE_MILLIS: Long = 3L * 24 * 60 * 60 * 1000

  /** Il terzo «Dopo» di fila: l'avviso tace [QUIET_MILLIS], poi il conto riparte da capo. Mai spento per sempre (giro 2, decisione dell'orchestratore). */
  const val MAX_DECLINES: Int = 3

  /** Il silenzio dopo il terzo «Dopo»: trenta giorni. La voce in cima alle Impostazioni resta sempre. */
  const val QUIET_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

  /** Il rimando salvato dopo un «Dopo»: fino a quando, e quanti «Dopo» di fila ([MAX_DECLINES] = il silenzio lungo). */
  data class Snooze(val until: Long, val declines: Int)

  /**
   * L'avviso all'apertura (giro 1, UU-4 e US-3; giro 2): c'e' una versione da offrire e il rimando e' passato. Il rimando e' uno per tutte le versioni:
   * chi pubblica due nomi a giorni alterni non fa tornare l'avviso ogni giorno (rispondendo subito «Dopo»: avvisi ai giorni 0, 3 e 6, poi il 36; al piu'
   * tre avvisi ogni 36 giorni). Un rimando che scade oltre la sua durata da adesso ([SNOOZE_MILLIS], o [QUIET_MILLIS] dopo il terzo «Dopo») vuol dire
   * che l'orologio e' tornato indietro: vale come scaduto.
   */
  fun shouldPrompt(hasOffer: Boolean, now: Long, snoozedUntil: Long?, declines: Int): Boolean =
    hasOffer && snoozeOver(now, snoozedUntil, windowOf(declines))

  /**
   * Un «Dopo»: tre giorni, e al terzo di fila trenta. Finito il silenzio, il conto riparte (il primo «Dopo» dopo vale di nuovo tre giorni). Dentro il
   * silenzio un «Dopo» (sull'avviso del permesso o di un guasto, che compaiono lo stesso) non lo accorcia ne' lo allunga.
   */
  fun declined(now: Long, snoozedUntil: Long?, declines: Int): Snooze {
    val quiet = declines >= MAX_DECLINES
    if (quiet && snoozedUntil != null && !snoozeOver(now, snoozedUntil, QUIET_MILLIS)) return Snooze(snoozedUntil, declines)
    val count = if (quiet) 1 else declines + 1
    return if (count >= MAX_DECLINES) Snooze(now + QUIET_MILLIS, MAX_DECLINES) else Snooze(now + SNOOZE_MILLIS, count)
  }

  private fun windowOf(declines: Int): Long = if (declines >= MAX_DECLINES) QUIET_MILLIS else SNOOZE_MILLIS

  private fun snoozeOver(now: Long, snoozedUntil: Long?, window: Long): Boolean =
    snoozedUntil == null || now >= snoozedUntil || snoozedUntil - now > window

  /**
   * Dopo il passaggio ad Android, l'app tornata davanti senza un esito (giro 2, UU2-1): «Sto installando» per questo tempo, contato solo con l'app
   * davanti. Di solito Android chiude l'app prima (l'aggiornamento e' partito) o arriva «annullata»; se non arriva niente, la finestra si era chiusa
   * senza una scelta o non si era aperta: si torna a «Pronta» con «Installa».
   */
  const val OUTCOME_WAIT_MILLIS: Long = 15_000L

  /** Un tentativo salvato (il permesso chiesto, l'installazione partita) vale per la ripresa solo se e' di poco fa: un'ora. */
  const val ATTEMPT_TTL_MILLIS: Long = 60L * 60 * 1000

  fun attemptFresh(startedAt: Long, now: Long): Boolean = now >= startedAt && now - startedAt <= ATTEMPT_TTL_MILLIS
}
