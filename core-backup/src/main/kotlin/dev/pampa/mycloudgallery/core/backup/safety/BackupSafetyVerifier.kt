package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.SafetyVerifier
import dev.pampa.mycloudgallery.core.backup.contract.VerifyReport
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import java.util.Locale
import kotlin.time.Duration

/**
 * La riverifica a rotazione (Q10 (a), D14): ogni copia privata riletta almeno una volta l'anno, dalla verifica piu' vecchia.
 *
 * Per giro si rileggono almeno [minBytesPerRun] byte (100 MB), o 1/[runsPerYear] dei byte protetti se li si conosce (circa 140 MB a
 * notte con 50 GB); oltre il budget si continua solo con le copie **scadute** (verificate da piu' di [maxAgeMillis], in ora del NAS),
 * finche' c'e' tempo: cosi' l'anno vale anche se i giri saltano qualche notte.
 */
data class RotationPolicy(
  val minBytesPerRun: Long = DEFAULT_MIN_BYTES_PER_RUN,
  val runsPerYear: Int = 365,
  val maxAgeMillis: Long = ONE_YEAR_MS,
) {
  init {
    require(minBytesPerRun >= 0 && runsPerYear > 0 && maxAgeMillis > 0) { "Rotazione non valida." }
  }

  /** Il budget di byte di un giro con [protectedBytes] byte protetti (null = non noti). */
  fun bytesPerRun(protectedBytes: Long?): Long {
    val share = if (protectedBytes == null || protectedBytes <= 0) 0L else (protectedBytes + runsPerYear - 1) / runsPerYear
    return maxOf(minBytesPerRun, share)
  }

  companion object {
    /** "Almeno 100 MB" (§2.3). */
    const val DEFAULT_MIN_BYTES_PER_RUN: Long = 100_000_000L

    const val ONE_YEAR_MS: Long = 365L * 24 * 60 * 60 * 1000
  }
}

/**
 * Le **verifiche** del backup (Fase 5A, P3; `docs/fase5-progetto.md` D14, §2.3, F4-D21, L28, E-B8). "Al sicuro" nasce solo da una
 * rilettura completa fatta **qui**: il verificatore rilegge ogni copia per intero (SHA-256 adesso, timbro uguale prima e dopo) e passa
 * al catalogo **lo SHA-256 letto** ([BackupCatalog.markVerified]), mai quello atteso: decide il catalogo confrontandolo con quello della
 * versione. Un hash dell'indice non diventa mai "al sicuro" (F4-D8).
 *
 * Gli ambiti ([VerifyScope]):
 * - [VerifyScope.Unverified]: le copie private mai rilette (`PRIVATE_COPY_UNVERIFIED`), per esempio i vecchi backup trovati dalla
 *   scansione (§2.11);
 * - [VerifyScope.FullHash]: la fase `FULL_HASH` che il catalogo mette in coda (F4-D21): l'hash calcolato qui entra con
 *   [BackupCatalog.recordFullHash] e risolve i gruppi `PROBABLE_DUPLICATE`; la copia, se e' privata, e' anche verificata. E' l'unico
 *   ambito che puo' leggere fuori dall'area privata (le copie che il catalogo mette in coda, per esempio nell'area comune): li' si
 *   impara solo l'hash, mai "al sicuro";
 * - [VerifyScope.Rotation]: la riverifica a rotazione ([RotationPolicy]);
 * - [VerifyScope.OnTheSpot]: la verifica sul momento della 5B (D21), con le stesse regole.
 *
 * Una copia che non torna perde "al sicuro" (il catalogo lo toglie con `markVerified` falso, E-B8). Se la versione e' ancora sul
 * telefono il backup la ricarica da solo (il media torna fra i candidati, con un nome nuovo se occupato: mai una sostituzione);
 * altrimenti il media riceve `VERIFY_FAILED` (segno `PROBLEM`, avviso nel riepilogo). Con un'altra copia privata verificata (E-L3) il
 * media resta al sicuro e niente si segnala.
 *
 * **Solo letture** sul NAS: nessuna primitiva di scrittura qui. Il cancello ferma il giro solo quando il backup non c'e' (famiglia non
 * pronta, area privata assente, "Backup in prova" D12); con la share congelata o le scritture ferme la rilettura continua: puo' solo
 * confermare byte giusti o togliere "al sicuro" a byte sbagliati, che e' proprio cio' che serve durante un incidente.
 *
 * Il budget e' di tempo (orologio monotono, D31); un file gia' iniziato si legge fino in fondo (lo SHA-256 non si riprende). Nessuna
 * eccezione per un errore del NAS: il giro si ferma con [RunStop.NAS].
 */
class BackupSafetyVerifier(
  reader: NasMediaReader,
  private val catalog: BackupCatalog,
  private val phone: PhoneBytes,
  private val gate: BackupGate,
  private val clock: BackupClock,
  /** L'area privata del proprietario (`LocalDeviceProfile.privateShare`), null = nessuna. */
  private val privateShare: () -> String?,
  private val rotation: RotationPolicy = RotationPolicy(),
  /** I byte protetti (copie private verificate), per la quota della rotazione; null = non noti (vale il minimo). */
  private val protectedBytes: suspend () -> Long? = { null },
  purpose: Purpose = Purpose.BACKGROUND,
  private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : SafetyVerifier {

  init {
    require(pageSize in 1..CandidateQuery.MAX_LIMIT) { "Pagina fuori misura." }
  }

  private val rereader = NasRereader(reader, purpose)

  private class Tally {
    var checked = 0
    var verified = 0
    var mismatched = 0
    var unreadable = 0
    var bytes = 0L

    fun report(stop: RunStop, problem: BackupProblemCode? = null) = VerifyReport(checked, verified, mismatched, unreadable, bytes, stop, problem)
  }

  private sealed interface Step {
    data object Next : Step

    class Stop(val stop: RunStop, val problem: BackupProblemCode? = null) : Step
  }

  override suspend fun verify(scope: VerifyScope, budget: Duration): VerifyReport {
    val tally = Tally()
    val share = privateShare() ?: return tally.report(RunStop.GATE)
    if (gate.stateOf(share) in CLOSED_FOR_VERIFY) return tally.report(RunStop.GATE)
    // Senza l'ora del NAS una copia non si segna "verificata adesso": niente si legge per niente.
    if (clock.nasNowMs() == null) return tally.report(RunStop.NAS)
    val deadline = clock.monotonicMs() + budget.inWholeMilliseconds.coerceAtLeast(0)
    val rotationBytes = if (scope == VerifyScope.Rotation) rotation.bytesPerRun(protectedBytes()) else Long.MAX_VALUE
    val seen = HashSet<String>()
    while (true) {
      if (clock.monotonicMs() >= deadline) return tally.report(RunStop.BUDGET)
      val limit = (seen.size + pageSize).coerceAtMost(CandidateQuery.MAX_LIMIT * 100)
      val page = catalog.verifyCandidates(scope, limit)
      val fresh = page.filter { keyOf(it.copy) !in seen }
      if (fresh.isEmpty()) return tally.report(RunStop.COMPLETE)
      for (c in fresh) {
        seen += keyOf(c.copy)
        if (clock.monotonicMs() >= deadline) return tally.report(RunStop.BUDGET)
        if (scope == VerifyScope.Rotation && tally.bytes >= rotationBytes && !overdue(c)) return tally.report(RunStop.COMPLETE)
        when (val step = verifyOne(scope, share, c, tally)) {
          Step.Next -> Unit
          is Step.Stop -> return tally.report(step.stop, step.problem)
        }
      }
      // Pagina non piena: non c'e' altro (le copie gia' viste restano viste; quelle verificate escono dall'ambito).
      if (page.size < limit) return tally.report(RunStop.COMPLETE)
    }
  }

  /** Rotazione: verificata da piu' di un anno (ora del NAS). */
  private fun overdue(c: CopyToVerify): Boolean {
    val at = c.verifiedAtMillis ?: return true
    val now = clock.nasNowMs() ?: return false
    return now - at >= rotation.maxAgeMillis
  }

  private suspend fun verifyOne(scope: VerifyScope, share: String, c: CopyToVerify, tally: Tally): Step {
    val copy = c.copy
    val isPrivate = SafetyCommon.sameShare(copy.share, share)
    val location = copy.locationId
    val versionId = copy.versionId
    // Fuori dall'area privata si legge solo per la fase FULL_HASH, e solo per imparare l'hash (mai "al sicuro").
    if (!isPrivate && scope != VerifyScope.FullHash) return Step.Next
    // Senza una posizione privata da segnare e senza una versione a cui dare l'hash, la lettura non servirebbe a niente.
    val canMark = isPrivate && location != null
    val learnsHash = versionId != null && (c.expected == null || scope == VerifyScope.FullHash)
    if (!canMark && !learnsHash) return Step.Next
    // Una copia privata senza hash noto e senza versione: niente con cui confrontarla.
    if (c.expected == null && !learnsHash) return Step.Next

    tally.checked++
    val read = rereader.reread(copy.share, copy.path, onBytes = { tally.bytes += it })
    val sha = when (read) {
      is Reread.Fail -> return Step.Stop(RunStop.NAS, SafetyCommon.problemFor(read.error))
      Reread.Missing, Reread.Busy, Reread.Changed, Reread.Different -> {
        // Assente, aperta dal PC o cambiata durante la lettura: questa volta non si sa. L'assenza la registra la scansione.
        tally.unreadable++
        return Step.Next
      }
      is Reread.Ok -> read.sha
    }
    val at = clock.nasNowMs() ?: return Step.Stop(RunStop.NAS)

    var conflict = false
    if (learnsHash) {
      when (catalog.recordFullHash(copy.mediaId, versionId!!, sha)) {
        HashRecord.RECORDED, HashRecord.SAME -> Unit
        // Il catalogo ha un altro hash per la versione: questi byte non sono la versione.
        HashRecord.CONFLICT -> conflict = true
        // La versione non c'e' piu' (unione, sostituzione): niente da segnare.
        HashRecord.UNKNOWN_VERSION -> return Step.Next
      }
    }
    if (!canMark) {
      if (conflict) tally.mismatched++ else tally.verified++
      return Step.Next
    }
    // Il catalogo confronta lo SHA-256 LETTO QUI con quello della versione: vero = verificata adesso, falso = fuori da "al sicuro".
    if (catalog.markVerified(location!!, sha, at)) {
      tally.verified++
      clearStaleProblem(copy)
    } else {
      tally.mismatched++
      afterMismatch(copy)
    }
    return Step.Next
  }

  /** Una copia che torna: se il media era segnato "copia non integra" e ora e' al sicuro, il segno non ha piu' ragione d'essere. */
  private suspend fun clearStaleProblem(copy: NasCopyRef) {
    val safety = catalog.mediaSafety(copy.mediaId) ?: return
    if (safety.problem == BackupProblemCode.VERIFY_FAILED && safety.status == SafetyStatus.SAFE) {
      catalog.markProblem(copy.mediaId, null)
    }
  }

  /**
   * E-B8: la copia non torna. Con un'altra copia privata verificata il media resta al sicuro (E-L3); se la versione e' ancora sul
   * telefono il backup la ricarica (il media e' di nuovo un candidato: niente segno, che lo toglierebbe dai candidati); altrimenti
   * `VERIFY_FAILED`.
   */
  private suspend fun afterMismatch(copy: NasCopyRef) {
    if (catalog.mediaSafety(copy.mediaId)?.status == SafetyStatus.SAFE) return
    val versionId = copy.versionId
    val ref = versionId?.let { catalog.phoneRefOf(copy.mediaId, it) }
    val phoneHasIt = ref != null && when (phone.lookup(ref)) {
      is PhoneLookup.Present -> true
      // Riga rinumerata o permessi ritirati: il telefono probabilmente ce l'ha; il motore la ritrova o dice perche' no.
      PhoneLookup.Stale, is PhoneLookup.Denied -> true
      PhoneLookup.Gone -> false
    }
    if (!phoneHasIt) catalog.markProblem(copy.mediaId, BackupProblemCode.VERIFY_FAILED)
  }

  private fun keyOf(copy: NasCopyRef): String =
    copy.locationId?.let { "l:${it.value}" } ?: ("p:" + copy.share.lowercase(Locale.ROOT) + ":" + copy.path.value.lowercase(Locale.ROOT))

  companion object {
    const val DEFAULT_PAGE_SIZE: Int = 50

    /**
     * Il backup non c'e': niente da verificare. Congelamento, scritture ferme, sola lettura e la pausa dell'utente ([BackupGateState.PAUSED],
     * R4 della revisione 5A: ferma chi scrive, non chi legge) non fermano una lettura.
     */
    private val CLOSED_FOR_VERIFY = setOf(BackupGateState.NOT_READY, BackupGateState.NO_PRIVATE_AREA, BackupGateState.TEST_ONLY)
  }
}
