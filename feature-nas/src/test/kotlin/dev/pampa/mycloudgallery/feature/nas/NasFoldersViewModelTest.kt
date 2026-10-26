package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.confirm.NameCheck
import dev.pampa.mycloudgallery.feature.nas.confirm.NamePurpose
import dev.pampa.mycloudgallery.feature.nas.confirm.confirmFacts
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersMode
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Cartelle NAS sul NAS finto di core-nasops (il kit: operazioni, cestino, cartelle e orologi collegati): sfogliare, selezionare,
 * i piani da confermare, il nome controllato mentre si scrive, la sola lettura (E-N9) e il NAS che non risponde. Ogni gesto che
 * cambia il NAS passa da un piano e da una conferma costruita dal foglio (`ConfirmationSheet`) dopo un tocco.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasFoldersViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val kit = NasOpsTestKit()
  private val areas = FakeNasAreas()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(folders: NasFolders = kit.folders) = NasFoldersViewModel(folders, kit.operations, areas, kit.clocks)

  private fun TestScope.settle() {
    runCurrent()
  }

  private fun populate() {
    kit.putFile("test", "Viaggi/Oslo/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Viaggi/Oslo/b.jpg", byteArrayOf(4, 5, 6, 7))
    kit.putFile("test", "Viaggi/mappa.png", byteArrayOf(8))
    kit.putFile("test", "nota.txt", byteArrayOf(9))
    kit.putDir("test-comune", "Famiglia")
  }

  @Test
  fun siSfogliaDalleAreeFinoAUnaCartella() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    settle()
    assertThat(vm.state.value.where).isNull()
    assertThat(vm.state.value.areas).hasSize(2)

    vm.openArea(areas.areas.value.first())
    settle()
    val root = vm.state.value
    assertThat(root.where).isEqualTo(FolderWhere("test", null))
    // Le cartelle prima, poi i file, per nome.
    assertThat(root.entries.map { it.name }).containsExactly("Viaggi", "nota.txt").inOrder()
    assertThat(root.canModify).isTrue()

    vm.openFolder(root.entries.first())
    settle()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", SharePath.of("Viaggi")))
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Oslo", "mappa.png").inOrder()
  }

  @Test
  fun indietroSaleUnLivelloAllaVoltaPoiTornaAlleAree() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", SharePath.parse("Viaggi/Oslo")))
    settle()
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", SharePath.of("Viaggi")))
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", null))
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.where).isNull()
    // Alle aree non c'e' piu' da salire: tocca a chi ospita la pagina.
    assertThat(vm.up()).isFalse()
  }

  @Test
  fun indietroConUnaSelezioneLaToglieSoltanto() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    assertThat(vm.state.value.selection).containsExactly(SharePath.of("nota.txt"))
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.selection).isEmpty()
    assertThat(vm.state.value.selecting).isFalse()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", null))
  }

  @Test
  fun eliminareChiedeUnPianoEdopoLaConfermaMandaNelCestino() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()

    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.TRASH)
    assertThat(plan.summary.itemCount).isEqualTo(1)
    // Il piano da solo non cambia niente sul NAS.
    assertThat(kit.exists("test", "nota.txt")).isTrue()

    val confirmation = checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs()))
    vm.confirmed(plan, confirmation)
    settle()
    assertThat(kit.exists("test", "nota.txt")).isFalse()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.selection).isEmpty()
    assertThat(vm.state.value.notice?.opensOperations).isTrue()
    // Dopo l'operazione l'elenco e' riletto: la nota non c'e' piu'.
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Viaggi")
  }

  @Test
  fun unaConfermaDiUnAltroPianoNonFaPartireNiente() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    // Una conferma data per un altro piano (un altro elemento): il diario non parte.
    vm.clearSelection()
    vm.toggle(SharePath.of("Viaggi"))
    vm.requestTrash()
    settle()
    val other = checkNotNull(vm.state.value.confirm)
    assertThat(other.hash).isNotEqualTo(plan.hash)
    val wrong = checkNotNull(ConfirmationSheet(other).tap(vm.monotonicMs()))
    vm.confirmed(plan, wrong)
    settle()
    assertThat(kit.exists("test", "nota.txt")).isTrue()
    assertThat(kit.exists("test", "Viaggi/mappa.png")).isTrue()
    assertThat(vm.state.value.notice?.opensOperations).isFalse()
  }

  // --- R11: i nascosti spostando nell'area comune (scelta dell'utente del 15/10: "Lascia i nascosti dove sono") -------------------------

  /** Seleziona `Viaggi` (Oslo/a.jpg, Oslo/b.jpg, mappa.png) nell'area privata e chiede di spostarla in `Famiglia` dell'area comune. */
  private fun TestScope.askToMoveViaggiToCommon(vm: NasFoldersViewModel) {
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("Viaggi"))
    vm.startMove()
    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    vm.moveHere()
    settle()
  }

  private fun sourceFiles(plan: dev.pampa.mycloudgallery.core.nasops.contract.OpPlan) = plan.items.filter { !it.isFolder }.map { it.srcPath.value }

  @Test
  fun r11_spostareNellAreaComuneDiceQuantiElementiNascostiCiSono() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    val vm = viewModel()
    askToMoveViaggiToCommon(vm)
    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(vm.state.value.confirmHidden).isEqualTo(1)
    val facts = confirmFacts(plan, vm.state.value.areas, vm.state.value.confirmHidden)
    assertThat(facts.intoCommon).isTrue()
    assertThat(facts.hiddenCount).isEqualTo(1)
    // Nella stessa area (niente "area comune") i nascosti non contano: nessuna riga.
    assertThat(confirmFacts(plan, emptyList(), 1).hiddenCount).isEqualTo(0)
  }

  @Test
  fun r11_ConINascostiIlFoglioHaDuePianiConDueImpronte_ELaScelteDiPartenzaELaPrudente() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    val vm = viewModel()
    askToMoveViaggiToCommon(vm)
    val everything = checkNotNull(vm.state.value.confirm)
    val leaving = checkNotNull(vm.state.value.confirmLeave)

    // "Sposta tutto" porta anche il nascosto; "Lascia" no, e lo elenca fra gli esclusi.
    assertThat(sourceFiles(everything)).containsExactly("Viaggi/Oslo/a.jpg", "Viaggi/Oslo/b.jpg", "Viaggi/mappa.png")
    assertThat(sourceFiles(leaving)).containsExactly("Viaggi/Oslo/a.jpg", "Viaggi/mappa.png")
    assertThat(leaving.skipped.map { it.reason }).containsExactly(SkipReason.LEFT_BEHIND)
    assertThat(leaving.hash).isNotEqualTo(everything.hash)
    assertThat(everything.skipped).isEmpty()
    // I piani non toccano il NAS.
    assertThat(kit.exists("test", "Viaggi/Oslo/b.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "Famiglia/Viaggi")).isFalse()
  }

  @Test
  fun r11_SenzaNascostiNonCeNessunaScelta() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    askToMoveViaggiToCommon(vm)
    assertThat(vm.state.value.confirm).isNotNull()
    assertThat(vm.state.value.confirmHidden).isEqualTo(0)
    assertThat(vm.state.value.confirmLeave).isNull()
  }

  @Test
  fun r11_SpostandoNellaStessaAreaNonSiChiedeDeiNascosti() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    vm.goTo(FolderWhere("test", SharePath.of("Viaggi")))
    settle()
    // Se si chiedesse al catalogo dei nascosti per il piano, sarebbe un errore: nella stessa area non serve. (Gli elenchi sono gia' letti.)
    areas.failing = true
    vm.moveHere()
    settle()
    assertThat(vm.state.value.confirm).isNotNull()
    assertThat(vm.state.value.confirmHidden).isEqualTo(0)
    assertThat(vm.state.value.confirmLeave).isNull()
  }

  @Test
  fun r11_ConfermandoIlPianoCheLasciaINascostiRestanoDovErano() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    val vm = viewModel()
    askToMoveViaggiToCommon(vm)
    val leaving = checkNotNull(vm.state.value.confirmLeave)
    vm.confirmed(leaving, checkNotNull(ConfirmationSheet(leaving).tap(vm.monotonicMs())))
    settle()

    assertThat(vm.state.value.notice?.opensOperations).isTrue()
    // Nell'area comune arrivano gli altri; il nascosto resta nell'area privata, intatto, e nessun suo byte e' nell'area comune.
    assertThat(kit.filesIn("test-comune").filterNot { it.startsWith(".mycloudgallery") })
      .containsExactly("Famiglia/Viaggi/Oslo/a.jpg", "Famiglia/Viaggi/mappa.png")
    assertThat(kit.bytes("test", "Viaggi/Oslo/b.jpg")!!.toList()).isEqualTo(listOf<Byte>(4, 5, 6, 7))
    for (path in kit.filesIn("test-comune")) assertThat(kit.bytes("test-comune", path)!!.toList()).isNotEqualTo(listOf<Byte>(4, 5, 6, 7))
    assertThat(kit.exists("test", "Viaggi")).isTrue()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.confirmLeave).isNull()
    assertThat(vm.state.value.confirmHidden).isEqualTo(0)
  }

  @Test
  fun r11_ConfermandoSpostaTuttoIlNascostoVaNellAreaComune() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    val vm = viewModel()
    askToMoveViaggiToCommon(vm)
    val everything = checkNotNull(vm.state.value.confirm)
    vm.confirmed(everything, checkNotNull(ConfirmationSheet(everything).tap(vm.monotonicMs())))
    settle()
    assertThat(kit.exists("test-comune", "Famiglia/Viaggi/Oslo/b.jpg")).isTrue()
    assertThat(kit.exists("test", "Viaggi/Oslo/b.jpg")).isFalse()
  }

  @Test
  fun r11_UnaConfermaPerUnPianoNonAvviaLAltro() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    val vm = viewModel()
    askToMoveViaggiToCommon(vm)
    val everything = checkNotNull(vm.state.value.confirm)
    val leaving = checkNotNull(vm.state.value.confirmLeave)

    // Il tocco su "Lascia i nascosti" non puo' far partire "Sposta tutto"...
    vm.confirmed(everything, checkNotNull(ConfirmationSheet(leaving).tap(vm.monotonicMs())))
    settle()
    assertThat(vm.state.value.notice?.opensOperations).isFalse()
    assertThat(kit.exists("test", "Viaggi/Oslo/b.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "Famiglia/Viaggi")).isFalse()
    assertThat(kit.operations.operations.value).isEmpty()

    // ...e viceversa.
    vm.confirmed(leaving, checkNotNull(ConfirmationSheet(everything).tap(vm.monotonicMs())))
    settle()
    assertThat(vm.state.value.notice?.opensOperations).isFalse()
    assertThat(kit.exists("test", "Viaggi/Oslo/b.jpg")).isTrue()
    assertThat(kit.operations.operations.value).isEmpty()
  }

  @Test
  fun r11_SeTuttoCioCheSiSpostaENascostoLaScelteCeMaLasciandoliNonRestaNiente() = runTest(dispatcher) {
    populate()
    // Un file nascosto non si elenca (I8), quindi non si sceglie da solo: si sceglie la cartella che contiene soltanto nascosti.
    kit.putFile("test", "Segreti/s.jpg", byteArrayOf(8, 8))
    areas.hiddenPaths += "Segreti/s.jpg"
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("Segreti"))
    vm.startMove()
    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    vm.moveHere()
    settle()
    assertThat(vm.state.value.confirm).isNotNull()
    assertThat(vm.state.value.confirmHidden).isEqualTo(1)
    assertThat(vm.state.value.confirmLeave).isNull()
  }

  @Test
  fun r11_IlPianoCheLasciaSiRicontrolla_UnFileDiventatoNascostoFraDuePianiVieneLasciato() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    var plans = 0
    val operations = object : dev.pampa.mycloudgallery.core.nasops.contract.NasOperations by kit.operations {
      override suspend fun plan(request: dev.pampa.mycloudgallery.core.nasops.contract.OpRequest): PlanOutcome {
        // Dopo il primo piano (quello che sposta tutto) un altro file risulta nascosto.
        if (++plans == 2) areas.hiddenPaths += "Viaggi/mappa.png"
        return kit.operations.plan(request)
      }
    }
    val vm = NasFoldersViewModel(kit.folders, operations, areas, kit.clocks)
    askToMoveViaggiToCommon(vm)
    val leaving = checkNotNull(vm.state.value.confirmLeave)
    assertThat(sourceFiles(leaving)).containsExactly("Viaggi/Oslo/a.jpg")
    assertThat(leaving.skipped).hasSize(2)
    assertThat(vm.state.value.confirmHidden).isEqualTo(2)
  }

  @Test
  fun r11_SeIlCatalogoNonRispondeAlControlloDelPianoCheLasciaNonSiProponeNiente() = runTest(dispatcher) {
    populate()
    areas.hiddenPaths += "Viaggi/Oslo/b.jpg"
    var plans = 0
    val operations = object : dev.pampa.mycloudgallery.core.nasops.contract.NasOperations by kit.operations {
      override suspend fun plan(request: dev.pampa.mycloudgallery.core.nasops.contract.OpRequest): PlanOutcome {
        if (++plans == 2) areas.failing = true
        return kit.operations.plan(request)
      }
    }
    val vm = NasFoldersViewModel(kit.folders, operations, areas, kit.clocks)
    askToMoveViaggiToCommon(vm)
    // Non poter controllare e' come avere ancora dei nascosti: nessun foglio, una frase, niente promesso e non mantenuto.
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.confirmLeave).isNull()
    assertThat(vm.state.value.notice).isNotNull()
    assertThat(kit.exists("test", "Viaggi/Oslo/b.jpg")).isTrue()
  }

  @Test
  fun r11_ConLEsecutoreVeroLasciaINascostiELaCartellaDOrigineResta() = runTest(dispatcher) {
    val production = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    production.putFile("test", "Viaggi/Oslo/a.jpg", byteArrayOf(1, 2, 3))
    production.putFile("test", "Viaggi/Oslo/b.jpg", byteArrayOf(4, 5, 6, 7))
    production.putFile("test", "Viaggi/Segreti/s.jpg", byteArrayOf(8, 8))
    production.putFile("test", "Viaggi/mappa.png", byteArrayOf(9))
    production.putDir("test-comune", "Famiglia")
    areas.hiddenPaths += setOf("Viaggi/Oslo/b.jpg", "Viaggi/Segreti/s.jpg")
    val vm = NasFoldersViewModel(production.folders, production.operations, areas, production.clocks)
    askToMoveViaggiToCommon(vm)

    val leaving = checkNotNull(vm.state.value.confirmLeave)
    assertThat(vm.state.value.confirmHidden).isEqualTo(2)
    assertThat(sourceFiles(leaving)).containsExactly("Viaggi/Oslo/a.jpg", "Viaggi/mappa.png")
    vm.confirmed(leaving, checkNotNull(ConfirmationSheet(leaving).tap(vm.monotonicMs())))
    settle()

    assertThat(production.filesIn("test-comune").filterNot { it.startsWith(".mycloudgallery") })
      .containsExactly("Famiglia/Viaggi/Oslo/a.jpg", "Famiglia/Viaggi/mappa.png")
    assertThat(production.filesIn("test").filterNot { it.startsWith(".mycloudgallery") })
      .containsExactly("Viaggi/Oslo/b.jpg", "Viaggi/Segreti/s.jpg")
    // Le cartelle d'origine non si sono tolte, e nell'area comune non e' arrivato nemmeno il nome della cartella di soli nascosti.
    assertThat(production.exists("test", "Viaggi")).isTrue()
    assertThat(production.exists("test", "Viaggi/Segreti")).isTrue()
    assertThat(production.exists("test-comune", "Famiglia/Viaggi/Segreti")).isFalse()
  }

  @Test
  fun spostareNellAreaComuneDiceChePassaDallaCopia() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    assertThat(vm.state.value.mode).isInstanceOf(FoldersMode.PickDestination::class.java)
    assertThat(vm.state.value.selection).isEmpty()

    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    assertThat(vm.state.value.mode).isInstanceOf(FoldersMode.PickDestination::class.java)
    vm.moveHere()
    settle()

    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.MOVE)
    assertThat(plan.warnings).contains(PlanWarning.CROSS_SHARE_COPY)
    val facts = confirmFacts(plan, vm.state.value.areas)
    assertThat(facts.intoCommon).isTrue()
    assertThat(facts.destFolder).containsExactly("Famiglia")

    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
    assertThat(kit.exists("test-comune", "Famiglia/nota.txt")).isTrue()
    assertThat(kit.exists("test", "nota.txt")).isFalse()
  }

  @Test
  fun spostareNellaStessaCartellaERifiutato() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    vm.moveHere()
    settle()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.notice).isNotNull()
  }

  @Test
  fun annullareLoSpostamentoTornaASfogliare() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    vm.cancelMove()
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
  }

  // --- O-B9: una cartella nuova dentro la scelta della destinazione ------------------------------------------------------------

  /** Crea [name] nella cartella aperta, passando dal nome controllato e dal foglio come fa l'utente. */
  private fun TestScope.createFolderHere(vm: NasFoldersViewModel, name: String) {
    vm.openNewFolder()
    vm.onNameChanged(name)
    advanceTimeBy(300)
    settle()
    val plan = (vm.state.value.name?.check as NameCheck.Ready).plan
    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    // Entrare nella cartella nuova rilegge l'elenco: un altro giro.
    settle()
  }

  @Test
  fun o_b9_creareUnaCartellaDentroLaSceltaLaTieneAperta() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    val picking = vm.state.value.mode as FoldersMode.PickDestination
    vm.goTo(FolderWhere("test-comune", null))
    settle()

    createFolderHere(vm, "Scuola")

    // La cartella e' nata e la scelta non si e' chiusa: gli stessi elementi da spostare, la stessa modalita'.
    assertThat(kit.exists("test-comune", "Scuola")).isTrue()
    assertThat(vm.state.value.mode).isEqualTo(picking)
    assertThat((vm.state.value.mode as FoldersMode.PickDestination).items.map { it.path.value }).containsExactly("nota.txt")
    assertThat(vm.state.value.picking).isTrue()
    assertThat(vm.state.value.name).isNull()
  }

  @Test
  fun o_b9_laCartellaNuovaEDiventataLaDestinazioneProposta() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    vm.goTo(FolderWhere("test-comune", null))
    settle()

    createFolderHere(vm, "Scuola")

    // Si e' dentro la cartella nuova: "Sposta qui" e' attivo e porta li' gli elementi.
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test-comune", SharePath.of("Scuola")))
    assertThat(vm.state.value.canMoveHere).isTrue()
    vm.moveHere()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.MOVE)
    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    assertThat(kit.exists("test-comune", "Scuola/nota.txt")).isTrue()
    assertThat(kit.exists("test", "nota.txt")).isFalse()
    // Finito lo spostamento si torna a sfogliare, come sempre.
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
  }

  @Test
  fun o_b9_creareUnaCartellaSfogliandoNonCambiaModalita() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()

    createFolderHere(vm, "Scuola")

    // Fuori dalla scelta una cartella nuova e' solo una cartella nuova: si resta dove si e' (nessun ingresso automatico).
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", null))
    assertThat(vm.state.value.entries.map { it.name }).contains("Scuola")
  }

  // --- Il nome -----------------------------------------------------------------------------------------------------------

  @Test
  fun ilNomeDiUnaNuovaCartellaSiControllaMentreSiScrive() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.openNewFolder()
    assertThat(vm.state.value.name?.purpose).isEqualTo(NamePurpose.NEW_FOLDER)
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Empty)

    vm.onNameChanged("Scuola")
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Checking)
    advanceTimeBy(300)
    settle()
    val ready = vm.state.value.name?.check
    assertThat(ready).isInstanceOf(NameCheck.Ready::class.java)

    // Un nome gia' occupato.
    vm.onNameChanged("Viaggi")
    advanceTimeBy(300)
    settle()
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Exists)

    // Un nome che il NAS non ammette: nessuna richiesta al NAS, la correzione proposta (E-D9).
    vm.onNameChanged("A:B ")
    val invalid = vm.state.value.name?.check as NameCheck.Invalid
    assertThat(invalid.suggestion).isEqualTo("A_B")

    vm.onNameChanged("")
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Empty)
  }

  @Test
  fun ilTocco_sulPulsanteDelNomeCreaLaCartella() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.openNewFolder()
    vm.onNameChanged("Scuola")
    advanceTimeBy(300)
    settle()
    val plan = (vm.state.value.name?.check as NameCheck.Ready).plan
    val confirmation = checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs()))
    vm.confirmed(plan, confirmation)
    settle()
    assertThat(vm.state.value.name).isNull()
    assertThat(vm.state.value.entries.map { it.name }).contains("Scuola")
  }

  @Test
  fun rinominareControllaChiEIlNomeDiAdesso() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.openRename()
    val draft = checkNotNull(vm.state.value.name)
    assertThat(draft.purpose).isEqualTo(NamePurpose.RENAME)
    assertThat(draft.text).isEqualTo("nota.txt")
    assertThat(draft.check).isEqualTo(NameCheck.Same)

    vm.onNameChanged("nota.txt")
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Same)
    vm.onNameChanged("promemoria.txt")
    advanceTimeBy(300)
    settle()
    assertThat(vm.state.value.name?.check).isInstanceOf(NameCheck.Ready::class.java)
  }

  @Test
  fun rinominareVuoleUnSoloElemento() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.selectAll()
    vm.openRename()
    assertThat(vm.state.value.name).isNull()
  }

  // --- Sola lettura (E-N9) e NAS che non risponde ----------------------------------------------------------------------------

  @Test
  fun inSolaLetturaLeAzioniCheModificanoSonoSpente() = runTest(dispatcher) {
    populate()
    kit.readOnlyShares += "test-comune"
    kit.putFile("test-comune", "Famiglia/ricetta.txt", byteArrayOf(1))
    val vm = viewModel()
    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    val state = vm.state.value
    assertThat(state.readOnly).isTrue()
    assertThat(state.canModify).isFalse()

    vm.toggle(SharePath.parse("Famiglia/ricetta.txt"))
    vm.requestTrash()
    vm.startMove()
    vm.openNewFolder()
    vm.openRename()
    settle()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
    assertThat(vm.state.value.name).isNull()
    assertThat(kit.exists("test-comune", "Famiglia/ricetta.txt")).isTrue()
  }

  @Test
  fun senzaIlNasSiVedeLUltimoElencoESiModificaNiente() = runTest(dispatcher) {
    val cached = listOf(
      NasFolderEntry(SharePath.of("Viaggi"), true, 0, NasFixtures.NOW, null, 12),
      NasFolderEntry(SharePath.of("nota.txt"), false, 3, NasFixtures.NOW, 7),
    )
    val offline = object : NasFolders {
      override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> = flow {
        emit(NasFolderState.Loading)
        emit(NasFolderState.Offline(cached, NasFixtures.NOW - 3_600_000))
      }

      override suspend fun refresh(share: String, dir: SharePath?) = Unit
    }
    val vm = viewModel(offline)
    vm.goTo(FolderWhere("test", null))
    settle()
    val state = vm.state.value
    assertThat(state.offline).isTrue()
    assertThat(state.entries.map { it.name }).containsExactly("Viaggi", "nota.txt").inOrder()
    assertThat(state.canModify).isFalse()
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    assertThat(vm.state.value.confirm).isNull()
  }

  @Test
  fun unaCartellaNegataEUnErroreHannoIlLoroStato() = runTest(dispatcher) {
    val states = MutableStateFlow<NasFolderState>(NasFolderState.Denied)
    val scripted = object : NasFolders {
      override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> = states
      override suspend fun refresh(share: String, dir: SharePath?) = Unit
    }
    val vm = viewModel(scripted)
    vm.goTo(FolderWhere("test", null))
    settle()
    assertThat(vm.state.value.listing).isEqualTo(NasFolderState.Denied)
    assertThat(vm.state.value.entries).isEmpty()
    states.value = NasFolderState.Error(dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode.UNEXPECTED)
    settle()
    assertThat(vm.state.value.listing).isInstanceOf(NasFolderState.Error::class.java)
  }

  @Test
  fun leOperazioniInCorsoSiVedonoESiRileggeQuandoFiniscono() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    assertThat(vm.state.value.activeOperations).isEqualTo(0)
    // Un'operazione vera (nota nel cestino): finita subito, l'elenco non la contiene piu'.
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    assertThat(kit.operations.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
    assertThat(vm.state.value.activeOperations).isEqualTo(0)
    assertThat(vm.state.value.entries.map { it.name }).doesNotContain("nota.txt")
  }

  @Test
  fun unPianoRifiutatoDiceIlMotivoSenzaFoglio() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    kit.deleteFromPc("test", "nota.txt")
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    // Il file non c'e' piu' (cancellato dal PC): il piano non nasce, e la pagina lo dice.
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.notice?.text).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.planRejectionRes(PlanRejection.SOURCE_MISSING))
  }
}
