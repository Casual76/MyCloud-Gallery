package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceTrust
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import dev.pampa.mycloudgallery.core.trust.verdictOf

/**
 * Il record firmato di un file messo nel cestino del NAS (`docs/formato-nas.md` §5.6). Serve a mostrare
 * "Eliminati di recente" sugli altri dispositivi e a sapere dove ripristinare. NON serve a decidere la
 * cancellazione definitiva: quella la decide [TrashPurgePolicy] dal diario LOCALE di chi ha cestinato.
 */
data class TrashRecord(
  val familyId: String,
  val deviceId: String,
  val share: String,
  val trashId: String,
  /** Dove stava il file: un percorso di media validato (mai dentro `.mycloudgallery`). */
  val originalPath: SharePath,
  val size: Long,
  val trashedAt: Long,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(deviceId) && TrustIds.isValid(trashId), RejectReason.INVALID_FIELD, "cestino: id")
    ShareNames.check(share)
    ensure(size >= 0, RejectReason.INVALID_FIELD, "cestino: dimensione")
  }

  fun sign(signer: Signer): ByteArray {
    require(TrustIds.deviceIdOf(signer.publicKey) == deviceId) { "Il record va firmato da chi ha cestinato." }
    return Envelope.seal(
      DocType.TRASH_RECORD,
      jsonObject(
        "family" to familyId.json(),
        "device" to deviceId.json(),
        "share" to share.json(),
        "trashId" to trashId.json(),
        "originalPath" to originalPath.value.json(),
        "size" to size.json(),
        "trashedAt" to trashedAt.json(),
      ),
      signer,
    )
  }

  companion object {
    /** Verifica un record letto in `trash/<deviceId>/rec/<trashId>` di [share]. */
    fun verify(bytes: ByteArray, registry: RegistryState, share: String, deviceId: String, trashId: String): Verdict<TrashRecord> = verdictOf {
      val trust = DeviceTrust(registry)
      val signed = trust.open(bytes, DocType.TRASH_RECORD)
      val record = Fields.read(signed.envelope.payloadJson()) { f ->
        trust.checkCommon(f, signed)
        TrashRecord(
          familyId = registry.registry.familyId,
          deviceId = signed.signer.id,
          share = f.string("share", TrustLimits.MAX_SHARE_NAME_CHARS),
          trashId = f.string("trashId", TrustIds.LENGTH),
          originalPath = SharePath.parseMediaPath(f.string("originalPath", TrustLimits.MAX_PATH_UTF8_BYTES)),
          size = f.long("size", 0..Long.MAX_VALUE),
          trashedAt = f.time("trashedAt"),
        )
      }
      ensure(
        record.share == share && record.deviceId == deviceId && record.trashId == trashId,
        RejectReason.WRONG_LOCATION,
        "cestino: non corrisponde al posto in cui si trova",
      )
      record
    }
  }
}

/**
 * Una voce del diario LOCALE del cestino: esiste solo perche' su QUESTO dispositivo l'utente ha confermato
 * l'eliminazione. Non si costruisce mai da un [TrashRecord] letto dal NAS.
 */
data class LocalTrashEntry(val share: String, val trashId: String, val size: Long, val trashedAtNas: Long)

/** Lo stato del file nel cestino, letto sul NAS al momento della pulizia. */
data class TrashItemInfo(val size: Long)

sealed interface PurgeDecision {
  /** Si puo' eliminare definitivamente ESATTAMENTE [path] (derivato dal diario locale, mai da dati remoti). */
  data class Purge(val path: SharePath) : PurgeDecision

  data class Keep(val why: KeepReason) : PurgeDecision

  data object AlreadyGone : PurgeDecision

  /** Tetto del giro raggiunto: il resto al prossimo giro (e oltre un certo numero si chiede all'utente). */
  data object Defer : PurgeDecision
}

enum class KeepReason { NO_LOCAL_ACTION, NOT_EXPIRED, CHANGED, IMPLAUSIBLE_TIME }

/** Il conto di un giro di pulizia, per i tetti (E-D4). */
class PurgeRound {
  var items: Int = 0
    private set
  var bytes: Long = 0
    private set

  internal fun fits(size: Long): Boolean =
    items < TrustLimits.MAX_PURGE_ITEMS_PER_ROUND && bytes + size <= TrustLimits.MAX_PURGE_BYTES_PER_ROUND

  internal fun take(size: Long) {
    items++
    bytes += size
  }
}

/**
 * La regola "nessuna azione distruttiva da dati remoti" applicata al cestino (E-S11, E-D4):
 *
 * - si elimina solo cio' che QUESTO dispositivo ha cestinato per azione locale confermata (diario locale);
 * - solo nel cestino di questo dispositivo (`trash/<io>/files/<id>`): il percorso e' derivato qui;
 * - solo se il file c'e' ancora con la stessa dimensione;
 * - solo dopo 30 giorni misurati sull'ora del NAS dal momento (locale) del cestino;
 * - al massimo [TrustLimits.MAX_PURGE_ITEMS_PER_ROUND] file e [TrustLimits.MAX_PURGE_BYTES_PER_ROUND] byte per giro.
 *
 * Un record remoto, vero o finto, non compare tra gli ingressi: non puo' far eliminare nulla.
 */
class TrashPurgePolicy(private val myDeviceId: String) {

  init {
    require(TrustIds.isValid(myDeviceId))
  }

  /** Il solo percorso che la pulizia puo' toccare per [entry]. */
  fun itemPath(entry: LocalTrashEntry): SharePath = NasLayout.trashItemFile(myDeviceId, entry.trashId)

  fun decide(entry: LocalTrashEntry?, item: TrashItemInfo?, nasNow: Long, round: PurgeRound): PurgeDecision {
    if (entry == null || !TrustIds.isValid(entry.trashId)) return PurgeDecision.Keep(KeepReason.NO_LOCAL_ACTION)
    if (item == null) return PurgeDecision.AlreadyGone
    if (item.size != entry.size) return PurgeDecision.Keep(KeepReason.CHANGED)
    if (entry.trashedAtNas > nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS) return PurgeDecision.Keep(KeepReason.IMPLAUSIBLE_TIME)
    if (nasNow < entry.trashedAtNas + TrustLimits.TRASH_RETENTION_MILLIS) return PurgeDecision.Keep(KeepReason.NOT_EXPIRED)
    if (!round.fits(item.size)) return PurgeDecision.Defer
    round.take(item.size)
    return PurgeDecision.Purge(itemPath(entry))
  }
}
