package dev.pampa.mycloudgallery.core.designsystem.motion

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Fase 10, P8 (D27, E-AC6, "Fatto quando": movimento ridotto su ogni animazione): la regola, verificata sul codice di tutta la galleria.
 *
 * - Le animazioni di Compose (`animate*AsState`, `Animatable`, `AnimatedVisibility`, le molle di `FluidMotion`) **seguono la scala dei tempi
 *   del sistema**: a zero ("Rimuovi animazioni", `ANIMATOR_DURATION_SCALE`) arrivano subito al valore finale. Quelle **decorative** o
 *   infinite (il luccichio del segnaposto, il velo del ritaglio, lo zoom della presentazione) in piu' leggono `LocalFluidMotionPolicy`.
 * - Cio' che Compose non scala, la camera della mappa (`animateCamera` di MapLibre), controlla da se' `ValueAnimator.areAnimatorsEnabled()`.
 *
 * Il test fa fallire un'animazione infinita che non conosce il movimento ridotto e una camera che vola con le animazioni rimosse.
 */
class ReducedMotionSourcesTest {

  private val root: File = File("..").canonicalFile

  private val sources: List<File> by lazy {
    val modules = root.listFiles { f -> f.isDirectory && (f.name.startsWith("feature-") || f.name.startsWith("core-") || f.name == "app") }.orEmpty()
    modules.flatMap { m -> File(m, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
  }

  private fun code(file: File): String = file.readLines().joinToString("\n") { it.substringBefore("//") }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("sorgenti da ${root.absolutePath}").that(sources.size).isGreaterThan(100)
  }

  @Test
  fun leAnimazioniInfiniteConosconoIlMovimentoRidotto() {
    val infinite = Regex("""rememberInfiniteTransition|infiniteRepeatable""")
    val aware = Regex("""educedMotion|LocalFluidMotionPolicy|allowDecorativeMotion""")
    val offenders = sources.filter { infinite.containsMatchIn(code(it)) && !aware.containsMatchIn(it.readText()) }.map { it.name }
    assertWithMessage("animazioni infinite senza il movimento ridotto: $offenders").that(offenders).isEmpty()
  }

  @Test
  fun laCameraDellaMappaNonVolaConLeAnimazioniRimosse() {
    val users = sources.filter { Regex("""\banimateCamera\(""").containsMatchIn(code(it)) }
    assertWithMessage("animateCamera fuori dalla superficie MapLibre: ${users.map { it.name }}").that(users.map { it.name }).containsExactly("MapLibreSurface.kt")
    assertWithMessage("MapLibreSurface.animateTo senza il controllo delle animazioni").that(code(users.single())).contains("areAnimatorsEnabled")
  }

  @Test
  fun nessunaAnimazioneIgnoraLaScalaDeiTempi() {
    // Una `tween` o una molla costruita a mano resta una durata: la scala del sistema la accorcia. Quello che la scala NON accorcia e'
    // un'animazione fuori da Compose (`ValueAnimator`, `ObjectAnimator`, `ViewPropertyAnimator`, `Handler.postDelayed` per muovere): vietata.
    val raw = Regex("""ObjectAnimator|ViewPropertyAnimator|\.animate\(\)\.|ValueAnimator\.of""")
    val offenders = sources.filter { raw.containsMatchIn(code(it)) }.map { it.name }
    assertWithMessage("animazioni fuori da Compose, che ignorano il movimento ridotto: $offenders").that(offenders).isEmpty()
  }
}
