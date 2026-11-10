package dev.pampa.mycloudgallery.core.designsystem.design

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B1 della beta (lista dell'utente del 31/10): "il vetro che cambia misura o forma deve muoversi, non saltare". Qui, fotogramma per
 * fotogramma con l'orologio fermo:
 *  - l'etichetta che cambia testo passa per le larghezze in mezzo (la molla), e non trema scorrendo veloce: il testo va **a ritmo**
 *    (il primo cambio subito, poi al piu' uno ogni 200 ms, sempre l'ultimo) e la capsula **non si stringe** durante una raffica;
 *  - la capsula dei comandi si allarga quando un tasto compare (il "torna in fondo" scorrendo) passando per le misure in mezzo;
 *  - con le animazioni ridotte tutto cambia di colpo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryChromeMotionTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val title = mutableStateOf("Oggi")
  private val shownHistory = mutableListOf<String>()

  private fun width(tag: String): Float = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

  /**
   * La larghezza di [tag] a ogni fotogramma per [count] fotogrammi, con l'orologio fermo. Il cambio non parte nel primo fotogramma
   * (la composizione, poi l'effetto, poi il layout): per questo si guarda tutta la serie, non un fotogramma scelto.
   */
  private fun widths(tag: String, count: Int): List<Float> = List(count) {
    composeRule.mainClock.advanceTimeByFrame()
    width(tag)
  }

  /**
   * Avanza un fotogramma alla volta finche' [done] e' vero (al piu' [max]); restituisce quanti ne sono serviti, o -1. Dopo ogni
   * fotogramma si sincronizza con il thread principale (li' partono le notifiche degli stati scritti dalla prova), come fa una lettura
   * dell'albero della semantica.
   */
  private fun framesUntil(max: Int, done: () -> Boolean): Int {
    for (i in 1..max) {
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
      if (done()) return i
    }
    return -1
  }

  // --- Il ritmo ------------------------------------------------------------------------------------------------------------

  @Test
  fun ilTestoVaARitmoEMostraSempreLUltimo() {
    composeRule.setContent {
      val shown = rememberChromePaced(title.value, GalleryChromeDefaults.LabelPaceMillis)
      SideEffect { if (shownHistory.lastOrNull() != shown) shownHistory += shown }
      Text(shown)
    }
    composeRule.waitForIdle()
    composeRule.mainClock.autoAdvance = false

    composeRule.runOnIdle { title.value = "12 ottobre" }
    // Il primo cambio si mostra senza aspettare il ritmo (ben prima dei 200 ms).
    val first = framesUntil(FramesFor300Ms) { shownHistory.last() == "12 ottobre" }
    assertWithMessage("fotogrammi per il primo cambio: $first").that(first).isIn(1..FramesWithinPace)

    // Una raffica, un cambio a fotogramma: dentro il ritmo non si mostra tutto.
    for (name in listOf("11 ottobre", "10 ottobre", "9 ottobre")) {
      composeRule.runOnIdle { title.value = name }
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
    }
    framesUntil(FramesFor1500Ms) { false }
    assertThat(shownHistory.last()).isEqualTo("9 ottobre")
    assertWithMessage("mostrati: $shownHistory").that(shownHistory.containsAll(listOf("11 ottobre", "10 ottobre"))).isFalse()
  }

  // --- L'etichetta -------------------------------------------------------------------------------------------------------

  private fun showLabel(reduced: Boolean = false) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        val policy = if (reduced) FluidMotionPolicy(durationScale = 0f) else FluidMotionPolicy.Default
        CompositionLocalProvider(LocalFluidMotionPolicy provides policy) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            GalleryChromeLabel(
              title = title.value,
              subtitle = null,
              style = GalleryChromeStyles.adaptive(GalleryLegibility.adapt(GalleryChromeStyles.adaptivePalette().unknown, GalleryChromeStyles.adaptivePalette())),
              backdrop = null,
              modifier = Modifier.testTag(LabelTag),
              contentMoving = { moving.value },
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
  }

  // --- P2 (S25, 10/11): mentre il contenuto sotto si muove per un gesto la larghezza non si anima --------------------------

  /** Il contenuto sotto l'etichetta si muove per un gesto (pinch, scorrimento): `GalleryChromeLabel(contentMoving)`. */
  private val moving = mutableStateOf(false)

  /**
   * Durante un pinch o uno scorrimento l'etichetta prende **subito** la larghezza del testo nuovo: nessuna misura in mezzo, quindi nessuna
   * superficie di vetro di misura nuova a ogni fotogramma (sull'S25 erano 139 larghezze diverse in un pinch, e scacciavano le miniature
   * dalla cache della GPU).
   */
  @Test
  fun mentreIlContenutoSiMuoveLEtichettaPrendeSubitoLaLarghezzaNuova() {
    moving.value = true
    showLabel()
    val narrow = width(LabelTag)
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { title.value = "Mercoledi 12 ottobre 2026" }
    val series = widths(LabelTag, FramesFor1500Ms)
    val wide = series.last()
    assertWithMessage("larghezza: prima $narrow, dopo $wide").that(wide).isGreaterThan(narrow + MinGrowthPx)
    assertWithMessage("nessuna misura in mezzo mentre il contenuto si muove: $series").that(series.none { it > narrow + 1f && it < wide - 1f }).isTrue()
  }

  /** Finito il gesto la molla torna: un cambio da fermi (entrare in selezione, un filtro) passa di nuovo per le misure in mezzo. */
  @Test
  fun finitoIlGestoLaMollaTorna() {
    moving.value = true
    showLabel()
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { title.value = "Mercoledi 12 ottobre 2026" }
    widths(LabelTag, FramesFor250Ms)
    composeRule.runOnIdle { moving.value = false }
    // Il testo resta fermo oltre la soglia della raffica: il cambio dopo e' isolato.
    val wide = widths(LabelTag, FramesFor1500Ms).last()
    composeRule.runOnIdle { title.value = "Oggi" }
    val series = widths(LabelTag, FramesFor1500Ms)
    assertThat(series.last()).isLessThan(wide - MinGrowthPx)
    assertWithMessage("da fermi si stringe passando per le misure in mezzo: $series").that(series.count { it < wide - 1f && it > series.last() + 1f }).isAtLeast(MinFramesBetween)
  }

  /** Un gesto che comincia a meta' di una molla la chiude: la larghezza va subito a quella del testo, non resta a meta' strada. */
  @Test
  fun unGestoCheCominciaAMetaMollaPortaSubitoLaLarghezzaAlTesto() {
    showLabel()
    val narrow = width(LabelTag)
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { title.value = "Mercoledi 12 ottobre 2026" }
    val started = framesUntil(FramesFor300Ms) { width(LabelTag) > narrow + 1f }
    assertWithMessage("la molla e' partita (fotogrammi: $started)").that(started).isGreaterThan(0)
    val midway = width(LabelTag)
    composeRule.runOnIdle { moving.value = true }
    val closed = widths(LabelTag, 2).last()
    val wide = widths(LabelTag, FramesFor1500Ms).last()
    assertWithMessage("a meta' molla la larghezza era in viaggio: $narrow -> $midway -> $wide").that(midway).isLessThan(wide - 1f)
    assertWithMessage("due fotogrammi dopo l'inizio del gesto la larghezza e' gia' quella del testo: $closed contro $wide").that(closed).isWithin(1f).of(wide)
  }

  @Test
  fun lEtichettaCresceConLaMollaNonDiColpo() {
    showLabel()
    val narrow = width(LabelTag)
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { title.value = "Mercoledi 12 ottobre 2026" }
    val series = widths(LabelTag, FramesFor1500Ms)
    val wide = series.last()
    assertWithMessage("larghezza: prima $narrow, dopo $wide").that(wide).isGreaterThan(narrow + MinGrowthPx)
    val between = series.count { it > narrow + 1f && it < wide - 1f }
    assertWithMessage("fotogrammi fra le due misure ($narrow -> $wide): $series").that(between).isAtLeast(MinFramesBetween)
  }

  /**
   * GF-6 (review della beta): un cambio **isolato** (entrando in selezione il testo diventa «3 selezionati») non tiene la larghezza: la
   * capsula si stringe insieme al testo, con la molla, non 600 ms dopo.
   */
  @Test
  fun unCambioIsolatoStringeLaCapsulaInsiemeAlTesto() {
    title.value = "Mercoledi 12 ottobre 2026"
    showLabel()
    val wide = width(LabelTag)
    // Il testo e' fermo da piu' della soglia: il prossimo cambio non fa parte di una raffica.
    composeRule.mainClock.advanceTimeBy(GalleryChromeDefaults.LabelSettleMillis * 2)
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { title.value = "3 selezionati" }
    val start = widths(LabelTag, FramesFor160Ms)
    assertWithMessage("la capsula si stringe mentre il testo cambia (prima aspettava 600 ms): $wide -> $start").that(start.last()).isLessThan(wide - MinGrowthPx)
    val series = start + widths(LabelTag, FramesFor1500Ms)
    assertWithMessage("si stringe passando per le misure in mezzo: $series").that(series.count { it < wide - 1f && it > series.last() + 1f }).isAtLeast(MinFramesBetween)
  }

  /**
   * Dentro una **raffica** (un cambio a meno di 600 ms dal precedente: lo scorrimento veloce) la capsula cresce se serve ma non si
   * stringe; si stringe sul testo solo quando il testo e' fermo da 600 ms.
   */
  @Test
  fun scorrendoVeloceLEtichettaNonSiStringeFinoACheIlTestoEFermo() {
    title.value = "Oggi"
    showLabel()
    composeRule.mainClock.autoAdvance = false
    // Il primo cambio della raffica porta la capsula larga.
    composeRule.runOnIdle { title.value = "Mercoledi 12 ottobre 2026" }
    val wide = widths(LabelTag, FramesFor250Ms).last()
    // Poi date corte, una ogni 250 ms: la capsula non si stringe a ogni data (e finisce di crescere verso la piu' larga vista).
    var floor = wide
    for (name in listOf("Ieri", "Lunedi", "Oggi")) {
      composeRule.runOnIdle { title.value = name }
      val series = widths(LabelTag, FramesFor250Ms)
      assertWithMessage("durante la raffica ($name), da $floor: $series").that(series.min()).isAtLeast(floor - 1f)
      floor = series.last()
    }
    // A testo fermo (dopo la soglia) si stringe sulla misura del testo, con la molla.
    val settle = widths(LabelTag, FramesFor1500Ms + FramesFor1500Ms)
    assertThat(settle.last()).isLessThan(floor - MinGrowthPx)
    assertWithMessage("si stringe passando per le misure in mezzo: $settle").that(settle.count { it < floor - 1f && it > settle.last() + 1f }).isAtLeast(MinFramesBetween)
  }

  @Test
  fun conLeAnimazioniRidotteLEtichettaCambiaDiColpo() {
    showLabel(reduced = true)
    val narrow = width(LabelTag)
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { title.value = "Mercoledi 12 ottobre 2026" }
    val series = widths(LabelTag, FramesFor1500Ms)
    val wide = series.last()
    assertThat(wide).isGreaterThan(narrow + MinGrowthPx)
    assertWithMessage("nessuna misura in mezzo: $series").that(series.none { it > narrow + 1f && it < wide - 1f }).isTrue()
  }

  // --- La capsula --------------------------------------------------------------------------------------------------------

  @Test
  fun laCapsulaSiAllargaQuandoCompareUnTasto() {
    val extra = mutableStateOf(false)
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
          val palette = GalleryChromeStyles.adaptivePalette()
          GalleryChromeCapsule(
            style = GalleryChromeStyles.adaptive(GalleryLegibility.adapt(palette.unknown, palette)),
            backdrop = null,
            modifier = Modifier.testTag(CapsuleTag),
          ) {
            GalleryChromeSlot(visible = extra.value) { GalleryChromeIconButton(icon = Icons.Rounded.Add, contentDescription = "Oggi", onClick = {}) }
            GalleryChromeIconButton(icon = Icons.Rounded.Check, contentDescription = "Seleziona", onClick = {})
          }
        }
      }
    }
    composeRule.waitForIdle()
    val one = width(CapsuleTag)
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle { extra.value = true }
    val series = widths(CapsuleTag, FramesFor1500Ms)
    val two = series.last()
    assertWithMessage("capsula: $one -> $two").that(two).isGreaterThan(one + MinGrowthPx)
    assertWithMessage("fotogrammi fra le due misure: $series").that(series.count { it > one + 1f && it < two - 1f }).isAtLeast(MinFramesBetween)
  }

  private companion object {
    const val LabelTag = "etichetta"
    const val CapsuleTag = "capsula"
    const val MinGrowthPx = 20f

    /** Una molla di `FluidMotion.standard` (0,38 s) attraversa le misure in mezzo in piu' fotogrammi: almeno quattro. */
    const val MinFramesBetween = 4

    /** Il primo cambio deve arrivare prima che il ritmo (200 ms, 12 fotogrammi) finisca. */
    const val FramesWithinPace = 11

    /** La dissolvenza del testo dura 180 ms: dentro quel tempo la capsula deve essere gia' in viaggio. */
    const val FramesFor160Ms = 10
    const val FramesFor250Ms = 16
    const val FramesFor300Ms = 19
    const val FramesFor1500Ms = 94
  }
}
