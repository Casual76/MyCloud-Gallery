package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// La pulizia dei PROPRI file (docs/formato-nas.md §2.3, M6; accesa nella 4B). Qui solo la POLITICA, pura: decide file
// per file se un file e' proprio e se si puo' togliere. La cancellazione la esegue solo core-nasops (§9, punto 7), mai
// core-trust, che non ha operazioni distruttive (NasFileSystem: list, read, publishExclusive).
//
// Un file e' proprio solo se lo dicono INSIEME il posto e la prova: la firma per i file firmati (teste, prenotazioni),
// il diario locale per i segmenti e i temporanei. Un file messo da altri nella mia cartella non e' mio (E-S11).

/** Che cosa e' un file sotto `.mycloudgallery/`, secondo il **posto** (nessun contenuto letto). */
sealed interface OwnFile {
  val path: SharePath

  /** `devices/<id>/<tipo>/head/<n>`. */
  data class Head(override val path: SharePath, val deviceId: String, val kind: HeadKind, val version: Long) : OwnFile

  /** `devices/<id>/<tipo>/seg/<impronta>`. */
  data class Segment(override val path: SharePath, val deviceId: String, val kind: HeadKind, val hash: String) : OwnFile

  /** `leases/<impronta della risorsa>/<gruppo>/<generazione>`, con il gruppo giusto per la generazione. */
  data class LeaseGeneration(override val path: SharePath, val resourceHash: String, val generation: Long) : OwnFile

  /** `tmp/<id>-<resto>`: i temporanei della pubblicazione atomica e delle operazioni (4B). */
  data class Temp(override val path: SharePath, val deviceId: String) : OwnFile

  companion object {
    /**
     * Riconosce un file dal percorso, con la forma esatta dei nomi (§2.4): null per tutto il resto (registro, cestino,
     * kit, nomi strani). Il dispositivo nel percorso NON e' ancora una prova di proprieta'.
     */
    fun classify(path: SharePath): OwnFile? {
      val c = path.components
      if (c.size < 3 || c[0] != TrustFormat.ROOT_DIR) return null
      return when (c[1]) {
        "devices" -> {
          if (c.size != 6 || !TrustIds.isValid(c[2])) return null
          val kind = HeadKind.entries.firstOrNull { it.wire == c[3] } ?: return null
          when (c[4]) {
            "head" -> NasLayout.parseVersionName(c[5])?.let { Head(path, c[2], kind, it) }
            "seg" -> c[5].takeIf(Hashes::isHashName)?.let { Segment(path, c[2], kind, it) }
            else -> null
          }
        }
        "leases" -> {
          if (c.size != 5 || !Hashes.isHashName(c[2])) return null
          val shard = NasLayout.parseVersionName(c[3]) ?: return null
          val generation = NasLayout.parseVersionName(c[4]) ?: return null
          if (NasLayout.leaseShardOf(generation) != shard) return null
          LeaseGeneration(path, c[2], generation)
        }
        "tmp" -> {
          if (c.size != 3) return null
          val owner = c[2].substringBefore('-', missingDelimiterValue = "")
          if (!TrustIds.isValid(owner) || c[2].length <= owner.length + 1) return null
          Temp(path, owner)
        }
        else -> null
      }
    }
  }
}

/**
 * Le prove su un file, raccolte da chi esegue la pulizia (4B, `OwnFilesCleaner`): firma letta e verificata, diario
 * locale, data del file (ora del NAS, dall'elenco). Nessuna viene da un dato remoto "di fiducia".
 */
sealed interface OwnFileFacts {
  val file: OwnFile

  /**
   * @property signedByMe la testa e' firmata da questo dispositivo (verifica della busta, non il nome della cartella).
   * @property ownVersions le versioni di tutte le mie teste valide dello stesso tipo in questa share.
   */
  data class HeadFacts(override val file: OwnFile.Head, val signedByMe: Boolean, val ownVersions: Set<Long>) : OwnFileFacts

  /**
   * @property listedByRecentHeads elencato da una delle ultime [TrustLimits.OWN_FILES_KEEP_HEADS] teste proprie.
   * @property inDiary l'impronta e' fra i segmenti che questo dispositivo ha pubblicato (diario locale).
   * @property modifiedAtNas la data del file sul NAS (ora del NAS), se nota.
   */
  data class SegmentFacts(
    override val file: OwnFile.Segment,
    val listedByRecentHeads: Boolean,
    val inDiary: Boolean,
    val modifiedAtNas: Long?,
  ) : OwnFileFacts

  /**
   * @property signedByMe la generazione e' una prenotazione valida firmata da questo dispositivo.
   * @property highestGeneration la generazione piu' alta della stessa risorsa trovata adesso.
   */
  data class LeaseFacts(override val file: OwnFile.LeaseGeneration, val signedByMe: Boolean, val highestGeneration: Long) : OwnFileFacts

  /** @property citedByDiary il temporaneo e' citato dal diario locale delle operazioni (4B): non si tocca. */
  data class TempFacts(override val file: OwnFile.Temp, val modifiedAtNas: Long?, val citedByDiary: Boolean) : OwnFileFacts
}

/** Perche' un file resta. */
enum class OwnKeepReason {
  /** Il posto non e' di questo dispositivo, o il percorso non e' uno dei file della politica. */
  NOT_MINE,

  /** Il posto e' mio ma la firma no (un file messo da altri nella mia cartella, E-S11). */
  NOT_SIGNED_BY_ME,

  /** Una delle ultime teste proprie (sempre la piu' alta). */
  RECENT_HEAD,

  /** La generazione piu' alta della risorsa, anche se mia: chi legge trova l'ultimo stato e il numero non si ripete. */
  HIGHEST_GENERATION,

  /** Un segmento elencato da una delle ultime teste. */
  LISTED,

  /** Un segmento che il diario locale non conosce: forse non e' mio. */
  NOT_IN_DIARY,

  /** Citato dal diario delle operazioni. */
  CITED_BY_DIARY,

  /** Data del file sconosciuta: nel dubbio si tiene. */
  AGE_UNKNOWN,

  /** Data del file nel futuro rispetto all'ora del NAS (ora che salta, file manipolato): nel dubbio si tiene. */
  TIME_IN_FUTURE,

  /** Non ancora abbastanza vecchio. */
  TOO_RECENT,

  /** Pulizia sospesa (congelamento D10, salto dell'ora D32) o tetto del giro raggiunto. */
  SUSPENDED,
}

sealed interface OwnFileDecision {
  data object Remove : OwnFileDecision

  data class Keep(val reason: OwnKeepReason) : OwnFileDecision
}

/** Il piano di un giro: i file da togliere (al massimo [TrustLimits.MAX_OWN_FILES_PER_ROUND]) e perche' restano gli altri. */
class OwnFilesPlan(val remove: List<SharePath>, val kept: Map<OwnKeepReason, Int>) {
  override fun toString(): String = "OwnFilesPlan(remove=${remove.size}, kept=$kept)"
}

/**
 * La politica dei propri file di questo dispositivo ([me]), §2.3 M6. Pura: nessun accesso al NAS, nessun orologio del
 * telefono (l'ora e' quella del NAS, passata da chi chiama).
 */
class OwnFilesPolicy(private val me: String) {

  init {
    require(TrustIds.isValid(me)) { "Id di dispositivo non valido." }
  }

  /** Decide un file. Il posto si ricontrolla qui dal percorso: le prove di chi chiama non bastano a renderlo mio. */
  fun decide(
    facts: OwnFileFacts,
    nasNow: Long,
    keepHeads: Int = TrustLimits.OWN_FILES_KEEP_HEADS,
    graceMillis: Long = TrustLimits.OWN_FILES_GRACE_MILLIS,
  ): OwnFileDecision {
    require(keepHeads >= 1 && graceMillis >= 0) { "Parametri non validi." }
    if (OwnFile.classify(facts.file.path) != facts.file) return keep(OwnKeepReason.NOT_MINE)
    return when (facts) {
      is OwnFileFacts.HeadFacts -> head(facts, keepHeads)
      is OwnFileFacts.SegmentFacts -> segment(facts, nasNow, graceMillis)
      is OwnFileFacts.LeaseFacts -> lease(facts)
      is OwnFileFacts.TempFacts -> temp(facts, nasNow)
    }
  }

  /**
   * Il piano di un giro: niente se la pulizia e' [suspended] (congelamento, salto dell'ora), al massimo
   * [TrustLimits.MAX_OWN_FILES_PER_ROUND] file tolti; gli altri restano con il loro motivo.
   */
  fun plan(facts: List<OwnFileFacts>, nasNow: Long, suspended: Boolean): OwnFilesPlan {
    val remove = ArrayList<SharePath>()
    val kept = LinkedHashMap<OwnKeepReason, Int>()
    for (f in facts) {
      val decision = if (suspended) OwnFileDecision.Keep(OwnKeepReason.SUSPENDED) else decide(f, nasNow)
      when {
        decision is OwnFileDecision.Keep -> kept.merge(decision.reason, 1, Int::plus)
        remove.size >= TrustLimits.MAX_OWN_FILES_PER_ROUND -> kept.merge(OwnKeepReason.SUSPENDED, 1, Int::plus)
        else -> remove += f.file.path
      }
    }
    return OwnFilesPlan(remove, kept)
  }

  private fun head(f: OwnFileFacts.HeadFacts, keepHeads: Int): OwnFileDecision {
    if (f.file.deviceId != me) return keep(OwnKeepReason.NOT_MINE)
    if (!f.signedByMe) return keep(OwnKeepReason.NOT_SIGNED_BY_ME)
    val recent = (f.ownVersions + f.file.version).sortedDescending().take(keepHeads)
    return if (f.file.version in recent) keep(OwnKeepReason.RECENT_HEAD) else OwnFileDecision.Remove
  }

  private fun segment(f: OwnFileFacts.SegmentFacts, nasNow: Long, graceMillis: Long): OwnFileDecision {
    if (f.file.deviceId != me) return keep(OwnKeepReason.NOT_MINE)
    if (f.listedByRecentHeads) return keep(OwnKeepReason.LISTED)
    if (!f.inDiary) return keep(OwnKeepReason.NOT_IN_DIARY)
    return byAge(f.modifiedAtNas, nasNow, graceMillis)
  }

  private fun lease(f: OwnFileFacts.LeaseFacts): OwnFileDecision {
    if (!f.signedByMe) return keep(OwnKeepReason.NOT_SIGNED_BY_ME)
    return if (f.file.generation >= f.highestGeneration) keep(OwnKeepReason.HIGHEST_GENERATION) else OwnFileDecision.Remove
  }

  private fun temp(f: OwnFileFacts.TempFacts, nasNow: Long): OwnFileDecision {
    if (f.file.deviceId != me) return keep(OwnKeepReason.NOT_MINE)
    if (f.citedByDiary) return keep(OwnKeepReason.CITED_BY_DIARY)
    return byAge(f.modifiedAtNas, nasNow, TEMP_GRACE_MILLIS)
  }

  private fun byAge(modifiedAt: Long?, nasNow: Long, graceMillis: Long): OwnFileDecision = when {
    modifiedAt == null -> keep(OwnKeepReason.AGE_UNKNOWN)
    modifiedAt > nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS -> keep(OwnKeepReason.TIME_IN_FUTURE)
    nasNow - modifiedAt < graceMillis -> keep(OwnKeepReason.TOO_RECENT)
    else -> OwnFileDecision.Remove
  }

  private fun keep(reason: OwnKeepReason) = OwnFileDecision.Keep(reason)

  private companion object {
    /** I temporanei propri si tolgono dopo un giorno, come `NasHousekeeping` di core-nas (§2.3, tabella M6). */
    const val TEMP_GRACE_MILLIS: Long = 24L * 60 * 60 * 1000
  }
}
