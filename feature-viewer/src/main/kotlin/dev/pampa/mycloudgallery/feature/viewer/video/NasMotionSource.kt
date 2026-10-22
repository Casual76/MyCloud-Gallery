package dev.pampa.mycloudgallery.feature.viewer.video

import android.net.Uri
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Il traffico verso il NAS della parte video delle foto in movimento (D51): `:app` lo registra come `VISIBLE_CONTENT` nel registro
 * del traffico (P11). [metered] dice se la rete era a consumo: letture sui dati mobili solo dopo la conferma dell'utente.
 */
fun interface MotionTraffic {
  fun record(bytes: Long, metered: Boolean)

  companion object {
    val None: MotionTraffic = MotionTraffic { _, _ -> }
  }
}

/** La parte video di una foto in movimento del NAS, gia' sul telefono (nella cache): un MP4 intero, letto da un file. */
class NasMotionClip internal constructor(
  internal val file: File,
  /** L'istante della foto (gia' risolto: campo dichiarato o meta' del video), se noto. */
  val stillUs: Long?,
  val durationUs: Long?,
) {
  /** L'intervallo e l'istante nello stesso modo dei media del telefono: l'intervallo e' tutto il file. */
  val found: MotionVideo.Found get() = MotionVideo.Found(ByteRange(0L, file.length()), stillUs, durationUs)

  /** Cosa dare al lettore: il file della cache, intero (un indirizzo `file://`, che la sorgente a contenuto sa aprire). */
  val source: PlaybackSource get() = PlaybackSource.Whole(Uri.fromFile(file))
}

/** Com'e' andata la ricerca e la lettura della parte video di una foto in movimento del NAS. */
sealed interface NasMotionResult {

  /** Pronta sul telefono; [fromCache] vero se non e' servito leggere dal NAS. */
  data class Ready(val clip: NasMotionClip, val fromCache: Boolean) : NasMotionResult

  /** Il video e' piu' grande del tetto di questa richiesta ([videoBytes]): non si legge. */
  data class TooBig(val videoBytes: Long) : NasMotionResult

  /** E-M16: il formato non e' riconosciuto. */
  data object Unrecognized : NasMotionResult

  /** La rete non lo permette (dati mobili da confermare, VPN spenta, nessuna rete) o il media non c'e' piu': [gate] dice perche'. Nessuna lettura. */
  data class Blocked(val gate: NasVideoGate) : NasMotionResult

  /** Il NAS non ha risposto o la lettura non e' riuscita. */
  data object Failed : NasMotionResult
}

/**
 * La parte video delle foto in movimento **solo-NAS** (D51, E-V15). Il file del NAS e' il JPEG intero con il video in coda: si
 * leggono **a intervalli** la testa e la coda (le stesse 256 KiB e 128 KiB del telefono) per trovare intervallo e istante, poi,
 * se il video non supera il tetto, solo i suoi byte, in blocchi da 1 MiB, nella cache `cacheDir/motion/` ([MotionCache], 64 MB).
 *
 * **Quando legge.** Mai prima di aver guardato la rete di adesso ([NasVideoSource.check], D-15): con i dati mobili non confermati
 * (`NeedsMobileData`), con la VPN spenta o senza rete non parte nessuna lettura. La lettura **automatica** (animazione d'apertura,
 * precarico) vuole di piu': solo `NasNetwork.OPEN` (Wi-Fi di casa o VPN su Wi-Fi), mai i dati mobili nemmeno se confermati per
 * il badge. Un file gia' in cache si usa sempre, senza rete.
 *
 * **La rete si ricontrolla durante la lettura** (I9): a ogni blocco da 1 MiB. Un passaggio dal Wi-Fi ai dati mobili con la VPN accesa a meta' lettura la ferma
 * al blocco dopo (il parziale si butta, l'esito e' `Blocked` come all'inizio) invece di portare fino a 16 MB senza conferma.
 *
 * Il lettore del video resta uno solo (`ViewerPlayback`): da qui escono solo byte.
 *
 * @param nasVideo il NAS del viewer: stesso collegamento, stesse conferme dei dati mobili, stesso lettore dei video.
 * @param cacheDir la cartella di cache dell'app (`Context.cacheDir`): la cache sta in `motion/`.
 * @param traffic dove si conta cio' che si e' letto.
 */
class NasMotionSource(
  private val nasVideo: NasVideoSource,
  cacheDir: File,
  private val traffic: MotionTraffic = MotionTraffic.None,
  capBytes: Long = MotionCache.DEFAULT_CAP_BYTES,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val chunkBytes: Int = CHUNK_BYTES,
) {

  private val cache = MotionCache(cacheDir, capBytes)
  private val capBytes: Long = capBytes

  /** La rete di adesso rispetto al NAS. */
  fun network(): NasNetwork = nasVideo.network()

  /** Il peso del file secondo il catalogo (la parte video e' meno), per la conferma sui dati mobili; null se il media non c'e' piu'. */
  suspend fun fileSize(ref: MediaRef.Nas): Long? = nasVideo.locate(ref)?.sizeBytes

  /** Il passaggio d'ingresso: puo' partire adesso? Non tocca il NAS. */
  suspend fun gate(ref: MediaRef.Nas): NasVideoGate = nasVideo.check(ref)

  /** L'azzeramento dell'app: la cache sparisce. */
  fun clear() {
    cache.clear()
  }

  /** La dimensione della cache, per le prove. */
  fun cachedBytes(): Long = cache.totalBytes()

  /**
   * Cerca e porta sul telefono la parte video di [ref].
   *
   * @param maxVideoBytes il tetto del video per questa richiesta (16 MB per l'animazione, il tetto della cache per il badge).
   * @param automatic una lettura che nessuno ha chiesto (animazione, precarico): solo con la rete aperta.
   */
  suspend fun fetch(ref: MediaRef.Nas, maxVideoBytes: Long, automatic: Boolean): NasMotionResult = withContext(dispatchers.io) {
    val location = nasVideo.locate(ref) ?: return@withContext NasMotionResult.Blocked(NasVideoGate.Missing)
    val prefix = prefixOf(ref, location)
    cache.find(prefix)?.let { file -> parse(file)?.let { return@withContext NasMotionResult.Ready(it, fromCache = true) } }
    networkGate(ref, automatic, location.sizeBytes)?.let { return@withContext NasMotionResult.Blocked(it) }
    try {
      read(ref, location, prefix, minOf(maxVideoBytes, capBytes), automatic)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (changed: NetworkChanged) {
      NasMotionResult.Blocked(changed.gate)
    } catch (_: NasIoException) {
      NasMotionResult.Failed
    } catch (_: IOException) {
      NasMotionResult.Failed
    }
  }

  /**
   * Se la rete di adesso non permette la lettura: [NasVideoGate.NeedsMobileData], `Unreachable` o `Missing`; null se si puo' leggere. Una lettura automatica
   * (animazione, precarico) vuole la rete aperta; una chiesta con il badge puo' essere a consumo se l'utente ha confermato per quel media.
   */
  private suspend fun networkGate(ref: MediaRef.Nas, automatic: Boolean, sizeBytes: Long): NasVideoGate? {
    val gate = nasVideo.check(ref)
    if (gate !is NasVideoGate.Ready) return gate
    if (automatic && nasVideo.network() != NasNetwork.OPEN) return NasVideoGate.NeedsMobileData(sizeBytes)
    return null
  }

  /** La rete e' cambiata a lettura iniziata (I9): [gate] dice cosa non permette piu'. */
  private class NetworkChanged(val gate: NasVideoGate) : IOException("La rete non permette piu' la lettura.")

  private suspend fun read(ref: MediaRef.Nas, location: NasMediaLocation, prefix: String, maxVideoBytes: Long, automatic: Boolean): NasMotionResult {
    val metered = nasVideo.network() == NasNetwork.METERED
    val file = when (val opened = nasVideo.openFile(location)) {
      is NasResult.Ok -> opened.value
      is NasResult.Err -> return NasMotionResult.Failed
    }
    file.use { remote ->
      val found = MotionLocating.locate(remote.size) { range ->
        remote.readRange(range).also { traffic.record(it.size.toLong(), metered) }
      }
      if (found !is MotionVideo.Found) return NasMotionResult.Unrecognized
      if (found.range.length > maxVideoBytes) return NasMotionResult.TooBig(found.range.length)
      val part = cache.begin(prefix)
      try {
        val buffer = ByteArray(chunkBytes)
        part.outputStream().use { out ->
          var position = found.range.start
          while (position < found.range.endExclusive) {
            currentCoroutineContext().ensureActive()
            // I9: la rete di adesso, a ogni blocco. Se non permette piu' di leggere il parziale si butta (sotto) e la lettura finisce qui.
            networkGate(ref, automatic, location.sizeBytes)?.let { throw NetworkChanged(it) }
            val want = minOf(buffer.size.toLong(), found.range.endExclusive - position).toInt()
            val got = remote.read(position, buffer, 0, want)
            // Il file si e' accorciato mentre lo leggevamo: non e' piu' quello che il catalogo conosce.
            if (got <= 0) throw IOException("File accorciato.")
            out.write(buffer, 0, got)
            traffic.record(got.toLong(), metered)
            position += got
          }
        }
      } catch (e: Throwable) {
        cache.discard(part)
        throw e
      }
      val name = prefix + "s" + (found.stillUs ?: "x") + "_d" + (found.durationUs ?: "x")
      val committed = cache.commit(part, name) ?: return NasMotionResult.Failed
      return NasMotionResult.Ready(NasMotionClip(committed, found.stillUs, found.durationUs), fromCache = false)
    }
  }

  /** Dal nome del file della cache: l'istante e la durata scritti da [read]. Un nome che non torna e' un file da buttare. */
  private fun parse(file: File): NasMotionClip? {
    val tail = file.name.removeSuffix(".mp4").substringAfterLast('_', "").takeIf { it.startsWith("d") } ?: return null
    val stillPart = file.name.removeSuffix(".mp4").substringBeforeLast("_d").substringAfterLast('_', "").takeIf { it.startsWith("s") } ?: return null
    if (file.length() <= 0L) return null
    return NasMotionClip(file, stillPart.drop(1).toLongOrNull(), tail.drop(1).toLongOrNull())
  }

  /** Il prefisso del file di [ref]: la posizione e il timbro (dimensione e data del NAS): un file cambiato dal PC non si riusa. */
  private fun prefixOf(ref: MediaRef.Nas, location: NasMediaLocation): String {
    val stamp = location.stamp
    return "m${ref.locationId}_${location.sizeBytes}_${stamp?.modifiedAtMillis ?: 0L}_"
  }

  companion object {
    /** Il blocco di lettura della parte video: 1 MiB, come i blocchi del resto del NAS. */
    const val CHUNK_BYTES: Int = 1024 * 1024

    /** L'animazione automatica solo se il video non supera i 16 MB (D51). */
    const val AUTOMATIC_MAX_BYTES: Long = 16L * 1024L * 1024L
  }
}
