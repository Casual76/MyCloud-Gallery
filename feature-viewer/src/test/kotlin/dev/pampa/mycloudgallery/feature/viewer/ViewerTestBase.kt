package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetcher
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.testing.FakeLocalMediaActions
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.metadata.testing.FakeExifDetailsReader
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.download.PhoneDownload
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tutti i thread di lavoro sono quello corrente: nei test niente attese su un IO vero. */
internal object ImmediateDispatchers : DispatcherProvider {
  override val main: CoroutineDispatcher = Dispatchers.Unconfined
  override val io: CoroutineDispatcher = Dispatchers.Unconfined
  override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

/**
 * La base delle prove del viewer su Robolectric: l'activity registrata a mano, la Libreria sintetica di 50 elementi, i
 * fake di immagini, lettore, azioni e azioni di sistema, e `show` che monta il viewer come lo monta `:app` (con un
 * `FluidGlassModalHost` sopra, per il menu e i fogli).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE)
abstract class ViewerTestBase {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  internal open val fixture: ViewerFixture = ViewerFixture()
  internal val images = PhoneAwareImages(mapper = { fixture.library.refAt((it.mediaStoreId - 1000L).toInt()) })
  val player = FakeViewerPlayer()
  var playersCreated = 0
  val intents = FakePhoneMediaIntents()
  val actions = FakeLocalMediaActions()
  val places = FakePlaces()
  val details = FakeMediaDetailsSource(emptyMap())
  var closed = 0

  /** I servizi di prova: tutto acceso. Chi vuole un viewer "povero" passa [actions] o [intents] a null. */
  internal fun services(
    actions: LocalMediaActions? = this.actions,
    intents: FakePhoneMediaIntents? = this.intents,
    motion: MotionVideoLocator = FakeMotionVideoLocator(),
    withDetails: Boolean = true,
    exif: ExifDetails = ExifDetails(
      make = "SAMSUNG",
      model = "Samsung Galaxy S25",
      lens = "Obiettivo principale",
      fNumber = 1.8,
      exposureTime = "1/250",
      iso = 50,
      focalLengthMm = 6.3,
      flashFired = false,
    ),
    confirmMobileData: ((dev.pampa.mycloudgallery.core.model.MediaRef) -> Unit)? = null,
    imageSource: dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource = images,
    animatedLoader: AnimatedDrawableLoader? = null,
    originals: OriginalFetcher? = null,
    nasVideo: NasVideoSource? = null,
    download: PhoneDownload? = null,
    backup: dev.pampa.mycloudgallery.feature.viewer.info.MediaBackupStates? = null,
    backupActions: dev.pampa.mycloudgallery.feature.viewer.menu.BackupMediaActions? = null,
    link: dev.pampa.mycloudgallery.core.imaging.viewer.NasLink? = null,
  ): ViewerServices = ViewerServices(
    animated = animatedLoader,
    images = imageSource,
    thumbnails = FakeThumbnailLoader(),
    placeholders = FakePlaceholderCache(),
    details = if (withDetails) {
      FakeMediaDetailsSource(
        (0 until fixture.library.count).associate { fixture.idAt(it) to sampleDetails(fixture.idAt(it), fixture.library.snapshot.kindAt(it)) },
      )
    } else {
      details
    },
    exif = FakeExifDetailsReader(exif),
    bytes = ViewerBytes { ByteArrayMediaBytes(ByteArray(16)) },
    motion = motion,
    places = places,
    geocoder = places,
    actions = actions,
    intents = intents,
    player = {
      playersCreated++
      player
    },
    onConfirmMobileData = confirmMobileData,
    originals = originals,
    nasVideo = nasVideo,
    download = download,
    dispatchers = ImmediateDispatchers,
    backup = backup,
    backupActions = backupActions,
    link = link,
  )

  internal fun show(
    request: ViewerRequest = fixture.request(),
    services: ViewerServices = services(),
    dark: Boolean = true,
  ) {
    composeRule.setContent {
      ViewerTestHost(dark = dark) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) }
    }
    composeRule.waitForIdle()
  }
}
