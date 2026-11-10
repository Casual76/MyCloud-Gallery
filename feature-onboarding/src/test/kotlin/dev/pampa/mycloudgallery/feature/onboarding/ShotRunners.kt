package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uno screenshot: una schermata in un suo stato. Il [slug] e' il nome del file (senza chiaro/scuro/telefono).
 *
 * @property step il passo evidenziato nella barra laterale (tablet).
 * @property progress il piano del percorso (la barra laterale lo elenca).
 * @property main lo stato principale di un passo o di una pagina: si fotografa anche sul tablet e con il testo
 *   grande. Gli stati d'errore e le varianti si fotografano su telefono, chiaro e scuro.
 * @property tall la pagina e' piu' lunga della finestra: si fotografa anche intera (una finestra alta).
 */
internal class Shot(
  val slug: String,
  val step: OnboardingStep,
  val progress: OnboardingProgress = foundProgress(step),
  val main: Boolean = false,
  val tall: Boolean = false,
  val content: @Composable () -> Unit,
) {
  override fun toString(): String = slug

  companion object {
    /** Il percorso "crea la famiglia" arrivato a [current]: i passi prima sono fatti, la ricerca delle cartelle e' saltata. */
    fun foundProgress(current: OnboardingStep): OnboardingProgress {
      val plan = OnboardingStep.entries.toList()
      val before = plan.takeWhile { it != current }
      val skipped = if (OnboardingStep.FOLDERS in before) setOf(OnboardingStep.FOLDERS) else emptySet()
      return OnboardingProgress.START.copy(
        path = OnboardingPath.FOUND_FAMILY,
        plan = plan,
        current = current,
        completed = (before - skipped).toSet(),
        skipped = skipped,
      )
    }

    /** Il percorso di un'altra strada ("entra", "recupera"): il piano e' quello del coordinatore finto. */
    fun pathProgress(path: OnboardingPath, current: OnboardingStep): OnboardingProgress {
      val plan = FakeOnboardingCoordinator.planFor(path)
      return OnboardingProgress.START.copy(
        path = path,
        plan = plan,
        current = current,
        completed = plan.takeWhile { it != current }.toSet(),
      )
    }
  }
}

/** Come si fotografa: chiaro, scuro, o chiaro con il testo grande. */
internal enum class Look(val slug: String, val dark: Boolean, val fontScale: Float?) {
  LIGHT("chiaro", false, null),
  DARK("scuro", true, null),
  LARGE_TEXT("chiaro", false, Devices.LARGE_TEXT),
}

private const val DIR = "../docs/screenshots/2-P6"

/** I PNG sono a mezza risoluzione (360 px di larghezza per il telefono): bastano a rivedere la grafica e tengono il repository leggero. */
private val HalfSize = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5))

/**
 * Fotografa ogni [Shot] nel suo look. `gradlew :feature-onboarding:recordRoborazziDebug` scrive i PNG in
 * `docs/screenshots/2-P6/`; nei normali `testDebugUnitTest` si verifica solo che ogni schermata componga (e che non
 * lanci niente, in nessuno stato).
 */
internal abstract class ShotRunner(
  private val shot: Shot,
  private val look: Look,
  private val device: String,
  private val suffix: String = "",
) : OnboardingComposeBase() {

  @Test
  fun scatta() {
    show(dark = look.dark, fontScale = look.fontScale) {
      OnboardingFrame(progress = shot.progress, sidebarStep = shot.step, ambientStep = shot.step) { shot.content() }
    }
    val large = if (look == Look.LARGE_TEXT) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/${shot.slug}-${look.slug.let { if (look.dark) "scuro" else "chiaro" }}-$device$large$suffix.png", HalfSize)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.SHOT_PHONE)
internal class PhoneShotTest(shot: Shot, look: Look) : ShotRunner(shot, look, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
    fun data(): List<Array<Any>> = ShotCatalog.all.flatMap { shot ->
      buildList {
        add(arrayOf<Any>(shot, Look.LIGHT))
        add(arrayOf<Any>(shot, Look.DARK))
        if (shot.main) add(arrayOf<Any>(shot, Look.LARGE_TEXT))
      }
    }
  }
}

/** Le pagine piu' lunghe della finestra, intere (finestra alta): per vedere anche cio' che sta sotto la piega. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.SHOT_PHONE_TALL)
internal class PhoneTallShotTest(shot: Shot, look: Look) : ShotRunner(shot, look, "telefono", suffix = "-intera") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
    fun data(): List<Array<Any>> = ShotCatalog.all.filter { it.tall }.flatMap { shot ->
      listOf(arrayOf<Any>(shot, Look.LIGHT), arrayOf<Any>(shot, Look.DARK))
    }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.TABLET)
internal class TabletShotTest(shot: Shot, look: Look) : ShotRunner(shot, look, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
    fun data(): List<Array<Any>> = ShotCatalog.all.filter { it.main }.flatMap { shot ->
      listOf(arrayOf<Any>(shot, Look.LIGHT), arrayOf<Any>(shot, Look.DARK))
    }
  }
}
