package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.compute.UrgentWait
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pacchetto XF: il registro delle richieste "Analizza adesso" (XS-2: scadenza, rotazione, tetto; XS-4: l'ultima toccata per prima), la pulizia
 * fatta dal ciclo di lavoro (XS-2, XS-5) e l'unico criterio di "fatto" (XS-5). Ogni prova nomina la guardia e la mutazione che la fa cadere.
 */
class UrgentRegistryXfTest {

  @get:Rule
  val temp = TemporaryFolder()

  private var clock = 1_000_000L

  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  private fun media(n: Long) = MediaId(UUID(0L, n).toString())

  /** Ricette diverse per i vettori e per il testo, cosi' le porte finte non le confondono. */
  private val RECIPE = AiRecipe(version = 2, ocrVersion = 3)

  private fun registry(file: File = File(temp.root, AiUrgentFile.FILE)) = AiUrgentFile(file) { clock }

  // ---- XS-4: l'ultima toccata per prima ----------------------------------------------------------------------------------

  @Test
  fun lUltimaToccataEPrimaEUnNuovoToccoSuUnaGiaChiestaLaPortaInTesta() {
    val urgent = registry()
    urgent.add(key(1))
    urgent.add(key(2))
    urgent.add(key(3))
    assertThat(urgent.urgent()).containsExactly(key(3), key(2), key(1)).inOrder()
    // Un secondo tocco sul primo lo porta in testa.
    urgent.add(key(1))
    assertThat(urgent.urgent()).containsExactly(key(1), key(3), key(2)).inOrder()
    // Lo stato osservabile e' un insieme (l'ordine lo da' `urgent()`): ha le stesse richieste.
    assertThat(urgent.state.value).containsExactly(key(1), key(2), key(3))
  }

  // ---- XS-2: scadenza ----------------------------------------------------------------------------------------------------

  @Test
  fun unaRichiestaScadeDopoVentiquattroOre() {
    val urgent = registry()
    urgent.add(key(1))
    clock += AiUrgentFile.ASK_TTL_MILLIS - 1
    urgent.add(key(2))
    assertThat(urgent.urgent()).containsExactly(key(2), key(1)).inOrder()
    // Oltre le 24 ore la prima esce, la seconda (piu' giovane) resta.
    clock += 2
    assertThat(urgent.urgent()).containsExactly(key(2))
    assertThat(urgent.state.value).containsExactly(key(2))
    // E non torna dal file: la scadenza e' stata scritta.
    assertThat(registry().urgent()).containsExactly(key(2))
  }

  @Test
  fun leRichiesteDiPrimaSenzaOraValgonoChiesteAdesso() {
    val file = File(temp.root, AiUrgentFile.FILE)
    file.writeText("${key(7).hex}\n")
    val urgent = registry(file)
    assertThat(urgent.urgent()).containsExactly(key(7))
    clock += AiUrgentFile.ASK_TTL_MILLIS + 1
    assertThat(urgent.urgent()).isEmpty()
  }

  @Test
  fun ilMediaChiestoSiRicordaSulFileESulFormatoVecchioNo() {
    val file = File(temp.root, AiUrgentFile.FILE)
    val urgent = registry(file)
    urgent.add(key(1), media(5))
    urgent.add(key(2))
    val again = registry(file)
    assertThat(again.mediaOf(key(1))).isEqualTo(media(5))
    assertThat(again.mediaOf(key(2))).isNull()
    assertThat(again.urgent()).containsExactly(key(2), key(1)).inOrder()
  }

  // ---- XS-2: rotazione e tetto della priorita' ---------------------------------------------------------------------------

  /** La priorita' offerta al selettore delle unita' e' di [PRIORITY_KEYS] richieste, l'ultima chiesta prima. Mutazione: nessun tetto. */
  @Test
  fun laPrioritaHaUnTettoEPortaPrimaLUltimaChiesta() {
    val urgent = registry()
    for (i in 1..40) urgent.add(key(i))
    assertThat(urgent.priority.value).hasSize(AiUrgentFile.PRIORITY_KEYS)
    assertThat(urgent.priority.value.first()).isEqualTo(key(40))
    assertThat(urgent.priority.value).doesNotContain(key(1))
    // Le richieste restano tutte, anche quelle che non sono in priorita'.
    assertThat(urgent.urgent()).hasSize(40)
  }

  /** Le richieste incontrate ma non servibili passano dietro: a turno entrano le altre. Mutazione: nessuna rotazione (si guarda solo l'ordine di richiesta). */
  @Test
  fun leRichiesteNonServibiliPassanoDietroEIAltriFannoATurno() {
    val urgent = registry()
    for (i in 1..40) urgent.add(key(i))
    val first = urgent.priority.value.toList()
    first.forEach { urgent.waiting(it, UrgentWait.PREVIEW) }
    // Le prime in priorita' adesso sono altre: quelle che non hanno ancora aspettato.
    assertThat(urgent.priority.value.take(AiUrgentFile.PRIORITY_KEYS).intersect(first.toSet())).isEmpty()
    assertThat(urgent.attemptsOf(key(40))).isEqualTo(1)
    // Le richieste restano tutte: la rotazione non ne butta nessuna.
    assertThat(urgent.urgent()).hasSize(40)
    assertThat(urgent.waits.value[key(40)]).isEqualTo(UrgentWait.PREVIEW)
  }

  @Test
  fun unaRichiestaFattaDiNuovoRipartePulita() {
    val urgent = registry()
    urgent.add(key(1))
    urgent.waiting(key(1), UrgentWait.OTHER_DEVICE)
    assertThat(urgent.waitOf(key(1))).isEqualTo(UrgentWait.OTHER_DEVICE)
    urgent.add(key(1))
    assertThat(urgent.waitOf(key(1))).isNull()
    assertThat(urgent.attemptsOf(key(1))).isEqualTo(0)
  }

  @Test
  fun waitingDiUnaChiaveSconosciutaNonFaNiente() {
    val urgent = registry()
    urgent.waiting(key(9), UrgentWait.RETRY)
    assertThat(urgent.urgent()).isEmpty()
    assertThat(urgent.waits.value).isEmpty()
  }

  // ---- XS-2, XS-5: la pulizia del ciclo di lavoro ------------------------------------------------------------------------

  /** La pulizia toglie le richieste **finite** (criterio del calcolo, anche se le ha finite un altro dispositivo). Mutazione: nessuna rimozione delle finite. */
  @Test
  fun laPuliziaTogliLeRichiesteFinite() = runBlocking<Unit> {
    val urgent = registry()
    for (i in 1..4) urgent.add(key(i), media(i.toLong()))
    val sweep = AiUrgentSweep(urgent, isDone = { k, _ -> k == key(2) }, servable = { _, _ -> true })
    sweep.sweep(checkServable = false)
    assertThat(urgent.urgent()).containsExactly(key(4), key(3), key(1)).inOrder()
  }

  /**
   * La pulizia a inizio tappa toglie quelle che qui **non si possono piu' servire** (media cancellato, non supportato, non di questo dispositivo);
   * dove non si sa (`null`) non toglie niente. Mutazione: nessuna rimozione delle non servibili.
   */
  @Test
  fun laPuliziaTogliLeNonServibiliEMaiQuelleDiCuiNonSiSa() = runBlocking<Unit> {
    val urgent = registry()
    for (i in 1..5) urgent.add(key(i), media(i.toLong()))
    val gone = setOf(key(3))
    val unknown = setOf(key(5))
    val sweep = AiUrgentSweep(
      urgent,
      isDone = { _, _ -> false },
      servable = { k, _ -> if (k in gone) false else if (k in unknown) null else true },
    )
    // A fine tappa basta il criterio di "fatto": le non servibili non si ricontrollano.
    sweep.sweep(checkServable = false)
    assertThat(urgent.urgent()).hasSize(5)
    sweep.sweep(checkServable = true)
    assertThat(urgent.urgent()).containsExactly(key(5), key(4), key(2), key(1)).inOrder()
  }

  /** Le scadute escono anche dalla pulizia (e dal file). */
  @Test
  fun laPuliziaFaScadereLeVecchie() = runBlocking<Unit> {
    val urgent = registry()
    urgent.add(key(1), media(1))
    val sweep = AiUrgentSweep(urgent, isDone = { _, _ -> false }, servable = { _, _ -> true })
    clock += AiUrgentFile.ASK_TTL_MILLIS + 1
    sweep.sweep(checkServable = false)
    assertThat(urgent.urgent()).isEmpty()
    assertThat(registry().urgent()).isEmpty()
  }

  @Test
  fun unGuastoDiUnaPortaNonToglieLaRichiestaENonEsceMaiUnErrore() = runBlocking<Unit> {
    val urgent = registry()
    urgent.add(key(1), media(1))
    val sweep = AiUrgentSweep(urgent, isDone = { _, _ -> error("indice non aperto") }, servable = { _, _ -> error("catalogo non pronto") })
    sweep.sweep(checkServable = true)
    assertThat(urgent.urgent()).containsExactly(key(1))
  }

  // ---- XS-5: l'unico criterio di "fatto" ---------------------------------------------------------------------------------

  private fun check(
    saved: Map<Pair<ContentKey, Int>, Set<AiClass>> = emptyMap(),
    others: Map<Triple<String, ContentKey, Int>, Set<AiClass>> = emptyMap(),
    shares: List<String> = emptyList(),
    reading: Boolean,
  ) = AiDoneCheck(
    shares = { shares },
    savedFor = { k, r, _ -> saved[k to r].orEmpty() },
    doneByOthers = { s, k, r -> others[Triple(s, k, r)].orEmpty() },
    recipe = RECIPE,
    reading = { reading },
  )

  @Test
  fun unVettoreGiaPresenteConIlTestoDaLeggereNonChiudeLaRichiesta() = runBlocking<Unit> {
    val recipe = RECIPE
    val k = key(1)
    // Il vettore e' arrivato dal NAS (fatto da un altro dispositivo), qui la lettura del testo e' attiva e il testo non c'e'.
    val others = mapOf(Triple("comune", k, recipe.version) to setOf(AiClass.VEC))
    val reading = check(others = others, shares = listOf("comune"), reading = true)
    assertThat(reading.isDone(k)).isFalse()
    // Con la lettura spenta quel vettore basta.
    assertThat(check(others = others, shares = listOf("comune"), reading = false).isDone(k)).isTrue()
    // Quando il testo c'e' (qui o dagli altri) la richiesta e' finita.
    val withText = others + (Triple("comune", k, recipe.ocrVersion) to setOf(AiClass.OCR))
    assertThat(check(others = withText, shares = listOf("comune"), reading = true).isDone(k)).isTrue()
    val savedHere = mapOf((k to recipe.ocrVersion) to setOf(AiClass.OCR))
    assertThat(check(saved = savedHere, others = others, shares = listOf("comune"), reading = true).isDone(k)).isTrue()
  }

  @Test
  fun iMediaDelSoloTelefonoSiGiudicanoSuCioCheESalvatoQui() = runBlocking<Unit> {
    val recipe = RECIPE
    val k = key(2)
    val saved = mapOf((k to recipe.version) to setOf(AiClass.VEC))
    assertThat(check(saved = saved, reading = true).isDone(k)).isFalse()
    assertThat(check(saved = saved, reading = false).isDone(k)).isTrue()
    // Una ricetta diversa dalla corrente non conta (ricette correnti).
    val stale = mapOf((k to recipe.version - 1) to setOf(AiClass.VEC))
    assertThat(check(saved = stale, reading = false).isDone(k)).isFalse()
  }
}
