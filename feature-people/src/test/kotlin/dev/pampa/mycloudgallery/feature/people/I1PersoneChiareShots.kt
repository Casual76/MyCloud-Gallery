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

  /**
   * Volti disegnati con le proporzioni del ritaglio vero (`FacesCrops` di `:app`: un quadrato con il volto nel 67% centrale, margine 1,5): il viso
   * dal 17% all'83% dell'altezza, gli occhi al 43%, la bocca al 69%. Con la scheda 3:4 il quadrato riempie l'altezza: si vede dove cade la bocca
   * rispetto alla sfumatura sopra il nome (giro 1, CU-2: sotto i 140 dp la sfumatura corta).
   */
  private val drawnFaces = object : PeopleCrops {
    override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap = drawnFace(face.box.x0.toLong(), sizePx)
  }

  @Test
  fun elencoVoltiDisegnati() {
    showAll(dark = dark) { PeopleListScreen(scenes.list(), drawnFaces, NoPeopleListHandlers) }
    assertShows("elenco volti", "Anna")
    composeRule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/I1/persone-elenco-volti-ritaglio-dopo-${if (dark) "scuro" else "chiaro"}-telefono.png")
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))

    /** Un volto schematico nelle proporzioni del ritaglio: fondo, viso, occhi e bocca (rossa, perche' si veda sotto la sfumatura). */
    fun drawnFace(seed: Long, px: Int): Bitmap {
      val side = px.coerceIn(1, 256)
      val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(BACKGROUNDS[Math.floorMod(seed, BACKGROUNDS.size.toLong()).toInt()].toInt())
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      paint.color = 0xFFE8C4A8.toInt()
      canvas.drawOval(side * 0.25f, side * 0.17f, side * 0.75f, side * 0.83f, paint)
      paint.color = 0xFF3A2A20.toInt()
      canvas.drawCircle(side * 0.40f, side * 0.43f, side * 0.035f, paint)
      canvas.drawCircle(side * 0.60f, side * 0.43f, side * 0.035f, paint)
      paint.color = 0xFFC0392B.toInt()
      canvas.drawOval(side * 0.42f, side * 0.66f, side * 0.58f, side * 0.72f, paint)
      return bitmap
    }

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
