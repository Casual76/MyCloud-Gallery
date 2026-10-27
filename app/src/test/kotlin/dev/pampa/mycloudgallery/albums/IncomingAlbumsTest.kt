package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingAlbumChoice
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingAlbumCreate
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Fase 7 (P13; P10-10, D35, D12): "Aggiungi ad album" dalla condivisione in arrivo. La promessa si registra su disco (sopravvive al processo); quando i
 * file dello stage sono nel catalogo un album personale li riceve, uno **condiviso** no: si apre la scelta con l'album gia' scelto (la conferma col
 * conteggio la da' l'utente), e la promessa si chiude solo quando l'utente ha deciso. Finche' un file e' in coda si aspetta; senza file arrivati la
 * promessa si chiude senza fare niente.
 */
class IncomingAlbumsTest {

  private val dir = Files.createTempDirectory("promesse").toFile()
  private val file = File(dir, "incoming-promises")
  private val library = FakeAlbumLibrary()
  private val albums = InMemoryAlbums(library, FakeAlbumFamily(AccountId("test")), FakeAlbumClocks())
  private val requests = AlbumRequests()
  private val stages = HashMap<String, AppIncomingAlbums.StageMedia>()

  private fun port(promises: FileIncomingPromises = FileIncomingPromises(file)) =
    AppIncomingAlbums({ albums }, { NoSharedAlbums }, promises, { stage -> stages[stage.value] ?: AppIncomingAlbums.StageMedia(emptyList(), waiting = true) }, requests)

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  private fun shot(n: Int) = FakeMedia(MediaId(UUID(9L, n.toLong()).toString()), takenAtUtcMs = 1_760_000_000_000L - n * 60_000L)

  private suspend fun manual(title: String): AlbumId = (albums.create(title, AlbumKind.MANUAL) as CreateOutcome.Created).id

  @Test
  fun lAlbumPersonaleRiceveIFileQuandoSonoNelCatalogo() = runBlocking<Unit> {
    val photos = (1..2).map { shot(it) }
    library.put(*photos.toTypedArray())
    val album = manual("Ricevute")
    val port = port()
    val choice = port.albums().single()
    assertThat(choice.shared).isFalse()

    assertThat(port.willAdd(IncomingStageId("stage-1"), choice)).isTrue()
    // Un file ancora in coda: si aspetta.
    port.resolve()
    assertThat(checkNotNull(albums.content(album).first()).items.size).isEqualTo(0)

    // La promessa sopravvive al processo: un'altra istanza la ritrova e la mantiene.
    stages["stage-1"] = AppIncomingAlbums.StageMedia(photos.map { it.id }, waiting = false)
    val reborn = port(FileIncomingPromises(file))
    reborn.resolve()
    assertThat(checkNotNull(albums.content(album).first()).items.size).isEqualTo(2)
    assertThat(FileIncomingPromises(file).all()).isEmpty()
    // Un album personale non chiede niente all'utente.
    assertThat(requests.addToAlbum.value).isNull()
  }

  @Test
  fun unAlbumCondivisoNonRiceveMaiDaSoloSiApreLaSceltaConLAlbumGiaScelto() = runBlocking<Unit> {
    val photos = (1..3).map { shot(it) }
    library.put(*photos.toTypedArray())
    val port = port()
    val shared = AlbumId(UUID(5L, 5L).toString())
    assertThat(port.willAdd(IncomingStageId("stage-2"), IncomingAlbumChoice(shared.value, "Prova Oslo", shared = true))).isTrue()
    stages["stage-2"] = AppIncomingAlbums.StageMedia(photos.map { it.id }, waiting = false)

    port.resolve()
    val request = checkNotNull(requests.addToAlbum.value)
    assertThat(request.album).isEqualTo(shared)
    assertThat(request.media).containsExactlyElementsIn(photos.map { it.id })
    // Un altro giro non la chiede due volte, e la promessa resta finche' l'utente non ha deciso.
    port.resolve()
    assertThat(requests.addToAlbum.value).isSameInstanceAs(request)
    assertThat(FileIncomingPromises(file).all().keys).containsExactly("stage-2")

    requests.done(request)
    port.resolve()
    assertThat(FileIncomingPromises(file).all()).isEmpty()
    assertThat(requests.addToAlbum.value).isNull()
  }

  @Test
  fun senzaFileArrivatiLaPromessaSiChiudeSenzaFareNiente() = runBlocking<Unit> {
    val album = manual("Vuoto")
    val port = port()
    port.willAdd(IncomingStageId("stage-3"), IncomingAlbumChoice(album.value, "Vuoto", shared = false))
    stages["stage-3"] = AppIncomingAlbums.StageMedia(emptyList(), waiting = false)

    port.resolve()

    assertThat(FileIncomingPromises(file).all()).isEmpty()
    assertThat(checkNotNull(albums.content(album).first()).items.size).isEqualTo(0)
  }

  @Test
  fun gliAlbumSmartNonSiOffrono() = runBlocking<Unit> {
    manual("A mano")
    assertThat(albums.create("Smart", AlbumKind.SMART, SmartRules(period = null, place = null, text = "mare"))).isInstanceOf(CreateOutcome.Created::class.java)
    assertThat(port().albums().map { it.name }).containsExactly("A mano")
  }

  // --- AA-1, AA-2 ---------------------------------------------------------------------------------------------------------------

  @Test
  fun laPrimaLetturaAspettaGliAlbumMontatiENonDiceNessunAlbum_AA1() = runBlocking<Unit> {
    val port = port()
    // L'app era chiusa e gli album si montano: lo stato dice «sto leggendo» e l'elenco e' ancora vuoto.
    albums.status.value = AlbumsStatus.Loading
    val read = async { port.albums() }
    manual("Compleanno")
    albums.status.value = AlbumsStatus.Ready
    assertThat(read.await().map { it.name }).containsExactly("Compleanno")
  }

  @Test
  fun glialbumSeguonoLaPortaNelTempo_AA1() = runBlocking<Unit> {
    val port = port()
    val sawEmpty = CompletableDeferred<Unit>()
    // Il collettore parte e vede l'elenco vuoto di adesso; solo dopo nasce l'album: prima la schermata leggeva una volta sola e non lo vedeva mai.
    val got = async(start = CoroutineStart.UNDISPATCHED) { port.choices().onEach { sawEmpty.complete(Unit) }.first { it.isNotEmpty() } }
    sawEmpty.await()
    manual("Nuovo")
    assertThat(got.await().map { it.name }).containsExactly("Nuovo")
  }

  @Test
  fun unNuovoAlbumDallaRicezioneSiCreaEsiOffre_AA2() = runBlocking<Unit> {
    val port = port()
    assertThat(port.canCreate).isTrue()
    val created = port.create("  Mare 2026 ") as IncomingAlbumCreate.Created
    assertThat(created.choice.name).isEqualTo("Mare 2026")
    assertThat(created.choice.shared).isFalse()
    assertThat(port.albums().map { it.id }).containsExactly(created.choice.id)
    // Il nome e' un dato: un album a mano, mai smart e mai condiviso.
    assertThat(albums.all.value.albums.single()).isInstanceOf(dev.pampa.mycloudgallery.core.albums.contract.Album.Manual::class.java)
  }

  @Test
  fun unNomeNonValidoNonCreaNiente_AA2() = runBlocking<Unit> {
    val port = port()
    assertThat(port.create("   ")).isEqualTo(IncomingAlbumCreate.InvalidTitle)
    assertThat(port.create("x".repeat(65))).isEqualTo(IncomingAlbumCreate.InvalidTitle)
    assertThat(albums.all.value.albums).isEmpty()
  }
}
