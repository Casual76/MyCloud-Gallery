package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
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
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextReader
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextSource
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting
import dev.pampa.mycloudgallery.feature.viewer.motion.PowerSaving
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource
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

  /**
   * La scala delle animazioni del sistema ("Rimuovi animazioni" = 0f), com'e' per la composizione di un telefono vero: sta nel contesto delle coroutine della composizione
   * (`MotionDurationScale`), da dove `rememberFluidMotionPolicy` la legge sotto il tema vero. Di serie 1f; un test la mette a zero prima di montare il viewer.
   */
  var systemAnimatorScale: Float = 1f

  private val systemMotionScale = object : MotionDurationScale {
    override val scaleFactor: Float get() = systemAnimatorScale
  }

  val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = systemMotionScale)

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
    motionAnimation: MotionAnimationSetting = MotionAnimationSetting.On,
    powerSaving: PowerSaving? = PowerSaving { false },
    nasMotion: NasMotionSource? = null,
    textSource: ViewerTextSource? = null,
    textReader: ViewerTextReader? = null,
    subjectPress: ViewerSubjectPress? = null,
    subjectCutter: dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerSubjectCutter? = null,
    cutoutSaver: dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaver? = null,
    albums: dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumActions = dev.pampa.mycloudgallery.feature.viewer.menu.NoViewerAlbumActions,
    // H2: le sezioni in piu' del pannello (la mappa, le persone, le copie) e dettagli scelti dal test.
    people: dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople? = null,
    placeMap: dev.pampa.mycloudgallery.feature.viewer.place.ViewerPlaceMap? = null,
    similarCopies: dev.pampa.mycloudgallery.core.library.similar.SimilarCopies? = null,
    detailsSource: dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource? = null,
  ): ViewerServices = ViewerServices(
    animated = animatedLoader,
    images = imageSource,
    thumbnails = FakeThumbnailLoader(),
    placeholders = FakePlaceholderCache(),
    details = detailsSource ?: if (withDetails) {
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
    motionAnimation = motionAnimation,
    powerSaving = powerSaving,
    nasMotion = nasMotion,
    textSource = textSource,
    textReader = textReader,
    subjectPress = subjectPress,
    subjectCutter = subjectCutter,
    cutoutSaver = cutoutSaver,
    albums = albums,
    people = people,
    placeMap = placeMap,
    similarCopies = similarCopies,
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
