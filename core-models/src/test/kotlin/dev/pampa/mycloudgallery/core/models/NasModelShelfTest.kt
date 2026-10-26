package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfDevices
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.shelf.NasModelShelf
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.testing.InMemoryModelShelf
import dev.pampa.mycloudgallery.core.models.testing.InMemoryShelfWriter
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 6, P3 (§2.1 "Copia sul NAS", D18, F4-D31): lo scaffale sul NAS. Pubblicazione ESCLUSIVA nella cartella del proprio dispositivo (mai
 * una sostituzione, anche con collisioni e due pubblicazioni insieme), solo da un file verificato adesso, solo se la share e'
 * pubblicabile; `pruneOwn` toglie solo le PROPRIE copie che l'app non elenca piu'; `copies` legge i dispositivi della famiglia con nomi
 * che hanno la forma di un id. Sul NAS finto con `FaultPlan`, e la stessa suite sul fake di P0 (parita', §5.2).
 */
class NasModelShelfTest {
  private val share = "test-comune"
  private val nas = InMemoryNasMedia().apply { addShare(share) }
  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val bytes = TestModels.bytes(200_000, seed = 3)
  private val rig = ModelRig(bytes).also { it.installFinal() }
  private val spec = rig.spec
  private val writer = InMemoryShelfWriter(nas, me)

  @Volatile private var blocked: String? = null

  /** I dispositivi del registro firmato (I5): di serie solo `other`; `null` = il registro non si e' potuto leggere. */
  @Volatile private var trusted: Set<String>? = setOf(other)
  private val devices = ModelShelfDevices { trusted }
  private val shelf = NasModelShelf(me, rig.files, nas.reader, writer, ModelShelfGate { blocked }, devices)

  private fun pathOf(device: String, sha: Sha256 = spec.sha256): String = NasLayout.modelCopy(device, sha.hex).value

  private fun publish(s: ModelSpec = spec, shelf: NasModelShelf = this.shelf): ShelfResult = runBlocking { shelf.publish(s, share) }

  // --- Pubblicazione esclusiva ----------------------------------------------------------------------------------------------------

  @Test
  fun pubblicaNellaPropriaCartellaEBasta() {
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    assertThat(nas.bytes(share, pathOf(me))).isEqualTo(bytes)
    assertThat(writer.written.map { it.value }).containsExactly(pathOf(me))
    // Sul NAS e' comparso un solo file, nella cartella del proprio dispositivo.
    assertThat(nas.filePaths(share)).containsExactly(pathOf(me))
  }

  @Test
  fun unaCopiaGiaPresenteNonSiSostituisceMai() {
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    assertThat(publish()).isEqualTo(ShelfResult.AlreadyThere)
    // Collisione: qualcuno ha messo un altro file con quel nome (stessa dimensione, byte diversi): resta com'e'.
    val tampered = TestModels.flipped(bytes, 77)
    nas.modify(share, pathOf(me), NasContent.of(tampered))
    assertThat(publish()).isEqualTo(ShelfResult.AlreadyThere)
    assertThat(nas.bytes(share, pathOf(me))).isEqualTo(tampered)
    assertThat(writer.written).hasSize(1)
  }

  @Test
  fun unaCartellaAlPostoDellaCopiaNonSiSostituisce() {
    nas.putDir(share, pathOf(me))
    assertThat(publish()).isEqualTo(ShelfResult.Failed)
    assertThat(writer.written).isEmpty()
  }

  @Test
  fun leCopieDegliAltriNonSonoUnaCollisione() {
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    assertThat(nas.bytes(share, pathOf(other))).isEqualTo(bytes)
    assertThat(nas.filePaths(share)).containsExactly(pathOf(me), pathOf(other))
  }

  @Test
  fun dueDispositiviCheSiPubblicanoInsiemeNonSiPestano() = runBlocking<Unit> {
    val otherWriter = InMemoryShelfWriter(nas, other)
    val otherShelf = NasModelShelf(other, rig.files, nas.reader, otherWriter, ModelShelfGate { null }, devices)
    val results = listOf(
      async(Dispatchers.IO) { shelf.publish(spec, share) },
      async(Dispatchers.IO) { otherShelf.publish(spec, share) },
    ).awaitAll()
    assertThat(results).containsExactly(ShelfResult.Published, ShelfResult.Published)
    assertThat(nas.filePaths(share)).containsExactly(pathOf(me), pathOf(other))
  }

  @Test
  fun laStessaPubblicazioneInsiemeDaDueRichiesteDaUnaSolaCopia() = runBlocking<Unit> {
    val results = (1..6).map { async(Dispatchers.IO) { shelf.publish(spec, share) } }.awaitAll()
    assertThat(results.count { it == ShelfResult.Published }).isEqualTo(1)
    assertThat(results.count { it == ShelfResult.AlreadyThere }).isEqualTo(5)
    assertThat(nas.bytes(share, pathOf(me))).isEqualTo(bytes)
  }

  @Test
  fun sottoUnBloccoDiScritturaNonSiScriveNiente() {
    blocked = "LOCAL_ONLY"
    assertThat(publish()).isEqualTo(ShelfResult.Blocked("LOCAL_ONLY"))
    assertThat(writer.requested).isEmpty()
    assertThat(nas.filePaths(share)).isEmpty()
    blocked = null
    assertThat(publish()).isEqualTo(ShelfResult.Published)
  }

  @Test
  fun ilRitaglioDentroLApkNonSiPubblica() {
    assertThat(publish(ModelCatalog.CUTOUT_MAGICTOUCH)).isEqualTo(ShelfResult.Blocked("bundled"))
    assertThat(writer.requested).isEmpty()
  }

  @Test
  fun unModelloLocaleRovinatoNonArrivaMaiSulNas() {
    rig.finalFile.writeBytes(TestModels.flipped(bytes, 5))
    assertThat(publish()).isEqualTo(ShelfResult.Failed)
    assertThat(writer.requested).isEmpty()
    assertThat(nas.filePaths(share)).isEmpty()
    // E il modello locale rifiutato e' stato tolto, come a ogni apertura.
    assertThat(rig.finalFile.exists()).isFalse()
  }

  @Test
  fun unModelloCheNonCEFallisceSenzaScrivere() {
    rig.finalFile.delete()
    assertThat(publish()).isEqualTo(ShelfResult.Failed)
    assertThat(writer.requested).isEmpty()
  }

  @Test
  fun unaSpecificaConImprontaDiversaDaQuellaVerificataFallisce() {
    // Lo stesso id con un'altra impronta (un catalogo passato male): il file verificato non e' quello chiesto.
    val wrong = spec.copy(sha256 = Sha256.of(byteArrayOf(1)))
    assertThat(publish(wrong)).isEqualTo(ShelfResult.Failed)
    assertThat(writer.requested).isEmpty()
  }

  @Test
  fun unaScritturaCheFalliscePrimaDelFondoNonLasciaNienteEPoiRiesce() {
    for (failure in listOf<ShelfWrite>(ShelfWrite.NoSpace, ShelfWrite.NetworkError, ShelfWrite.Refused("sola lettura"))) {
      writer.failNext = failure
      assertThat(publish()).isEqualTo(ShelfResult.Failed)
      assertThat(nas.filePaths(share)).isEmpty()
    }
    writer.failAfterBytes = 100_000
    assertThat(publish()).isEqualTo(ShelfResult.Failed)
    assertThat(nas.filePaths(share)).isEmpty()
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    assertThat(nas.bytes(share, pathOf(me))).isEqualTo(bytes)
  }

  @Test
  fun unNasCheNonRispondeEUnFallimento() {
    nas.failWith = NasError.Timeout
    assertThat(publish()).isEqualTo(ShelfResult.Failed)
    nas.failWith = null
    assertThat(publish()).isEqualTo(ShelfResult.Published)
  }

  @Test
  fun lAdattatoreVeroDovraRifiutareOgniAltroPercorso() {
    // Il contratto dello scrittore (lo prova il fake, lo dovra' rispettare l'adattatore di core-nas): solo la propria cartella.
    val foreign = SharePath.parse(pathOf(other))
    val source = object : ShelfSource {
      override val size: Long = 1

      override fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int = if (offset == 0L) { buf[off] = 1; 1 } else -1
    }
    assertThat(runBlocking { writer.publishExclusive(share, foreign, source) }).isEqualTo(ShelfWrite.Refused("outside_own_folder"))
    for (bad in listOf("a/b/c", ".mycloudgallery/devices/$me/models/nothex", ".mycloudgallery/devices/$me/ai/head/${"0".repeat(64)}", ".mycloudgallery/devices/$me/models/x/${"0".repeat(64)}")) {
      assertThat(runBlocking { writer.publishExclusive(share, SharePath.parse(bad), source) }).isInstanceOf(ShelfWrite.Refused::class.java)
    }
    assertThat(runBlocking { writer.deleteOwn(share, foreign, 1) }).isEqualTo(ShelfDelete.Failed)
    assertThat(nas.filePaths(share)).isEmpty()
  }

  @Test
  fun unaSpecificaDiUnaltroDispositivoNonSiCostruisce() {
    assertThrows(IllegalArgumentException::class.java) { NasModelShelf("non-un-id", rig.files, nas.reader, writer, ModelShelfGate { null }, devices) }
    assertThrows(IllegalArgumentException::class.java) { NasModelShelf(other, rig.files, nas.reader, writer, ModelShelfGate { null }, devices) }
  }

  // --- copies ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun leCopieDeiDispositiviFidatiSiLeggonoConLaLoroDimensione() = runBlocking<Unit> {
    val third = TrustIds.random()
    trusted = setOf(other, third)
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    nas.putFile(share, pathOf(third), NasContent.of(bytes.copyOf(10)))
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    val copies = shelf.copies(share, spec.sha256)
    // La copia propria non e' una sorgente (il dispositivo ha gia' il file); le altre si.
    assertThat(copies.map { it.device }).containsExactlyElementsIn(listOf(other, third).sorted()).inOrder()
    assertThat(copies.associate { it.device to it.sizeBytes }).containsExactly(other, bytes.size.toLong(), third, 10L)
    assertThat(copies.all { it.share == share && it.sha256 == spec.sha256 }).isTrue()
    // Un'altra impronta non ha copie.
    assertThat(shelf.copies(share, ModelCatalog.INDEX_440M.sha256)).isEmpty()
  }

  /** I5 (verifica della Fase 6): chi scrive in `.mycloudgallery/devices/` non fa di una cartella con la forma di un id una sorgente. */
  @Test
  fun i5_unaCartellaConLaFormaDiUnIdNonEUnaSorgenteSeNonEnelRegistroFirmato() = runBlocking<Unit> {
    val stranger = TrustIds.random()
    nas.putFile(share, pathOf(stranger), NasContent.of(bytes))
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    assertThat(shelf.copies(share, spec.sha256).map { it.device }).containsExactly(other)
    // Dopo l'approvazione sul registro firmato, e solo allora, e' una sorgente.
    trusted = setOf(other, stranger)
    assertThat(shelf.copies(share, spec.sha256).map { it.device }).containsExactlyElementsIn(listOf(other, stranger).sorted())
    // Dismesso (fuori dal registro): di nuovo niente.
    trusted = setOf(other)
    assertThat(shelf.copies(share, spec.sha256).map { it.device }).containsExactly(other)
  }

  @Test
  fun i5_senzaLoElencoFirmatoNonSiLeggeNienteSulNas() = runBlocking<Unit> {
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    trusted = null
    val reads = nas.statCalls.get() + nas.listCalls.get()
    assertThat(shelf.copies(share, spec.sha256)).isEmpty()
    // Nemmeno una stat: senza registro lo scaffale non tocca il NAS.
    assertThat(nas.statCalls.get() + nas.listCalls.get()).isEqualTo(reads)
  }

  @Test
  fun i5_lElencoDelleCartelleDeiDispositiviNonSiLeggeMai() = runBlocking<Unit> {
    // Una cartella con 300 finti dispositivi non costa 300 letture ne' un elenco: si guardano solo i dispositivi del registro.
    repeat(300) { nas.putFile(share, pathOf(TrustIds.random()), NasContent.of(ByteArray(3))) }
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    val lists = nas.listCalls.get()
    val stats = nas.statCalls.get()
    assertThat(shelf.copies(share, spec.sha256).map { it.device }).containsExactly(other)
    assertThat(nas.listCalls.get()).isEqualTo(lists)
    assertThat(nas.statCalls.get() - stats).isEqualTo(1)
  }

  @Test
  fun iNomiDelRegistroCheNonSonoUnIdSiIgnorano() = runBlocking<Unit> {
    val sha = spec.sha256.hex
    val bad = listOf("x y", "ABCDEFGHJKMNPQRSTVWXYZ0123", "a".repeat(25), "0".repeat(27), me.uppercase(), "con", "../" + other)
    for (name in bad) nas.putFile(share, ".mycloudgallery/devices/$name/models/$sha", NasContent.of(bytes))
    // Un dispositivo vero, ma con una cartella al posto della copia, o con un collegamento.
    val withDir = TrustIds.random()
    nas.putDir(share, ".mycloudgallery/devices/$withDir/models/$sha")
    val withLink = TrustIds.random()
    nas.putFile(share, ".mycloudgallery/devices/$withLink/models/$sha", NasContent.of(bytes), attributes = NasAttributes.ARCHIVE or NasAttributes.REPARSE_POINT)
    trusted = bad.toSet() + withDir + withLink
    assertThat(shelf.copies(share, spec.sha256)).isEmpty()
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    trusted = (bad + withDir + withLink + other).toSet()
    assertThat(shelf.copies(share, spec.sha256).map { it.device }).containsExactly(other)
  }

  @Test
  fun inFamigliaCiSonoPocheCartelleDiDispositivo() = runBlocking<Unit> {
    val many = List(300) { TrustIds.random() }
    many.forEach { nas.putFile(share, pathOf(it), NasContent.of(ByteArray(3))) }
    trusted = many.toSet()
    assertThat(shelf.copies(share, spec.sha256).size).isAtMost(ModelLimits.MAX_NAS_DEVICES)
  }

  @Test
  fun senzaNasNiente() = runBlocking<Unit> {
    nas.failWith = NasError.Timeout
    assertThat(shelf.copies(share, spec.sha256)).isEmpty()
    assertThat(shelf.pruneOwn(share, setOf(spec.sha256))).isEqualTo(0)
  }

  // --- pruneOwn --------------------------------------------------------------------------------------------------------------------

  @Test
  fun pruneOwnTogliSoloLePropriCopieChenonSiTengono() = runBlocking<Unit> {
    val old1 = Sha256.of(byteArrayOf(1))
    val old2 = Sha256.of(byteArrayOf(2))
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    nas.putFile(share, pathOf(me, old1), NasContent.of(ByteArray(7)))
    nas.putFile(share, pathOf(me, old2), NasContent.of(ByteArray(9)))
    // Le copie degli altri (anche di modelli che l'app non elenca) non si toccano mai.
    nas.putFile(share, pathOf(other, old1), NasContent.of(ByteArray(7)))
    nas.putFile(share, pathOf(other), NasContent.of(bytes))
    // Cose che non sono copie: nomi senza forma di impronta, cartelle.
    nas.putFile(share, ".mycloudgallery/devices/$me/models/NOTE.txt", NasContent.of(ByteArray(3)))
    nas.putFile(share, ".mycloudgallery/devices/$me/models/${"A".repeat(64)}", NasContent.of(ByteArray(3)))
    nas.putDir(share, ".mycloudgallery/devices/$me/models/${"c".repeat(64)}")

    assertThat(shelf.pruneOwn(share, setOf(spec.sha256))).isEqualTo(2)
    assertThat(nas.exists(share, pathOf(me))).isTrue()
    assertThat(nas.exists(share, pathOf(me, old1))).isFalse()
    assertThat(nas.exists(share, pathOf(me, old2))).isFalse()
    assertThat(nas.exists(share, pathOf(other, old1))).isTrue()
    assertThat(nas.exists(share, pathOf(other))).isTrue()
    assertThat(nas.exists(share, ".mycloudgallery/devices/$me/models/NOTE.txt")).isTrue()
    assertThat(nas.exists(share, ".mycloudgallery/devices/$me/models/${"A".repeat(64)}")).isTrue()
    assertThat(nas.exists(share, ".mycloudgallery/devices/$me/models/${"c".repeat(64)}")).isTrue()
    // Ogni richiesta allo scrittore e' dentro la propria cartella.
    val ownDir = NasLayout.ROOT.child("devices").child(me).child("models")
    assertThat(writer.requested.all { it.parent() == ownDir }).isTrue()
    assertThat(writer.deleted.map { it.name }).containsExactly(old1.hex, old2.hex)
  }

  @Test
  fun unElencoDaTenereVuotoNonTogliNiente() = runBlocking<Unit> {
    assertThat(publish()).isEqualTo(ShelfResult.Published)
    assertThat(shelf.pruneOwn(share, emptySet())).isEqualTo(0)
    assertThat(nas.exists(share, pathOf(me))).isTrue()
  }

  @Test
  fun sottoUnBloccoNonSiTogliNiente() = runBlocking<Unit> {
    nas.putFile(share, pathOf(me, Sha256.of(byteArrayOf(1))), NasContent.of(ByteArray(7)))
    blocked = "READ_ONLY_AREA"
    assertThat(shelf.pruneOwn(share, setOf(spec.sha256))).isEqualTo(0)
    assertThat(writer.requested).isEmpty()
  }

  @Test
  fun unaCopiaCheCambiaDimensioneFraElencoERimozioneResta() = runBlocking<Unit> {
    val old = Sha256.of(byteArrayOf(1))
    nas.putFile(share, pathOf(me, old), NasContent.of(ByteArray(7)))
    // Il lettore, dopo aver elencato, fa cambiare il file (un altro dispositivo, il PC).
    val racing = object : NasMediaReader by nas.reader {
      override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> {
        val listed = nas.reader.list(share, dir, purpose, pageSize, onPage)
        nas.modify(share, pathOf(me, old), NasContent.of(ByteArray(99)))
        return listed
      }
    }
    val racingShelf = NasModelShelf(me, rig.files, racing, writer, ModelShelfGate { null }, devices)
    assertThat(racingShelf.pruneOwn(share, setOf(spec.sha256))).isEqualTo(0)
    assertThat(nas.exists(share, pathOf(me, old))).isTrue()
    assertThat(runBlocking { writer.deleteOwn(share, SharePath.parse(pathOf(me, old)), 7) }).isEqualTo(ShelfDelete.Changed)
    assertThat(runBlocking { writer.deleteOwn(share, SharePath.parse(pathOf(me, Sha256.of(byteArrayOf(9)))), 1) }).isEqualTo(ShelfDelete.Missing)
  }

  // --- FaultPlan: guasto a ogni chiamata, prima e dopo ---------------------------------------------------------------------------

  @Test
  fun conUnGuastoAOgniChiamataNienteSiRompeEAlGiroDopoRiesce() {
    for (fault in listOf(SimFault.DropConnection, SimFault.ExpireSession, SimFault.Stall)) {
      for (phase in FaultPhase.entries) {
        for (n in 1..7) {
          val localNas = InMemoryNasMedia().apply { addShare(share) }
          val localWriter = InMemoryShelfWriter(localNas, me)
          val localShelf = NasModelShelf(me, rig.files, localNas.reader, localWriter, ModelShelfGate { null }, devices)
          localNas.putFile(share, pathOf(other), NasContent.of(bytes))
          localNas.putFile(share, pathOf(me, Sha256.of(byteArrayOf(1))), NasContent.of(ByteArray(7)))
          localNas.readFaults = FaultPlan().at(n, phase, fault)
          // Nessun guasto fa lanciare un'eccezione: gli esiti sono "meno di prima", mai un errore verso chi chiama.
          runBlocking {
            localShelf.copies(share, spec.sha256)
            localShelf.pruneOwn(share, setOf(spec.sha256))
            localShelf.publish(spec, share)
          }
          // Al giro dopo, senza guasti, lo scaffale e' nello stato giusto e non c'e' mai stata una copia a meta'.
          localNas.readFaults = null
          runBlocking {
            localShelf.pruneOwn(share, setOf(spec.sha256))
            assertThat(localShelf.publish(spec, share)).isIn(listOf(ShelfResult.Published, ShelfResult.AlreadyThere))
            assertThat(localNas.bytes(share, pathOf(me))).isEqualTo(bytes)
            assertThat(localShelf.copies(share, spec.sha256).map { it.device }).containsExactlyElementsIn(listOf(other))
          }
          assertThat(localNas.filePaths(share)).containsExactly(pathOf(me), pathOf(other))
        }
      }
    }
  }

  // --- Parita' col fake di P0 (§5.2) ---------------------------------------------------------------------------------------------

  /** La stessa suite per il fake dello scaffale (P0) e per quello vero sul NAS finto. */
  abstract class ShelfContract {
    abstract val spec: ModelSpec
    abstract val ownDevice: String
    abstract val shelf: ModelShelf

    /** Una copia di un altro dispositivo. */
    abstract fun addForeign(device: String, sha: Sha256, size: Long)

    /** Una copia propria che l'app non elenca piu'. */
    abstract fun addOwnStale(sha: Sha256, size: Long)

    abstract fun block(reason: String?)

    abstract val share: String

    @Test
    fun pubblicaUnaVoltaSola() = runBlocking<Unit> {
      assertThat(shelf.publish(spec, share)).isEqualTo(ShelfResult.Published)
      assertThat(shelf.publish(spec, share)).isEqualTo(ShelfResult.AlreadyThere)
    }

    @Test
    fun sottoUnBloccoNonPubblica() = runBlocking<Unit> {
      block("FROZEN")
      assertThat(shelf.publish(spec, share)).isEqualTo(ShelfResult.Blocked("FROZEN"))
      block(null)
      assertThat(shelf.publish(spec, share)).isEqualTo(ShelfResult.Published)
    }

    @Test
    fun leCopieSiVedonoConDispositivoEDimensione() = runBlocking<Unit> {
      val foreign = TrustIds.random()
      addForeign(foreign, spec.sha256, 10)
      shelf.publish(spec, share)
      val copies = shelf.copies(share, spec.sha256)
      // La copia propria non e' una sorgente: il dispositivo ha gia' il file (I5).
      assertThat(copies.map { it.device }).containsExactly(foreign)
      assertThat(copies.single().sizeBytes).isEqualTo(10)
    }

    @Test
    fun pruneOwnTogliSoloLePropriePiuNonElencate() = runBlocking<Unit> {
      val stale = Sha256.of(byteArrayOf(5))
      val foreign = TrustIds.random()
      shelf.publish(spec, share)
      addOwnStale(stale, 4)
      addForeign(foreign, stale, 4)
      assertThat(shelf.pruneOwn(share, setOf(spec.sha256))).isEqualTo(1)
      assertThat(shelf.copies(share, stale).map { it.device }).containsExactly(foreign)
      assertThat(shelf.copies(share, spec.sha256)).isEmpty()
    }
  }

  class FakeShelfContractTest : ShelfContract() {
    private val bytes = TestModels.bytes(5_000, seed = 8)
    override val spec: ModelSpec = TestModels.spec(bytes)
    override val ownDevice: String = TrustIds.random()
    override val share: String = "test-comune"
    private val fake = InMemoryModelShelf(ownDevice)
    override val shelf: ModelShelf = fake

    override fun addForeign(device: String, sha: Sha256, size: Long) = fake.add(ShelfCopy(share, device, sha, size))

    override fun addOwnStale(sha: Sha256, size: Long) = fake.add(ShelfCopy(share, ownDevice, sha, size))

    override fun block(reason: String?) {
      fake.blocked = reason
    }
  }

  class NasShelfContractTest : ShelfContract() {
    private val bytes = TestModels.bytes(5_000, seed = 8)
    private val rig = ModelRig(bytes).also { it.installFinal() }
    private val nas = InMemoryNasMedia().apply { addShare("test-comune") }
    override val spec: ModelSpec = rig.spec
    override val ownDevice: String = TrustIds.random()
    override val share: String = "test-comune"
    @Volatile private var reason: String? = null
    private val known = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override val shelf: ModelShelf =
      NasModelShelf(ownDevice, rig.files, nas.reader, InMemoryShelfWriter(nas, ownDevice), ModelShelfGate { reason }, ModelShelfDevices { known.toSet() })

    override fun addForeign(device: String, sha: Sha256, size: Long) {
      known += device
      nas.putFile(share, NasLayout.modelCopy(device, sha.hex).value, NasContent.of(ByteArray(size.toInt())))
    }

    override fun addOwnStale(sha: Sha256, size: Long) = addForeign(ownDevice, sha, size)

    override fun block(reason: String?) {
      this.reason = reason
    }
  }
}
