package dev.pampa.mycloudgallery.feature.viewer.motion

import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.ChangingLink
import dev.pampa.mycloudgallery.feature.viewer.video.MotionCache
import dev.pampa.mycloudgallery.feature.viewer.video.MotionTraffic
import dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Quanto traffico ha contato la parte video delle foto in movimento, e quanto a consumo (D51: `VISIBLE_CONTENT`). */
internal class TrafficRecorder : MotionTraffic {
  var bytes = 0L
    private set
  var meteredBytes = 0L
    private set

  override fun record(bytes: Long, metered: Boolean) {
    this.bytes += bytes
    if (metered) meteredBytes += bytes
  }
}

/** I thread di lavoro del test: uno solo, sul tempo virtuale (le attese del NAS finto contano come quelle vere, ma senza aspettare). */
internal class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
  override val main: CoroutineDispatcher = dispatcher
  override val io: CoroutineDispatcher = dispatcher
  override val default: CoroutineDispatcher = dispatcher
}

/**
 * Un NAS finto con la sua parte video (D51): il contatore delle letture ([reader]), la rete che il test cambia ([link]), le conferme dei dati mobili
 * ([consent]), il traffico contato ([traffic]) e la cache in una cartella temporanea. Le posizioni sono numeri: il media N sta in `Foto/mN.jpg`.
 */
internal class MotionNasRig(private val cacheDir: File, scheduler: TestCoroutineScheduler, cap: Long = MotionCache.DEFAULT_CAP_BYTES) {
  val nas = InMemoryNasMedia().also { it.addShare(SHARE) }
  val reader = ScriptedNasReader(nas.reader)
  val locations = HashMap<Long, NasMediaLocation?>()
  val link = ChangingLink(NasNetwork.OPEN)
  val consent = MobileDataConsent()
  val traffic = TrafficRecorder()
  val nasVideo = NasVideoSource(reader, { locations[it.locationId] }, link = link, consent = consent)
  val source = NasMotionSource(nasVideo, cacheDir, traffic, cap, TestDispatchers(StandardTestDispatcher(scheduler)))

  /** I file nella cache, per nome. */
  val cacheFiles: List<File> get() = File(cacheDir, MotionCache.DIRECTORY).listFiles()?.sortedBy { it.name }.orEmpty()

  /** Un media del NAS con questi byte (la foto in movimento intera: JPEG con XMP e video in coda). */
  fun add(locationId: Long, content: ByteArray): MediaRef.Nas = put(locationId, NasContent.of(content), content.size.toLong())

  /** Un media "grande": la testa vera e il corpo virtuale, fino a [size] byte. */
  fun addVirtual(locationId: Long, head: ByteArray, size: Long): MediaRef.Nas = put(locationId, NasContent.virtual(size, seed = locationId, head = head), size)

  private fun put(locationId: Long, content: NasContent, size: Long): MediaRef.Nas {
    val path = SharePath.of("Foto", "m$locationId.jpg")
    nas.putFile(SHARE, path.value, content)
    locations[locationId] = NasMediaLocation(SHARE, path, size, stamp = NasStamp(size, 1_000L + locationId))
    return MediaRef.Nas(locationId, contentKey = locationId + 100L, mime = "image/jpeg")
  }

  private companion object {
    const val SHARE = "test"
  }
}
