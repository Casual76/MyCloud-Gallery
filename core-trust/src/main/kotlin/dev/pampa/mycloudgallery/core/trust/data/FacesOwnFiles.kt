package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// La pulizia dei PROPRI file dei volti (Fase 8, P3; docs/formato-nas.md §2.3 riga "Teste, segmenti e prenotazioni propri dei volti", A8;
// docs/fase8-progetto.md §2.4 "Pubblicazione"). Qui solo la POLITICA, pura, come `OwnFilesPolicy`: decide file per file se un file dei volti
// e' mio e se si puo' togliere. La esegue solo core-nasops (P8, nella stessa esecuzione di `OwnFilesCleaner`), mai core-trust ne' core-faces.
//
// Le regole sono quelle di M6 (ultime 3 teste, segmenti elencati da loro o nel diario, 7 giorni di grazia, la prenotazione piu' alta resta),
// **solo nella generazione corrente**: le cartelle di una generazione non corrente si tolgono intere con la regola del §9 punto 6-bis
// (`FacesNasEraser`, D31), mai file per file da qui. Un file messo da altri nella mia cartella non e' mio (la firma o il diario lo dicono).

/** Che cosa e' un file sotto `.mycloudgallery/faces/`, secondo il **posto** (nessun contenuto letto). */
sealed interface FacesOwnFile {
  val path: SharePath
  val generation: FaceGeneration

  /** `faces/<G>/<dispositivo>/head/<n>`. */
  data class Head(override val path: SharePath, override val generation: FaceGeneration, val deviceId: String, val version: Long) : FacesOwnFile

  /** `faces/<G>/<dispositivo>/seg/<impronta>`. */
  data class Segment(override val path: SharePath, override val generation: FaceGeneration, val deviceId: String, val hash: String) : FacesOwnFile

  /** `faces/<G>/leases/<impronta della risorsa>/<gruppo>/<numero>`, con il gruppo giusto per il numero. */
  data class LeaseGeneration(
    override val path: SharePath,
    override val generation: FaceGeneration,
    val resourceHash: String,
    val number: Long,
  ) : FacesOwnFile

  companion object {
    /** Riconosce un file dei volti dal percorso, con la forma esatta dei nomi; null per tutto il resto (nomi strani, altre cartelle). */
    fun classify(path: SharePath): FacesOwnFile? {
      val c = path.components
      if (c.size < 6 || c[0] != TrustFormat.ROOT_DIR || c[1] != NasLayout.FACES_DIR.name) return null
      val generation = FaceGeneration.parse(c[2]) ?: return null
      if (c[3] == NasLayout.FACES_LEASES) {
        if (c.size != 7 || !Hashes.isHashName(c[4])) return null
        val shard = NasLayout.parseVersionName(c[5]) ?: return null
        val number = NasLayout.parseVersionName(c[6]) ?: return null
        if (NasLayout.leaseShardOf(number) != shard) return null
        return LeaseGeneration(path, generation, c[4], number)
      }
      if (c.size != 6 || !TrustIds.isValid(c[3])) return null
      return when (c[4]) {
        "head" -> NasLayout.parseVersionName(c[5])?.let { Head(path, generation, c[3], it) }
        "seg" -> c[5].takeIf(Hashes::isHashName)?.let { Segment(path, generation, c[3], it) }
        else -> null
      }
    }
  }
}

/** Le prove su un file dei volti, raccolte da chi esegue la pulizia (firma verificata con `FacesDocs.decodeHead`, diario locale, data). */
sealed interface FacesOwnFileFacts {
  val file: FacesOwnFile

  /** [signedByMe]: la testa e' firmata da me (busta verificata); [ownVersions]: le mie teste valide della stessa generazione. */
  data class HeadFacts(override val file: FacesOwnFile.Head, val signedByMe: Boolean, val ownVersions: Set<Long>) : FacesOwnFileFacts

  /** [listedByRecentHeads]: elencato da una delle mie ultime teste; [inDiary]: pubblicato da me (diario del pubblicatore). */
  data class SegmentFacts(
    override val file: FacesOwnFile.Segment,
    val listedByRecentHeads: Boolean,
    val inDiary: Boolean,
    val modifiedAtNas: Long?,
  ) : FacesOwnFileFacts

  /** [signedByMe]: una prenotazione valida firmata da me; [highestNumber]: il numero piu' alto della stessa risorsa trovato adesso. */
  data class LeaseFacts(override val file: FacesOwnFile.LeaseGeneration, val signedByMe: Boolean, val highestNumber: Long) : FacesOwnFileFacts
}

/**
 * La politica dei propri file dei volti di [me] nella generazione corrente [current] (formato §2.3, M6, A8). Pura: nessun accesso al NAS,
 * nessun orologio del telefono. Un file di un'altra generazione resta (`NOT_MINE`): lo toglie, intera, la regola di D31.
 */
class FacesOwnFilesPolicy(private val me: String, private val current: FaceGeneration) {

  init {
    require(TrustIds.isValid(me)) { "Id di dispositivo non valido." }
  }

  fun decide(
    facts: FacesOwnFileFacts,
    nasNow: Long,
    keepHeads: Int = TrustLimits.OWN_FILES_KEEP_HEADS,
    graceMillis: Long = TrustLimits.OWN_FILES_GRACE_MILLIS,
  ): OwnFileDecision {
    require(keepHeads >= 1 && graceMillis >= 0) { "Parametri non validi." }
    // Il posto si ricontrolla dal percorso: le prove di chi chiama non bastano a renderlo mio.
    if (FacesOwnFile.classify(facts.file.path) != facts.file) return keep(OwnKeepReason.NOT_MINE)
    if (facts.file.generation != current) return keep(OwnKeepReason.NOT_MINE)
    return when (facts) {
      is FacesOwnFileFacts.HeadFacts -> head(facts, keepHeads)
      is FacesOwnFileFacts.SegmentFacts -> segment(facts, nasNow, graceMillis)
      is FacesOwnFileFacts.LeaseFacts -> lease(facts)
    }
  }

  /** Il piano di un giro: niente se [suspended] (congelamento, salto dell'ora), al massimo `MAX_OWN_FILES_PER_ROUND` file tolti. */
  fun plan(facts: List<FacesOwnFileFacts>, nasNow: Long, suspended: Boolean): OwnFilesPlan {
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

  private fun head(f: FacesOwnFileFacts.HeadFacts, keepHeads: Int): OwnFileDecision {
    if (f.file.deviceId != me) return keep(OwnKeepReason.NOT_MINE)
    if (!f.signedByMe) return keep(OwnKeepReason.NOT_SIGNED_BY_ME)
    val recent = (f.ownVersions + f.file.version).sortedDescending().take(keepHeads)
    return if (f.file.version in recent) keep(OwnKeepReason.RECENT_HEAD) else OwnFileDecision.Remove
  }

  private fun segment(f: FacesOwnFileFacts.SegmentFacts, nasNow: Long, graceMillis: Long): OwnFileDecision {
    if (f.file.deviceId != me) return keep(OwnKeepReason.NOT_MINE)
    if (f.listedByRecentHeads) return keep(OwnKeepReason.LISTED)
    if (!f.inDiary) return keep(OwnKeepReason.NOT_IN_DIARY)
    val modifiedAt = f.modifiedAtNas
    return when {
      modifiedAt == null -> keep(OwnKeepReason.AGE_UNKNOWN)
      modifiedAt > nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS -> keep(OwnKeepReason.TIME_IN_FUTURE)
      nasNow - modifiedAt < graceMillis -> keep(OwnKeepReason.TOO_RECENT)
      else -> OwnFileDecision.Remove
    }
  }

  private fun lease(f: FacesOwnFileFacts.LeaseFacts): OwnFileDecision {
    if (!f.signedByMe) return keep(OwnKeepReason.NOT_SIGNED_BY_ME)
    return if (f.file.number >= f.highestNumber) keep(OwnKeepReason.HIGHEST_GENERATION) else OwnFileDecision.Remove
  }

  private fun keep(reason: OwnKeepReason) = OwnFileDecision.Keep(reason)
}
