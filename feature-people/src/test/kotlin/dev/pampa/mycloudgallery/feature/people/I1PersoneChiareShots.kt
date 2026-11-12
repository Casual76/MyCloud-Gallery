package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.list.NoPeopleListHandlers
import dev.pampa.mycloudgallery.feature.people.list.PeopleListScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase I, giro di correzione 1 (CU-2, CU-21, CS-5): «Tutte le persone» con i volti **quasi bianchi** (una foto sulla neve, un muro chiaro, il flash), il
 * caso peggiore per il nome bianco sopra la scheda. Le schede sono la scheda comune [dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCard],
 * con la sua sfumatura: qui si guarda che il nome si legga e che il velo non copra il volto.
 *
 * `gradlew :feature-people:recordRoborazziDebug --tests ...I1PersoneChiareShots` scrive in `docs/screenshots/beta-grafica/I1/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class I1PersoneChiareShots(private val dark: Boolean) : PeopleScreenTest() {

  /** Volti quasi bianchi: un fondo chiarissimo, appena diverso per volto, con una luce bianca. */
  private val lightCrops = object : PeopleCrops {
    override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap = nearWhite(face.box.x0.toLong(), sizePx)
  }

  @Test
  fun elencoCopertineChiare() {
    showAll(dark = dark) { PeopleListScreen(scenes.list(), lightCrops, NoPeopleListHandlers) }
    assertShows("elenco chiaro", "Anna")
    composeRule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/I1/persone-elenco-copertine-chiare-dopo-${if (dark) "scuro" else "chiaro"}-telefono.png")
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))

    /** Una foto quasi bianca di lato [px]: tre fondi chiarissimi (freddo, caldo, neutro) e un disco bianco. */
    fun nearWhite(seed: Long, px: Int): Bitmap {
      val side = px.coerceIn(1, 256)
      val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(BACKGROUNDS[Math.floorMod(seed, BACKGROUNDS.size.toLong()).toInt()].toInt())
      canvas.drawCircle(side * 0.55f, side * 0.4f, side * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
      return bitmap
    }

    private val BACKGROUNDS = longArrayOf(0xFFF3F6FA, 0xFFFAF7F1, 0xFFF4F5F3)
  }
}
