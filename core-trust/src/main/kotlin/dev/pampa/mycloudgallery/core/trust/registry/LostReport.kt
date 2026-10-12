package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * La segnalazione "dispositivo perso" (tipo 7, `docs/formato-nas.md` §5.9): il proprietario, da un altro
 * dispositivo approvato **dello stesso account** del NAS, dice che [targetId] e' perso (decisione 3(a)).
 *
 * Si pubblica in `family/reports/<target>/<n>` (serie numerata, primo nome libero) in **due** share:
 * - nell'area comune, dove la vede l'admin, che la converte nello stato `lost` del registro con un gesto suo;
 * - nell'area privata dell'account, dove solo chi ha la password di quell'account scrive: e' la **prova di
 *   possesso dell'account** (revisione B6). Il dispositivo perso, se si ricollega, rispetta solo la copia che
 *   trova nella **propria** area privata; la copia dell'area comune da sola non fa azzerare nessuno, cosi' un
 *   dispositivo che ha dichiarato l'account di un altro familiare non puo' far azzerare i suoi telefoni.
 */
data class LostReport(
  val familyId: String,
  val reporterId: String,
  val targetId: String,
  val nasUser: String,
  val reportedAt: Long,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(reporterId) && TrustIds.isValid(targetId), RejectReason.INVALID_FIELD, "segnalazione: id")
    ensure(reporterId != targetId, RejectReason.INVALID_FIELD, "segnalazione: un dispositivo non segnala se stesso")
    TextRules.nasAccount(nasUser)
  }

  fun sign(signer: Signer): ByteArray {
    require(TrustIds.deviceIdOf(signer.publicKey) == reporterId) { "La segnalazione va firmata da chi segnala." }
    return Envelope.seal(
      DocType.LOST_REPORT,
      jsonObject(
        "family" to familyId.json(),
        "device" to reporterId.json(),
        "target" to targetId.json(),
        "nasUser" to nasUser.json(),
        "reportedAt" to reportedAt.json(),
      ),
      signer,
    )
  }
}

/** Una segnalazione accettata: chi l'ha firmata e l'impronta del contenuto firmato. */
class AcceptedLostReport(val report: LostReport, val reporter: DeviceEntry, val contentHash: String)

/** L'esito della lettura di una serie di segnalazioni: la prima valida (se c'e') e i rifiuti (Diagnostica). */
class LostReportScan(val found: AcceptedLostReport?, val rejected: RejectionTally, val incomplete: Boolean)

object LostReports {

  /**
   * Accetta una segnalazione letta in `reports/<targetId>/…` solo se: busta e firma integre; firmata da un
   * dispositivo **approvato** nel registro accettato (non dismesso, revocato, perso, in attesa: `DeviceTrust`);
   * famiglia giusta e `device` uguale al firmatario; `target` uguale al posto; [targetId] nel registro;
   * `nasUser` uguale all'account del firmatario **e** a quello del dispositivo segnalato ([RejectReason.NOT_OWNER]);
   * ora non oltre lo scarto ammesso nel futuro. Non decide nulla: e' chi la usa a scegliere dove cercarla.
   */
  fun verify(bytes: ByteArray, registry: RegistryState, targetId: String, nasNow: Long): Verdict<AcceptedLostReport> = verdictOf {
    ensure(TrustIds.isValid(targetId), RejectReason.WRONG_LOCATION, "segnalazione: posto non valido")
    val trust = DeviceTrust(registry)
    val signed = trust.open(bytes, DocType.LOST_REPORT)
    val report = Fields.read(signed.envelope.payloadJson()) { f ->
      trust.checkCommon(f, signed)
      LostReport(
        familyId = registry.registry.familyId,
        reporterId = signed.signer.id,
        targetId = f.string("target", TrustIds.LENGTH),
        nasUser = f.string("nasUser", TrustLimits.MAX_NAS_ACCOUNT_CHARS),
        reportedAt = f.time("reportedAt"),
      )
    }
    ensure(report.targetId == targetId, RejectReason.WRONG_LOCATION, "segnalazione: non corrisponde al posto")
    val target = registry.registry.device(targetId)
      ?: violation(RejectReason.INVALID_FIELD, "segnalazione: dispositivo sconosciuto")
    ensure(
      TextRules.sameAccount(report.nasUser, signed.signer.nasUser) && TextRules.sameAccount(report.nasUser, target.nasUser),
      RejectReason.NOT_OWNER,
      "segnalazione: account diverso",
    )
    ensure(report.reportedAt <= nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS, RejectReason.INVALID_FIELD, "segnalazione: ora nel futuro")
    AcceptedLostReport(report, signed.signer, signed.envelope.contentHash)
  }

  /**
   * Legge per nome la serie `reports/<targetId>/` di [share] (1, 2, … fino al primo nome assente; spazzatura
   * scavalcata, al massimo [TrustLimits.MAX_JUNK_RUN] di fila) e si ferma alla prima segnalazione valida.
   * Leggere per nome e non dall'elenco: inondare la cartella non nasconde una segnalazione (M1).
   */
  fun find(share: NasFileSystem, registry: RegistryState, targetId: String, nasNow: Long): LostReportScan {
    val tally = RejectionTally()
    if (!TrustIds.isValid(targetId)) return LostReportScan(null, tally, incomplete = false)
    val maxBytes = Envelope.maxFileBytes(DocType.LOST_REPORT)
    var junkRun = 0
    for (version in 1..TrustLimits.MAX_SERIES_READS.toLong()) {
      if (junkRun > TrustLimits.MAX_JUNK_RUN) return LostReportScan(null, tally, incomplete = true)
      when (val read = share.read(NasLayout.reportFile(targetId, version), maxBytes)) {
        NasRead.NotFound -> return LostReportScan(null, tally, incomplete = false)
        NasRead.TooLarge -> {
          tally.add(RejectReason.TOO_LARGE)
          junkRun++
        }
        is NasRead.Data -> when (val verdict = verify(read.bytes, registry, targetId, nasNow)) {
          is Verdict.Accepted -> return LostReportScan(verdict.value, tally, incomplete = false)
          is Verdict.Rejected -> {
            tally.add(verdict.reason)
            junkRun++
          }
        }
      }
    }
    return LostReportScan(null, tally, incomplete = true)
  }

  /**
   * Pubblica [signed] (una segnalazione per [targetId]) al primo nome libero della serie di [share]. Se la
   * stessa segnalazione e' gia' pubblicata (stessi byte) vale come fatto. Null se non si trova posto.
   */
  fun publish(share: NasFileSystem, targetId: String, signed: ByteArray): SharePath? {
    val maxBytes = Envelope.maxFileBytes(DocType.LOST_REPORT)
    for (version in 1..TrustLimits.MAX_SERIES_READS.toLong()) {
      val path = NasLayout.reportFile(targetId, version)
      when (val read = share.read(path, maxBytes)) {
        NasRead.NotFound -> when (share.publishExclusive(path, signed)) {
          PublishResult.CREATED -> return path
          PublishResult.ALREADY_EXISTS -> if ((share.read(path, maxBytes) as? NasRead.Data)?.bytes?.contentEquals(signed) == true) return path
        }
        is NasRead.Data -> if (read.bytes.contentEquals(signed)) return path
        NasRead.TooLarge -> Unit
      }
    }
    return null
  }
}
