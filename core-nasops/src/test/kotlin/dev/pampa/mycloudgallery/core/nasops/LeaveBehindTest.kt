package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.LeaveBehind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanViolation
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.SkippedItem
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R11 della revisione 4B, scelta dell'utente del 15/10: spostando nell'area comune una cartella con dei media **nascosti**, si possono
 * **lasciare dove sono** (nell'area privata) e spostare il resto. Il piano ([OpRequest.Move.leaveBehind]) esclude quei file anche quando
 * stanno dentro una cartella; la cartella d'origine resta con loro; gli esclusi entrano nell'impronta, quindi la conferma di un piano non
 * avvia l'altro. Gli stessi scenari girano sull'esecutore di riferimento e su quello vero ([ProductionLeaveBehindTest]).
 */
open class LeaveBehindTest {

  protected open fun engine(): KitEngine = KitEngine.REFERENCE

  protected val kit = NasOpsTestKit(engine = engine())

  protected val photo = byteArrayOf(1, 1, 1, 1)
  protected val trip = byteArrayOf(2, 2, 2)
  protected val secret = byteArrayOf(9, 9, 9, 9, 9)
  protected val deepSecret = byteArrayOf(8, 8, 8, 8, 8, 8)

  protected val secretFile = LeaveBehind("test", SharePath.parse("V/riservata.jpg"))
  protected val deepSecretFile = LeaveBehind("test", SharePath.parse("V/Gita/Segreta/s.jpg"))

  protected suspend fun plan(request: OpRequest): OpPlan = (kit.operations.plan(request) as PlanOutcome.Ready).plan

  private suspend fun rejection(request: OpRequest): PlanRejection = (kit.operations.plan(request) as PlanOutcome.Rejected).reason

  protected fun visible(share: String): List<String> = kit.filesIn(share).filterNot { it.startsWith(".mycloudgallery") }

  /** Una cartella `V` con due file normali e due nascosti (uno in una sottocartella che contiene solo lui). */
  protected fun populate() {
    kit.putFile("test", "V/a.jpg", photo)
    kit.putFile("test", "V/Gita/b.jpg", trip)
    kit.putFile("test", "V/riservata.jpg", secret)
    kit.putFile("test", "V/Gita/Segreta/s.jpg", deepSecret)
  }

  protected suspend fun moveV(vararg leave: LeaveBehind) =
    OpRequest.Move(listOf(kit.itemOf("test", "V")), "test-comune", SharePath.parse("F"), leave.toSet())

  // --- Il piano --------------------------------------------------------------------------------------------------------------

  @Test
  fun ilPianoCheLasciaINascostiNonLiContieneELiElencaFraGliEsclusi() = runTest {
    populate()
    val everything = plan(moveV())
    val keeping = plan(moveV(secretFile, deepSecretFile))

    // Senza la scelta, come prima: i nascosti si spostano con il resto.
    assertThat(everything.skipped).isEmpty()
    assertThat(everything.items.filter { !it.isFolder }.map { it.srcPath.value }).containsExactly("V/a.jpg", "V/Gita/b.jpg", "V/riservata.jpg", "V/Gita/Segreta/s.jpg")

    // Con la scelta, nessun elemento per i nascosti (nemmeno quello dentro una sottocartella), e il piano li elenca.
    assertThat(keeping.items.filter { !it.isFolder }.map { it.srcPath.value }).containsExactly("V/a.jpg", "V/Gita/b.jpg")
    assertThat(keeping.skipped.map { it.reason }.toSet()).containsExactly(SkipReason.LEFT_BEHIND)
    assertThat(keeping.skipped.map { it.path.value }).containsExactly("V/riservata.jpg", "V/Gita/Segreta/s.jpg")
    assertThat(keeping.leftBehind).hasSize(2)
    assertThat(keeping.warnings).contains(PlanWarning.SOME_SKIPPED)
    // I byte del riepilogo sono quelli di cio' che si sposta davvero.
    assertThat(keeping.summary.totalBytes).isEqualTo(everything.summary.totalBytes - secret.size - deepSecret.size)
    // Il piano non scrive niente.
    assertThat(visible("test-comune")).isEmpty()
  }

  @Test
  fun unaSottocartellaCheRestaVuotaSoloPerINascostiNonSiRicreaNellAreaComune() = runTest {
    populate()
    val keeping = plan(moveV(secretFile, deepSecretFile))
    // `V/Gita/Segreta` ha solo un nascosto: nell'area comune non deve arrivare il suo nome da solo.
    assertThat(keeping.items.map { it.srcPath.value }).doesNotContain("V/Gita/Segreta")
    assertThat(keeping.items.map { it.dstPath?.value }).doesNotContain("F/V/Gita/Segreta")
  }

  @Test
  fun unNascostoScelto_Direttamente_StaFraGliEsclusi() = runTest {
    kit.putFile("test", "V/riservata.jpg", secret)
    kit.putFile("test", "V/a.jpg", photo)
    val request = OpRequest.Move(
      listOf(kit.itemOf("test", "V/riservata.jpg"), kit.itemOf("test", "V/a.jpg")), "test-comune", SharePath.parse("F"), setOf(secretFile),
    )
    val keeping = plan(request)
    assertThat(keeping.items.map { it.srcPath.value }).containsExactly("V/a.jpg")
    assertThat(keeping.skipped.single().reason).isEqualTo(SkipReason.LEFT_BEHIND)
  }

  @Test
  fun seTuttoCioCheSiSpostaENascostoLasciandoloNonCeNienteDaFare() = runTest {
    kit.putFile("test", "V/riservata.jpg", secret)
    val request = OpRequest.Move(listOf(kit.itemOf("test", "V/riservata.jpg")), "test-comune", null, setOf(secretFile))
    assertThat(rejection(request)).isEqualTo(PlanRejection.EMPTY)
  }

  @Test
  fun unElencoCheNonCorrispondeANienteNonCambiaIlPiano() = runTest {
    populate()
    val unrelated = LeaveBehind("test", SharePath.parse("Altrove/x.jpg"))
    val plain = plan(moveV())
    val same = plan(moveV(unrelated))
    assertThat(same.items.map { it.srcPath.value }).containsExactlyElementsIn(plain.items.map { it.srcPath.value })
    assertThat(same.skipped).isEmpty()
  }

  @Test
  fun ilNomeDelFileNonDipendeDalleMaiuscole() = runTest {
    populate()
    val shouting = LeaveBehind("TEST", SharePath.parse("v/RISERVATA.jpg"))
    val keeping = plan(moveV(shouting))
    assertThat(keeping.items.map { it.srcPath.value }).doesNotContain("V/riservata.jpg")
    assertThat(keeping.skipped.map { it.path.value }).containsExactly("V/riservata.jpg")
  }

  // --- L'impronta ------------------------------------------------------------------------------------------------------------

  @Test
  fun glEsclusiEntranoNellImprontaDelPiano() = runTest {
    populate()
    val everything = plan(moveV())
    val keeping = plan(moveV(secretFile, deepSecretFile))
    val onlyOne = plan(moveV(secretFile))
    assertThat(keeping.hash).isNotEqualTo(everything.hash)
    assertThat(onlyOne.hash).isNotEqualTo(keeping.hash)
    // Stessi elementi, ma senza l'elenco degli esclusi: un altro piano. La conferma di "lascia" non vale per "sposta e basta".
    val stripped = OpPlan(keeping.kind, keeping.items, emptyList(), keeping.summary, keeping.warnings)
    assertThat(stripped.hash).isNotEqualTo(keeping.hash)
    // E gli esclusi di un tipo diverso (sola lettura, gia' lì) sono un'altra cosa dai nascosti lasciati.
    val otherReason = OpPlan(
      keeping.kind, keeping.items, keeping.skipped.map { SkippedItem(it.share, it.path, SkipReason.SAME_LOCATION) }, keeping.summary, keeping.warnings,
    )
    assertThat(otherReason.hash).isNotEqualTo(keeping.hash)
  }

  @Test
  fun senzaEsclusiLImprontaELaStessaDiSempre() = runTest {
    populate()
    val everything = plan(moveV())
    assertThat(PlanHash.of(everything.kind, everything.items, emptyList())).isEqualTo(PlanHash.of(everything.kind, everything.items))
    assertThat(everything.hash).isEqualTo(PlanHash.of(everything.kind, everything.items))
  }

  @Test
  fun laConfermaDelPianoCheLasciaNonAvviaQuelloCheSpostaTutto() = runTest {
    populate()
    val everything = plan(moveV())
    val keeping = plan(moveV(secretFile, deepSecretFile))
    val forKeeping = kit.confirm(keeping)
    val forEverything = kit.confirm(everything)

    // La conferma data per "lascia" non fa partire "sposta tutto", e viceversa: niente nel diario, niente sul NAS.
    assertThat(kit.operations.start(everything, forKeeping)).isEqualTo(StartOutcome.ConfirmationRejected(ConfirmationCheck.WrongPlan))
    assertThat(kit.operations.start(keeping, forEverything)).isEqualTo(StartOutcome.ConfirmationRejected(ConfirmationCheck.WrongPlan))
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
    assertThat(visible("test-comune")).isEmpty()
    assertThat(visible("test")).hasSize(4)

    // La conferma giusta, per il piano giusto, parte.
    assertThat(kit.operations.start(keeping, forKeeping)).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(visible("test-comune")).containsExactly("F/V/a.jpg", "F/V/Gita/b.jpg")
  }

  @Test
  fun unPianoCheLasciaUnFileEChiedeDiSpostarloNonParte() = runTest {
    populate()
    val everything = plan(moveV())
    val keeping = plan(moveV(secretFile))
    // Un piano costruito male (da una UI con un errore): gli elementi di "sposta tutto" con l'elenco degli esclusi di "lascia".
    val tampered = OpPlan(everything.kind, everything.items, keeping.skipped, everything.summary, everything.warnings)
    assertThat(kit.operations.start(tampered, kit.confirm(tampered))).isEqualTo(StartOutcome.PlanInvalid(PlanViolation.LEFT_BEHIND_MOVED))
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
    assertThat(visible("test-comune")).isEmpty()
  }

  // --- L'esecuzione -----------------------------------------------------------------------------------------------------------

  @Test
  fun nessunNascostoFinisceNellAreaComuneELaCartellaDOrigineResta() = runTest {
    populate()
    val keeping = plan(moveV(secretFile, deepSecretFile))
    assertThat(kit.operations.start(keeping, kit.confirm(keeping))).isInstanceOf(StartOutcome.Started::class.java)
    checkLeft()

    // Una ripresa dopo la morte del processo non cambia niente: i nascosti non sono nel diario.
    kit.restart()
    kit.operations.resumeAll(5.minutes)
    checkLeft()
    val items = kit.store.items(kit.store.ops(OpState.entries.toSet()).single().id)
    assertThat(items.map { it.srcPath.value }).containsNoneOf("V/riservata.jpg", "V/Gita/Segreta/s.jpg")
  }

  protected fun checkLeft() {
    // Nell'area comune arrivano i file normali, e nessun byte di un nascosto.
    assertThat(visible("test-comune")).containsExactly("F/V/a.jpg", "F/V/Gita/b.jpg")
    for (path in kit.filesIn("test-comune")) {
      val content = kit.bytes("test-comune", path)!!.toList()
      assertThat(content).isNotEqualTo(secret.toList())
      assertThat(content).isNotEqualTo(deepSecret.toList())
    }
    // I nascosti restano dove sono, intatti, e la cartella d'origine non si toglie ne' si svuota.
    assertThat(visible("test")).containsExactly("V/riservata.jpg", "V/Gita/Segreta/s.jpg")
    assertThat(kit.bytes("test", "V/riservata.jpg")!!.toList()).isEqualTo(secret.toList())
    assertThat(kit.bytes("test", "V/Gita/Segreta/s.jpg")!!.toList()).isEqualTo(deepSecret.toList())
    assertThat(kit.exists("test", "V")).isTrue()
    assertThat(kit.exists("test", "V/Gita/Segreta")).isTrue()
    // Cio' che si e' spostato ha la sua copia nel cestino della share d'origine (30 giorni), niente di piu'.
    assertThat(kit.store.allTrash()).hasSize(2)
    assertThat(kit.exists("test", "V/a.jpg")).isFalse()
    assertThat(kit.exists("test", "V/Gita/b.jpg")).isFalse()
  }
}
