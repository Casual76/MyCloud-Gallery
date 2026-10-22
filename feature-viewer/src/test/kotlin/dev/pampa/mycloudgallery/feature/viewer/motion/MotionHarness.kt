package dev.pampa.mycloudgallery.feature.viewer.motion

import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * Il direttore delle foto in movimento con tutto finto (§5.2): il lettore che registra taglio, silenzio e partenze, il localizzatore con
 * intervalli e istanti scelti dal test, un pager "finto" che il test pilota con `PageSettle`. Niente Compose: la logica si prova a tempo virtuale.
 *
 * Gli id sono [count] media consecutivi, tutti foto in movimento del telefono (se [phone]) o del NAS; chi vuole altro cambia [kinds] e [refs].
 */
internal class MotionHarness(
  scope: CoroutineScope,
  count: Int = 60,
  phone: Boolean = true,
  nasMotion: NasMotionSource? = null,
  /** Falso: nessun localizzatore riconosce il formato (E-M16). */
  recognized: Boolean = true,
  /** Il video di ogni pagina: intervallo, istante della foto (2 s) e durata (4 s). */
  val found: MotionVideo.Found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = 2_000_000L, durationUs = 4_000_000L),
) {
  val ids: List<MediaId> = List(count) { MediaId(UUID(0L, it.toLong() + 1L).toString()) }
  val refs: MutableMap<MediaId, MediaRef> = ids.mapIndexed { index, id ->
    id to (if (phone) MediaRef.Phone("external_primary", 1_000L + index, "image/jpeg") else MediaRef.Nas(index + 1L, index + 100L, "image/jpeg"))
  }.toMap().toMutableMap()
  val kinds: MutableMap<MediaId, MediaKind> = ids.associateWith { MediaKind.MOTION_PHOTO }.toMutableMap()

  val player = FakeViewerPlayer()
  var created = 0
    private set
  val playback = ViewerPlayback {
    created++
    player
  }
  val locator = FakeMotionVideoLocator(if (recognized) refs.values.filterIsInstance<MediaRef.Phone>().associateWith { found } else emptyMap())
  val intents = FakePhoneMediaIntents()

  var settingOn = true
  var reducedMotion = false
  var powerSaving = false
  var photoAspect = 0f
  var zoomed = false
  val unrecognized: MutableList<MediaId> = mutableListOf()

  var services: ViewerServices = ViewerServices(
    motion = locator,
    intents = intents,
    motionAnimation = MotionAnimationSetting { settingOn },
    powerSaving = PowerSaving { powerSaving },
    nasMotion = nasMotion,
  )

  val host = MotionHost(
    services = { services },
    refOf = { refs[it] },
    kindOf = { kinds[it] },
    photoAspect = { photoAspect },
    reducedMotion = { reducedMotion },
    powerSaving = { powerSaving },
    onUnrecognized = { unrecognized += it },
    zoomed = { zoomed },
    neighborsOf = { id -> ids.indexOf(id).let { at -> listOf(at - 1, at + 1).filter { it in ids.indices }.map(ids::get) } },
  )

  val director = MotionDirector(playback, scope, host)

  fun uriOf(index: Int) = intents.uriOf(refs.getValue(ids[index]) as MediaRef.Phone)
}
