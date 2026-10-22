package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.compute.AiPace
import dev.pampa.mycloudgallery.core.ai.compute.AiRunControl
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 6, P11b (docs/fase6-progetto.md §2.2 "Media del solo telefono", D10, D14, D13): **i media che stanno solo sul telefono si analizzano**.
 * Prima `AiPhoneMedia` e `phoneOnly` erano vuoti e questi media non avevano mai un vettore, ne' un testo letto, ne' un risultato nella ricerca.
 *
 * Telefono vero (galleria sopra il MediaStore finto, con i byte delle foto), NAS finto e motori finti: la preparazione e' quella vera
 * (`DefaultPreparer` sui byte del telefono), il calcolo, il buffer, l'indice locale e la ricerca pure.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class PhoneOnlyAiTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() = rig.close()

  /** Una foto in `DCIM/mcg-prova/` (dentro la recinzione di D13: il conto `test` e' di prova) e una in `DCIM/Camera/` (fuori). */
  private fun twoPhotos(): dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore {
    val store = NasGalleries.newStore()
    val inside = AiRig.jpeg(60)
    val outside = AiRig.jpeg(180)
    store.put(AiRig.photoRow(1, "DCIM/mcg-prova/", "IMG_0001.jpg", inside, rig.time), inside)
    store.put(AiRig.photoRow(2, "DCIM/Camera/", "IMG_0002.jpg", outside, rig.time), outside)
    return store
  }

  @Test
  fun unaFotoSoloSulTelefonoSiAnalizzaInLocaleEOgniAltroResta() = runBlocking<Unit> {
    val phone = rig.founder(twoPhotos())
    rig.enableReading(phone)

    val results = checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))

    // Una sola foto e' candidata: quella dentro la recinzione (D13); l'altra non si apre e non si calcola.
    val onPhone = results.single { it.first == AiScope.PhoneOnly }.second
    assertThat(onPhone.computed).isEqualTo(1)
    assertThat(onPhone.waitingForPreparation).isEqualTo(0)
    assertThat(phone.stack.search.index.sizes).isEqualTo(1 to 1)
    // L'anteprima e' stata preparata dai byte del telefono (sorgente `phone_original`) e il buffer e' tornato vuoto.
    assertThat(phone.reader.reads).containsExactly(OcrSource.PHONE_ORIGINAL)
    assertThat(phone.stack.ai.buffer.keys()).isEmpty()
    // Solo locale (D14): il file non e' in nessuna share, niente nell'outbox `ai` e niente in attesa.
    val pending = phone.stack.ai.publishStore.pending()
    assertThat(pending.waitingForShare).isEqualTo(0)
    // La ricerca la trova dal testo letto.
    assertThat(phone.stack.search.open()).isTrue()
    assertThat(phone.stack.search.engine.search(SearchRequest("pizzeria")).confident.size).isEqualTo(1)

    // Un secondo giro non rifa' niente: il media e' a posto (D22).
    val again = checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))
    assertThat(again.single { it.first == AiScope.PhoneOnly }.second.computed).isEqualTo(0)
    assertThat(phone.reader.reads).hasSize(1)
  }

  @Test
  fun leRegoleDellaTappaValgonoAnchePerIMediaDelSoloTelefono() = runBlocking<Unit> {
    val phone = rig.founder(twoPhotos())
    rig.enableReading(phone)

    // Le regole dicono di fermarsi (fascia, carica, temperatura): nessun media del telefono viene toccato.
    val halted = object : AiRunControl {
      override suspend fun check(): AiPace = AiPace.Halt(AiStop.RULES)
    }
    val results = checkNotNull(phone.stack.ai.runStage(5.minutes, halted))
    assertThat(results.none { it.first == AiScope.PhoneOnly }).isTrue()
    assertThat(phone.stack.search.index.sizes).isEqualTo(0 to 0)
    assertThat(phone.reader.reads).isEmpty()

    // Il ritmo ridotto col caldo (E-A5) non ferma: si calcola, a lotti piccoli.
    val hot = object : AiRunControl {
      override suspend fun check(): AiPace = AiPace.Go(reduced = true)
    }
    val reduced = checkNotNull(phone.stack.ai.runStage(5.minutes, hot))
    assertThat(reduced.single { it.first == AiScope.PhoneOnly }.second.computed).isEqualTo(1)
  }

  @Test
  fun senzaRetePerIlNasIMediaDelTelefonoSiCalcolanoLoStesso() = runBlocking<Unit> {
    val phone = rig.founder(twoPhotos())
    rig.enableReading(phone)

    // E-A6: notte senza NAS (`LOCAL_BUFFER_ONLY`): il buffer non ha niente, ma il telefono non ha bisogno del NAS.
    val noNetwork = object : AiRunControl {
      override suspend fun check(): AiPace = AiPace.Go(localOnly = true)
    }
    val results = checkNotNull(phone.stack.ai.runStage(5.minutes, noNetwork))

    assertThat(results.single { it.first == AiScope.PhoneOnly }.second.computed).isEqualTo(1)
    assertThat(phone.stack.search.index.sizes).isEqualTo(1 to 1)
  }

  @Test
  fun conLaLetturaDelTestoSpentaSiCalcolaSoloIlVettoreEIlLettoreNonSiChiamaMai() = runBlocking<Unit> {
    val phone = rig.founder(twoPhotos())
    // La lettura non e' stata attivata (consenso Q4): il lettore finto fallisce se qualcuno lo chiama (D41).
    assertThat(phone.stack.textSwitch.state.value.activeHere).isFalse()

    val results = checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))

    assertThat(results.single { it.first == AiScope.PhoneOnly }.second.computed).isEqualTo(1)
    assertThat(phone.stack.search.index.sizes).isEqualTo(1 to 0)
    assertThat(phone.reader.reads).isEmpty()
    assertThat(phone.stack.ai.wantedHere()).containsExactly(AiClass.VEC)
  }

  @Test
  fun ilLavoroChePuoRestareSiConta() = runBlocking<Unit> {
    val phone = rig.founder(twoPhotos())
    rig.enableReading(phone)

    // La stima della pagina Ricerca e modelli (§2.8): un media del telefono da analizzare, finche' non e' fatto.
    assertWithMessage("prima").that(checkNotNull(phone.stack.ai.backlog()).photos).isEqualTo(1)
    checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))
    assertWithMessage("dopo").that(checkNotNull(phone.stack.ai.backlog()).photos).isEqualTo(0)
  }
}
