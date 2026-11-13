package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.model.MediaKind
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto X (12/11/2026): "Analizza adesso" fa passare **davanti nella coda** un media, senza cambiare nient'altro (stessi cancelli,
 * stesse prenotazioni, nessun media calcolato due volte). Le chiavi del NAS finto sono deterministiche e le unita' ordinano per chiave:
 * l'ordine naturale e' quello di creazione, quindi l'ultimo creato e' l'ultimo della coda se nessuno lo chiede. I byte che il motore
 * finto riceve portano il concetto e un suffisso ("FAKE:coda 6 0006"): qui si confrontano i numeri dopo il prefisso.
 */
class UrgentQueueTest {
  private val forever = Duration.INFINITE
  private val common = TestFamily.COMMON

  /** I numeri dei concetti "FAKE:<prefisso> N ..." nell'ordine in cui il motore li ha calcolati. */
  private fun numbersOf(order: List<String>, prefix: String): List<Int> =
    order.mapNotNull { it.removePrefix("FAKE:$prefix ").substringBefore(' ').toIntOrNull() }

  /** Nella share: il media chiesto (l'ultimo della coda) esce per primo, gli altri seguono nell'ordine di sempre. */
  @Test
  fun nellaShareIlMediaChiestoEscePerPrimo() = runTest {
    val f = TestFamily()
    val keys = (1..6).map { f.nas.add(common, concepts = "coda $it") }
    val phone = f.anna()
    phone.urgentKeys += keys.last()

    phone.engine.run(AiScope.Share(common), forever)

    assertThat(numbersOf(phone.images.order, "coda")).containsExactly(6, 1, 2, 3, 4, 5).inOrder()
    // Finito: l'urgenza e' uscita da sola, e tutti sono pubblicati come sempre.
    assertThat(phone.urgentKeys).isEmpty()
    assertThat(phone.urgentDone).containsExactly(keys.last())
    assertThat(f.published(common, phone.id)).containsExactlyElementsIn(keys)
    // Il flusso vivo: le ultime finite restano, niente media di adesso a tappa ferma.
    assertThat(phone.engine.live.value.current).isNull()
    assertThat(phone.engine.live.value.recent.first()).isEqualTo(keys[4])
    assertThat(phone.engine.live.value.recent).contains(keys.last())
  }

  /** Nei media del solo telefono la precedenza vale allo stesso modo (risultati solo locali, come sempre). */
  @Test
  fun nelSoloTelefonoIlMediaChiestoEscePerPrimo() = runTest {
    val f = TestFamily()
    val phone = f.anna()
    val keys = (1..4).map { i ->
      val key = f.nas.newKey()
      phone.phoneMedia[key] = MediaKind.PHOTO to "telefono $i"
      key
    }
    phone.urgentKeys += keys[2]

    val r = phone.engine.run(AiScope.PhoneOnly, forever)

    assertThat(r.computed).isEqualTo(4)
    assertThat(numbersOf(phone.images.order, "telefono").first()).isEqualTo(3)
    assertThat(phone.urgentKeys).isEmpty()
    assertThat(phone.publisher.pending.value.staged).isEqualTo(0)
  }

  /** Con le regole che fermano subito, niente si calcola e l'urgenza **resta**: al giro dopo il media e' il primo. */
  @Test
  fun conLeRegoleCheFermanoRestaPrimo() = runTest {
    val f = TestFamily()
    val keys = (1..5).map { f.nas.add(common, concepts = "fermi $it") }
    val phone = f.anna()
    phone.urgentKeys += keys.last()

    val stopped = phone.engine.run(AiScope.Share(common), forever, object : AiRunControl {
      override suspend fun check(): AiPace = AiPace.Halt(AiStop.RULES)
    })

    assertThat(stopped.stoppedBy).isEqualTo(AiStop.RULES)
    assertThat(phone.embedded).isEqualTo(0)
    assertThat(phone.urgentKeys).containsExactly(keys.last())

    phone.engine.run(AiScope.Share(common), forever)
    assertThat(numbersOf(phone.images.order, "fermi").first()).isEqualTo(5)
    assertThat(phone.urgentKeys).isEmpty()
  }

  /** Un media gia' analizzato non si ricalcola: una seconda richiesta non fa ripartire il motore su di lui. */
  @Test
  fun giaFattoNonSiAnalizzaDueVolte() = runTest {
    val f = TestFamily()
    val keys = (1..3).map { f.nas.add(common, concepts = "fatte $it") }
    val phone = f.anna()
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(phone.embedded).isEqualTo(3)

    phone.urgentKeys += keys[1]
    phone.engine.run(AiScope.Share(common), forever)

    assertThat(phone.embedded).isEqualTo(3)
    assertThat(phone.images.perImage.values.toSet()).containsExactly(1)
  }

  /**
   * Salvato qui ma non ancora pubblicato (morte fra salvataggio e outbox): al giro dopo si rimette in fila **senza ricalcolo** e
   * l'urgenza esce (il media e' finito qui).
   */
  @Test
  fun salvatoMaNonPubblicatoEsceDalleUrgenzeSenzaRicalcolo() = runTest {
    val f = TestFamily()
    val keys = (1..4).map { f.nas.add(common, concepts = "morte $it") }
    val phone = f.anna()
    phone.urgentKeys += keys.last()
    phone.dieAfterSave = 1

    runCatching { phone.engine.run(AiScope.Share(common), forever) }
    assertThat(phone.embedded).isEqualTo(1)
    assertThat(phone.urgentKeys).containsExactly(keys.last())

    phone.dieAfterSave = -1
    phone.restart()
    phone.engine.run(AiScope.Share(common), forever)

    assertThat(phone.embedded).isEqualTo(4)
    assertThat(numbersOf(phone.images.order, "morte").count { it == 4 }).isEqualTo(1)
    assertThat(phone.urgentKeys).isEmpty()
    assertThat(f.published(common, phone.id)).containsExactlyElementsIn(keys)
  }

  /** I cancelli restano: un'urgenza non apre un'area che non e' di questo dispositivo (D14, nessuna porta toccata). */
  @Test
  fun unUrgenzaNonSaltaICancelliDelleAree() = runTest {
    val f = TestFamily()
    val foreign = "privata-di-un-altro"
    f.nas.share(foreign, "bruno")
    val key = f.nas.add(foreign, concepts = "altrui")
    val phone = f.anna()
    phone.urgentKeys += key

    phone.engine.run(AiScope.Share(foreign), forever)

    assertThat(phone.embedded).isEqualTo(0)
    assertThat(phone.units.claims).isEmpty()
    assertThat(phone.engine.counters.foreignScopes.get()).isEqualTo(1)
    assertThat(phone.urgentKeys).containsExactly(key)
  }

  /** Il flusso vivo non porta mai chiavi nei testi (D40): `toString` dice solo conteggi. */
  @Test
  fun ilVivoNonStampaChiavi() = runTest {
    val f = TestFamily()
    val key = f.nas.add(common, concepts = "segreta")
    val live = AiLive(key, listOf(key), listOf(key))
    assertThat(live.toString()).doesNotContain(key.hex)
  }
}
