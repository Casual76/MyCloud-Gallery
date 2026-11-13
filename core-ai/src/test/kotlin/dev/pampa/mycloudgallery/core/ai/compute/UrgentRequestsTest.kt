package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto XF: "Analizza adesso" **davvero primo** (XS-4) e **solo per i media chiesti** (XS-3), con le unita' del NAS finto (stessa semantica
 * di `LeasedWorkUnits`: parti ordinate per chiave, prenotazioni di un'ora, `keepAlive`) e i motori finti. Ogni prova nomina la guardia che
 * protegge e la mutazione che la fa cadere (registrate nel resoconto solo se eseguite).
 *
 * Le chiavi del NAS finto sono deterministiche e le unita' ordinano per chiave: l'ordine naturale e' quello di creazione. I byte che il motore
 * finto riceve portano il concetto e un suffisso ("FAKE:lotto 6 0006"): qui si confrontano i numeri dopo il prefisso.
 */
class UrgentRequestsTest {
  private val forever = Duration.INFINITE
  private val common = TestFamily.COMMON

  private fun numbersOf(order: List<String>, prefix: String): List<Int> =
    order.mapNotNull { it.removePrefix("FAKE:$prefix ").substringBefore(' ').toIntOrNull() }

  /** XS-4 (richiesta sopraggiunta): una richiesta che arriva mentre il calcolo lavora riordina **solo** cio' che non e' ancora stato lavorato. */
  @Test
  fun unaRichiestaArrivataDuranteIlLottoRiordinaSoloIlNonLavorato() = runTest {
    val f = TestFamily()
    val keys = (1..6).map { f.nas.add(common, concepts = "lotto $it") }
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 2, reducedBatch = 1))
    // Arriva a meta' del primo lotto (dopo il primo media): l'ultimo dell'unita' viene chiesto.
    phone.images.onImage = { n -> if (n == 1) phone.urgentKeys += keys.last() }

    phone.engine.run(AiScope.Share(common), forever)

    // Il primo lotto (1, 2) e' gia' passato e non si tocca; al confine il resto e' riordinato: 6 davanti a 3, 4, 5.
    assertThat(numbersOf(phone.images.order, "lotto")).containsExactly(1, 2, 6, 3, 4, 5).inOrder()
    assertThat(phone.urgentKeys).isEmpty()
  }

  /** XS-4 (due tocchi): fra piu' richieste **l'ultima toccata viene prima**; il resto nell'ordine di sempre. */
  @Test
  fun dueToccchiSuMediaDiversiLUltimoToccatoVienePrima() = runTest {
    val f = TestFamily()
    val keys = (1..6).map { f.nas.add(common, concepts = "tocchi $it") }
    val phone = f.anna()
    phone.urgentKeys += keys[1] // il 2, toccato per primo
    phone.urgentKeys += keys[4] // il 5, toccato per ultimo

    phone.engine.run(AiScope.Share(common), forever)

    assertThat(numbersOf(phone.images.order, "tocchi")).containsExactly(5, 2, 1, 3, 4, 6).inOrder()
    assertThat(phone.urgentKeys).isEmpty()
  }

  /** XS-4 (oltre il limite): un candidato chiesto fuori dai primi della lista locale entra comunque, e va per primo. */
  @Test
  fun ilCandidatoChiestoOltreIlLimiteEntraComunque() = runTest {
    val f = TestFamily()
    val phone = f.anna()
    phone.restart(AiComputeConfig(localBatchLimit = 2))
    val keys = (1..5).map { i ->
      val key = f.nas.newKey()
      phone.phoneMedia[key] = MediaKind.PHOTO to "oltre $i"
      key
    }
    phone.urgentKeys += keys[4]

    phone.engine.run(AiScope.PhoneOnly, forever)

    // La lista e' limitata a due (1 e 2) come sempre; il chiesto (5) si aggiunge e passa per primo. Il 3 e il 4 restano per un altro giro.
    assertThat(numbersOf(phone.images.order, "oltre")).containsExactly(5, 1, 2).inOrder()
    assertThat(phone.urgentKeys).isEmpty()
  }

  /**
   * XS2-4: una richiesta **sopraggiunta** durante il giro per un candidato **fuori dalla lista** limitata entra al confine del lotto dopo, senza rifare quelli gia'
   * lavorati. Mutazione: non rileggere i candidati al cambio delle richieste.
   */
  @Test
  fun unaRichiestaOltreIlLimiteArrivataDuranteIlLottoEntraAlConfineDopo() = runTest {
    val f = TestFamily()
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 1, reducedBatch = 1, localBatchLimit = 2))
    val keys = (1..5).map { i ->
      val key = f.nas.newKey()
      phone.phoneMedia[key] = MediaKind.PHOTO to "dopo $i"
      key
    }
    // La richiesta per il quinto (fuori dai primi due della lista) arriva durante il primo lotto, non prima del giro.
    phone.images.onImage = { n -> if (n == 1) phone.urgentKeys += keys[4] }

    phone.engine.run(AiScope.PhoneOnly, forever)

    // Il primo lotto (il 1) era gia' passato; al confine il 5 entra davanti al 2 (che era in lista, non ancora lavorato); niente si rifa'. Il resto della coda
    // (3 e 4) segue nel giro dopo: qui conta l'inizio e che nessun media si ripeta.
    val order = numbersOf(phone.images.order, "dopo")
    assertThat(order.take(3)).containsExactly(1, 5, 2).inOrder()
    assertThat(order).containsNoDuplicates()
    assertThat(phone.embedded).isEqualTo(order.size)
    assertThat(phone.urgentKeys).isEmpty()
  }

  /**
   * XS3-1 (equita' dell'ordinario, via locale): con richieste **nuove a ogni confine di lotto** le richieste davanti non rinviano l'ordinario fino al budget. Dopo due lotti
   * di soli media chiesti il lotto dopo e' ordinario: fra i primi sette media lavorati ce ne sono almeno due **mai chiesti** (oltre al primo, lavorato prima di ogni richiesta).
   * Garanzia: almeno un lotto ogni tre. Mutazione: ordinare sempre le richieste davanti (`orderedFair` che restituisce `ordered`).
   */
  @Test
  fun conRichiesteNuoveAOgniConfineUnLottoOgniTreVaAllOrdinarioSuTelefono() = runTest {
    val f = TestFamily()
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 1, reducedBatch = 1, localBatchLimit = 20))
    val keys = (1..10).map { i ->
      val key = f.nas.newKey()
      phone.phoneMedia[key] = MediaKind.PHOTO to "dopo $i"
      key
    }
    // A ogni media lavorato (i primi sei) arriva una richiesta nuova, per un media ancora da fare: dal decimo in giu'. Mai chiesti: 1, 2, 3 e 4.
    phone.images.onImage = { n -> if (n in 1..6) phone.urgentKeys += keys[10 - n] }

    phone.engine.run(AiScope.PhoneOnly, forever)

    val order = numbersOf(phone.images.order, "dopo")
    assertThat(order).containsNoDuplicates()
    assertThat(order.take(7).count { it in 2..4 }).isAtLeast(2)
  }

  /** Lo stesso, **dentro un'unita'** del NAS: i media di una stessa parte (fino a 200) non aspettano il budget per colpa di richieste che arrivano a ogni lotto. */
  @Test
  fun conRichiesteNuoveAOgniConfineUnLottoOgniTreVaAllOrdinarioInUnUnita() = runTest {
    val f = TestFamily()
    val keys = (1..10).map { f.nas.add(common, concepts = "unita $it") }
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 1, reducedBatch = 1))
    phone.images.onImage = { n -> if (n in 1..6) phone.urgentKeys += keys[10 - n] }

    phone.engine.run(AiScope.Share(common), forever)

    val order = numbersOf(phone.images.order, "unita")
    assertThat(order).containsNoDuplicates()
    assertThat(order.take(7).count { it in 2..4 }).isAtLeast(2)
  }

  /**
   * XS2-2: un'unita' in cui ogni media e' rimasto in attesa (l'anteprima non c'e') lo dice al selettore (`STALLED`), un'unita' che ha dato lavoro no. Mutazione:
   * chiudere sempre con `Completed`.
   */
  @Test
  fun unUnitaSenzaLavoroLoDiceAlSelettore() = runTest {
    val f = TestFamily()
    val waiting = f.nas.add(common, month = "2024-03", concepts = "senza", preview = false)
    f.nas.add(common, month = "2024-02", concepts = "con")
    val phone = f.anna()
    phone.urgentKeys += waiting

    phone.engine.run(AiScope.Share(common), forever)

    val byMonth = phone.units.finishes.associate { (key, outcome) -> key.month to outcome }
    assertThat(byMonth["2024-03"]).isEqualTo(UnitOutcome.Interrupted(UnitOutcome.Interrupted.STALLED))
    assertThat(byMonth["2024-02"]).isInstanceOf(UnitOutcome.Completed::class.java)
    assertThat((byMonth["2024-02"] as UnitOutcome.Completed).prepared).isEqualTo(1)
  }

  /** XS-4 (fra ambiti): l'ambito che contiene un media chiesto si serve per primo; senza richieste l'ordine e' quello di sempre. */
  @Test
  fun lAmbitoCheContieneUnMediaChiestoVieneServitoPrimaDegliAltri() = runTest {
    val f = TestFamily()
    f.nas.add(TestFamily.ANNA_PRIVATE, concepts = "privata")
    f.nas.add(common, concepts = "comune")
    val phone = f.anna()
    val onPhone = f.nas.newKey().also { phone.phoneMedia[it] = MediaKind.PHOTO to "telefono" }

    fun scopes(results: List<Pair<AiScope, *>>) = results.map { (scope, _) -> (scope as? AiScope.Share)?.share ?: "telefono" }

    // Senza richieste: area privata, comune, telefono (D14), come sempre.
    val plain = phone.engine.runAll(forever)
    assertThat(scopes(plain)).containsExactly(TestFamily.ANNA_PRIVATE, common, "telefono").inOrder()

    // Con un media del solo telefono chiesto: il telefono per primo. Gli stessi cancelli: le due aree sono quelle di questo dispositivo.
    val phone2 = f.anna("telefono-anna-due")
    val asked = f.nas.newKey().also { phone2.phoneMedia[it] = MediaKind.PHOTO to "telefono due" }
    f.nas.add(TestFamily.ANNA_PRIVATE, concepts = "privata due")
    phone2.urgentKeys += asked
    val first = phone2.engine.runAll(forever)
    assertThat(scopes(first).first()).isEqualTo("telefono")
    assertThat(phone2.images.order.first()).contains("telefono due")
    assertThat(onPhone).isNotEqualTo(asked)
  }

  /** XS-3: con "solo i chiesti" la tappa lavora quelli e poi si ferma; il resto (anche del solo telefono) resta per il lavoro in background. */
  @Test
  fun soloIChiestiVengonoLavoratiEPoiLaTappaSiFerma() = runTest {
    val f = TestFamily()
    val keys = (1..6).map { f.nas.add(common, concepts = "solo $it") }
    val phone = f.anna()
    phone.phoneMedia[f.nas.newKey()] = MediaKind.PHOTO to "telefono solo"
    phone.urgentKeys += keys[2]
    phone.urgentKeys += keys[4]

    val results = phone.engine.runAll(forever, only = true)

    assertThat(numbersOf(phone.images.order, "solo")).containsExactly(5, 3).inOrder()
    assertThat(phone.embedded).isEqualTo(2)
    assertThat(phone.urgentKeys).isEmpty()
    // Solo l'ambito che contiene una richiesta e' stato toccato.
    assertThat(results.map { it.first }).containsExactly(AiScope.Share(common))
    // Il resto e' ancora da fare: un giro normale lo lavora.
    phone.engine.runAll(forever)
    assertThat(phone.embedded).isEqualTo(7)
  }

  /** XS-3: senza richieste una tappa "solo i chiesti" non fa niente (non diventa mai un giro su tutto). */
  @Test
  fun senzaRichiesteLaTappaSoloIChiestiNonFaNiente() = runTest {
    val f = TestFamily()
    (1..4).forEach { f.nas.add(common, concepts = "niente $it") }
    val phone = f.anna()

    val results = phone.engine.runAll(forever, only = true)

    assertThat(results).isEmpty()
    assertThat(phone.embedded).isEqualTo(0)
  }

  /** XS-4 (unita' di un altro dispositivo): nessuna sottrazione della prenotazione; la richiesta resta e lo stato dice "in attesa". */
  @Test
  fun unUnitaPrenotataDaUnAltroDispositivoNonSiSottraeELaRichiestaAspetta() = runTest {
    val f = TestFamily()
    val keys = (1..4).map { f.nas.add(common, concepts = "altrui $it") }
    val bruno = f.bruno()
    val resource = "work/1/ai/2024-03/0"
    f.nas.of(common).leases[resource] = bruno.id to f.nas.now + TrustLimits.MAX_LEASE_MILLIS
    val phone = f.anna()
    phone.urgentKeys += keys[3]

    phone.engine.runAll(forever, only = true)

    assertThat(phone.embedded).isEqualTo(0)
    assertThat(f.nas.of(common).leases[resource]?.first).isEqualTo(bruno.id)
    assertThat(phone.urgentWaits).contains(keys[3] to UrgentWait.OTHER_DEVICE)
    assertThat(phone.urgentKeys).containsExactly(keys[3])
  }

  /** XS-2/XS-7: un media chiesto senza anteprima resta urgente e lo dice: "in attesa delle anteprime" (non e' mai tolto dal calcolo). */
  @Test
  fun unMediaSenzaAnteprimaRestaUrgenteELoDice() = runTest {
    val f = TestFamily()
    val key = f.nas.add(common, concepts = "senza", preview = false)
    val phone = f.anna()
    phone.urgentKeys += key

    phone.engine.run(AiScope.Share(common), forever)

    assertThat(phone.embedded).isEqualTo(0)
    assertThat(phone.urgentKeys).containsExactly(key)
    assertThat(phone.urgentWaits).contains(key to UrgentWait.PREVIEW)
  }

  /** Il comportamento senza richieste resta identico a prima: stesso ordine, tutto pubblicato, nessuna segnalazione di attesa. */
  @Test
  fun senzaRichiesteDellUtenteNienteCambia() = runTest {
    val f = TestFamily()
    val keys = (1..6).map { f.nas.add(common, concepts = "come prima $it") }
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 2, reducedBatch = 1))

    val r = phone.engine.run(AiScope.Share(common), forever)

    assertThat(numbersOf(phone.images.order, "come prima")).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
    assertThat(r.computed).isEqualTo(6)
    assertThat(f.published(common, phone.id)).containsExactlyElementsIn(keys)
    assertThat(phone.urgentWaits).isEmpty()
    assertThat(phone.urgentDone).isEmpty()
  }

  /** XS-5: l'**unico** criterio di "fatto": il vettore da solo non basta con il testo da leggere. */
  @Test
  fun ilVettoreDaSoloNonBastaConIlTestoDaLeggere() {
    val vec = setOf(AiClass.VEC)
    val both = setOf(AiClass.VEC, AiClass.OCR)
    // Con la lettura del testo attiva servono vettore e testo.
    assertThat(AiCompletion.isDone(vec, readingText = true)).isFalse()
    assertThat(AiCompletion.isDone(both, readingText = true)).isTrue()
    assertThat(AiCompletion.isDone(setOf(AiClass.OCR), readingText = true)).isFalse()
    // Con la lettura spenta basta il vettore.
    assertThat(AiCompletion.isDone(vec, readingText = false)).isTrue()
    assertThat(AiCompletion.isDone(emptySet(), readingText = false)).isFalse()
  }

  /** XS-5: il vettore degli altri non chiude la richiesta (c'e' un testo da leggere): la chiude il testo, letto qui. */
  @Test
  fun ilVettoreDegliAltriNonChiudeLaRichiestaLaChiudeIlTestoLetto() = runTest {
    val f = TestFamily()
    val key = f.nas.add(common, concepts = "testo")
    val first = f.anna("telefono-anna", reading = true)
    first.engine.runAll(forever)
    // Gli altri hanno il vettore; il testo, per questo media, non c'e' (come se non l'avessero letto).
    f.nas.of(common).heads[first.id]!!.remove(key to AiClass.OCR)
    val reading = f.bruno("tablet-bruno", reading = true)
    reading.urgentKeys += key

    reading.engine.runAll(forever)

    assertThat(reading.embedded).isEqualTo(0) // il vettore e' degli altri
    assertThat(f.published(common, reading.id, AiClass.OCR)).containsExactly(key)
    // La richiesta e' finita solo adesso, con il testo letto.
    assertThat(reading.urgentDone).containsExactly(key)
  }
  @Test
  fun richiesteImmutateSenzaAnteprimaServonoUnLottoOrdinarioOgniTre() = runTest {
    val f = TestFamily()
    val waiting = (1..30).map { f.nas.add(common, concepts = "attesa $it", preview = false) }
    (1..15).forEach { f.nas.add(common, concepts = "ordinario $it") }
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 5, reducedBatch = 5))
    phone.urgentKeys.addAll(waiting)
    val waitsAtOrdinary = ArrayList<Int>()
    phone.images.onImage = { waitsAtOrdinary += phone.urgentWaits.size }
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(waitsAtOrdinary.take(5)).containsExactly(10, 10, 10, 10, 10).inOrder()
    assertThat(waitsAtOrdinary.drop(5).take(5)).containsExactly(20, 20, 20, 20, 20).inOrder()
    assertThat(waitsAtOrdinary.drop(10)).containsExactly(30, 30, 30, 30, 30).inOrder()
    assertThat(phone.urgentKeys).containsExactlyElementsIn(waiting)
    assertThat(phone.embedded).isEqualTo(15)
  }

  @Test
  fun richiesteImmutateNonConsumanoTuttoIlBudgetPrimaDelPrimoOrdinario() = runTest {
    val f = TestFamily()
    val waiting = (1..15).map { f.nas.add(common, concepts = "attesa $it", preview = false) }
    f.nas.add(common, concepts = "ordinario")
    val phone = f.anna()
    phone.restart(AiComputeConfig(batch = 5, reducedBatch = 5))
    phone.urgentKeys.addAll(waiting)
    phone.engine.run(AiScope.Share(common), kotlin.time.Duration.parse("1s"))
    assertThat(phone.embedded).isEqualTo(1)
    assertThat(phone.urgentWaits).hasSize(10)
  }

  @Test
  fun richiesteImmutateInRetryServonoLOrdinarioAncheSuTelefono() = runTest {
    val f = TestFamily()
    val phone = f.anna()
    val keys = (1..45).map { i ->
      f.nas.newKey().also { phone.phoneMedia[it] = MediaKind.PHOTO to "retry $i" }
    }
    val waiting = keys.take(30).toSet()
    phone.images.onImage = {
      if (numbersOf(phone.images.order.takeLast(1), "retry").single() <= 30) {
        phone.images.fake.failNext = dev.pampa.mycloudgallery.core.ai.contract.AiEngineError.OUT_OF_MEMORY
      }
    }
    phone.restart(AiComputeConfig(batch = 5, reducedBatch = 5))
    phone.urgentKeys.addAll(waiting)
    phone.engine.run(AiScope.PhoneOnly, forever)
    val order = numbersOf(phone.images.order, "retry")
    assertThat(order.drop(10).take(5)).containsExactly(31, 32, 33, 34, 35).inOrder()
    assertThat(order.drop(25).take(5)).containsExactly(36, 37, 38, 39, 40).inOrder()
    assertThat(phone.urgentKeys).containsExactlyElementsIn(waiting)
    assertThat(order).containsNoDuplicates()
  }

}
