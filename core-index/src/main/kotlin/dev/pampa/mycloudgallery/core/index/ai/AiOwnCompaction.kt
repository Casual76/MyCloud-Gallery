package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.publish.Chunking
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.Nas
import dev.pampa.mycloudgallery.core.index.publish.nasCall
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * R14 (revisione A): i corpi che questo dispositivo **sostiene ancora** per le proprie voci vive. :app la lega all'indice locale: un corpo
 * calcolato qui per quella share (dai propri byte, o da un'anteprima attestata in quella share e non di un dispositivo revocato, R5) e
 * ancora uguale a quello pubblicato ([digest]). Null = la voce si toglie alla compattazione dopo una revoca. Un'eccezione (indice non
 * aperto, disco) = non si sa: la compattazione non toglie niente e si riprova.
 */
fun interface AiOwnBodies {
  suspend fun body(share: String, key: AiKey, digest: String): ByteArray?
}

/** R14: quando ogni share e' stata compattata l'ultima volta (ora del NAS), fra un processo e l'altro. :app la tiene in un file. */
interface AiCompactionMemory {
  fun last(share: String): Long?

  fun done(share: String, atNasMillis: Long)

  companion object {
    /** Solo in memoria (i test, i montaggi senza file). */
    fun inMemory(): AiCompactionMemory = object : AiCompactionMemory {
      private val map = HashMap<String, Long>()

      override fun last(share: String): Long? = synchronized(map) { map[share.lowercase(Locale.ROOT)] }

      override fun done(share: String, atNasMillis: Long) {
        synchronized(map) { map[share.lowercase(Locale.ROOT)] = atNasMillis }
      }
    }
  }
}

/**
 * LA COMPATTAZIONE DELLE PROPRIE TESTE `ai` (R14, §2.3 "Compattazione dei propri segmenti `ai`"): la testa nuova elenca solo le voci vive
 * che sono ancora di un file della share (M3 **adesso**) e, dopo una revoca ([checkBodies]), che questo dispositivo sostiene ancora
 * ([AiOwnBodies]: niente di cio' che e' nato dalle anteprime del revocato, R5); i segmenti con voci morte (superate, tolte) si riscrivono con le
 * sole vive, quelli interamente vivi restano com'erano, quelli senza voci vive escono. Con troppi segmenti si uniscono anche quelli mezzi
 * vuoti. La compattazione di ogni giorno non guarda i corpi: un indice locale ricostruito da capo non deve svuotare la testa (il calcolo
 * rifa' e ripubblica cio' che manca).
 *
 * Chi legge applica la regola E-I17 (`AiShareSync`): le voci dei segmenti tolti restano finche' i nuovi non sono scaricati. I file dei
 * segmenti tolti restano sul NAS finche' la pulizia dei propri file (core-nasops) non li toglie: vedi `docs/sicurezza.md` L46.
 */
internal class AiOwnCompaction(
  private val identity: IndexIdentity,
  private val store: AiPublishStore,
  private val content: AiShareContent,
  private val bodies: AiOwnBodies?,
  private val counters: AiIndexCounters,
  private val io: CoroutineDispatcher,
) {
  /** Com'e' andata: [Done] con una testa nuova, [Nothing] se non c'era niente da togliere, [Failed] se il NAS non ha risposto. */
  sealed interface Result {
    class Done(val head: OwnHead) : Result

    data object Nothing : Result

    data object Failed : Result
  }

  private class Kept(val entry: AiLiveEntry, var bytes: ByteArray?)

  suspend fun compact(
    share: String,
    fs: NasFileSystem,
    registry: RegistryState,
    own: OwnHead,
    heads: AiHeadWriter,
    now: Long,
    mergeAll: Boolean,
    checkBodies: Boolean = true,
  ): Result {
    val live = store.liveAll(share)
    val owned = content.owned(share, live.mapTo(HashSet()) { it.key.contentKey })
    val kept = LinkedHashMap<AiKey, Kept>()
    val dropped = ArrayList<AiKey>()
    for (e in live) {
      if (e.key.contentKey !in owned) {
        dropped += e.key
        continue
      }
      val source = bodies
      if (source == null || !checkBodies) {
        kept[e.key] = Kept(e, null)
        continue
      }
      val bytes = try {
        source.body(share, e.key, e.digest)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        // Non si sa se il corpo c'e' (indice non aperto, disco): meglio non togliere niente.
        return Result.Failed
      }
      if (bytes == null || AiSegments.digest(bytes) != e.digest) dropped += e.key else kept[e.key] = Kept(e, bytes)
    }
    val bySegment = kept.values.groupBy { it.entry.segment }
    val small = TrustLimits.MAX_SEGMENT_ENTRIES / 2
    // Un segmento resta com'e' se ogni sua voce e' viva e tenuta (e, unendo, se non e' mezzo vuoto).
    val untouched = own.segments.filter { seg -> bySegment[seg.hash]?.size == seg.entries && !(mergeAll && seg.entries < small) }
    val untouchedHashes = untouched.mapTo(HashSet()) { it.hash }
    val rewrite = kept.values.filter { it.entry.segment !in untouchedHashes }
    if (dropped.isEmpty() && rewrite.isEmpty() && untouched.size == own.segments.size) return Result.Nothing
    // I corpi da riscrivere che non arrivano da [bodies]: si rileggono dai propri segmenti (verificati con l'impronta della testa).
    val missing = rewrite.filter { it.bytes == null }.groupBy { it.entry.segment }
    for ((hash, items) in missing) {
      val segment = own.segments.firstOrNull { it.hash == hash } ?: continue
      val read = when (val r = nasCall(io) { fs.read(NasLayout.aiSegment(identity.deviceId, hash), segment.segment.ref.size.toInt()) }) {
        is Nas.Ok -> r.value
        is Nas.Failed -> return Result.Failed
      }
      val decoded = (read as? NasRead.Data)?.let { AiSegments.decode(it.bytes, segment.segment.ref, items.first().entry.key.cls) }
      val entries = (decoded as? Verdict.Accepted)?.value?.entries.orEmpty().associateBy { it.contentKey }
      for (item in items) item.bytes = entries[item.entry.key.contentKey]?.takeIf { it.digest == item.entry.digest }?.bodyBytes
    }
    // Una voce di cui non si ritrova il corpo (segmento sparito dal NAS) esce anche lei.
    rewrite.filter { it.bytes == null }.forEach { dropped += it.entry.key }
    val writable = rewrite.filter { it.bytes != null }
    val fresh = ArrayList<OwnSegment>()
    val moved = ArrayList<AiLiveEntry>()
    for (cls in AiClass.entries) {
      val ofClass = writable.filter { it.entry.key.cls == cls }
        .sortedWith { a, b -> IndexKeys.compare(IndexKeys.content(a.entry.key.contentKey), IndexKeys.content(b.entry.key.contentKey)) }
      for (group in Chunking.split(ofClass) { it.bytes!!.size.toLong() }) {
        val encoded = AiSegments.encodeBodies(cls, group.map { it.entry.key.contentKey to it.bytes!! })
        when (val r = nasCall(io) { fs.publishExclusive(NasLayout.aiSegment(identity.deviceId, encoded.ref.hash), encoded.bytes) }) {
          is Nas.Ok -> Unit
          is Nas.Failed -> return Result.Failed
        }
        fresh += OwnSegment(encoded.classed, encoded.entries)
        group.forEach { moved += AiLiveEntry(it.entry.key, it.entry.recipe, it.entry.digest, encoded.ref.hash) }
      }
    }
    val listed = (untouched + fresh).distinctBy { it.hash }.sortedBy { it.hash }
    val head = when (val r = heads.publish(fs, registry, own.version, listed.map { it.segment }, now)) {
      is AiHeadWriter.Result.Done -> r
      is AiHeadWriter.Result.Failed -> return Result.Failed
    }
    val next = OwnHead(head.version, head.mark, now, listed)
    store.commit(share, AiPublishCommit(next, emptySet(), fresh, moved, dropped.distinct()))
    counters.compactions.incrementAndGet()
    counters.entriesDropped.addAndGet(dropped.distinct().size)
    return Result.Done(next)
  }
}
