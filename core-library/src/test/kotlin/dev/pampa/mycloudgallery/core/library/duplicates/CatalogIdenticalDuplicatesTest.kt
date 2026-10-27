package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.similar.Fx
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * I doppioni identici sul catalogo vero (Room in memoria, Fase 7, P8, D25, §2.1.1): le copie dalla stessa parte con lo stesso contenuto, la
 * proposta di pulizia, il piano per i cestini, i nascosti che non escono mai (D22), E-U1 (area di un altro account), E-U5 (album).
 */
@RunWith(RobolectricTestRunner::class)
class CatalogIdenticalDuplicatesTest : SimilarCatalogTestBase() {

  private val bytes = Random(77).nextBytes(3_000)
  private val fingerprint = Fingerprint.ofContent(bytes)

  private fun finder(): CatalogIdenticalDuplicates {
    // UT-1: i doppioni identici si cercano solo con la Libreria leggibile per intero (l'istantanea c'e'); una vuota basta, i gruppi vengono dal catalogo.
    val snapshot = MutableStateFlow<TimelineSnapshot?>(ColumnarTimelineSnapshot.EMPTY)
    return CatalogIdenticalDuplicates(catalog, CatalogVisibleMedia(db, snapshot))
  }

  private var msId = 500L

  private suspend fun phoneCopy(folder: String, name: String, mtime: Long, content: Fingerprint = fingerprint): MediaId {
    val location = Location.Phone(Fx.VOLUME, folder, name, ++msId, Fx.STORE)
    val observation = Observation(location, FileStamp(content.size, mtime, 7), content, MediaKind.PHOTO, MediaSource.CAMERA, Fx.SECOND0)
    return catalog.record(observation).mediaId!!
  }

  private suspend fun nasCopy(share: String, path: String, mtime: Long, content: Fingerprint = fingerprint): MediaId {
    val location = Location.Nas(Fx.ME, share, NasPath.of(path), fileId = mtime)
    val observation = Observation(location, FileStamp(content.size, mtime), content, MediaKind.PHOTO, MediaSource.OTHER)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
  }

  @Test
  fun dueCopieSulTelefonoSonoUnDoppione_UnaSolaPerParteNonLo() = runBlocking<Unit> {
    val media = phoneCopy("DCIM/Camera/", "IMG_1.jpg", mtime = 100)
    assertThat(phoneCopy("Download/", "IMG_1.jpg", mtime = 900)).isEqualTo(media)
    // Una copia sul telefono e una sul NAS sono il caso comune di "al sicuro", non un doppione.
    nasCopy("alessio", "Backup/pixel/2026/IMG_1.jpg", mtime = 200)

    val scan = finder().scan()

    assertThat(scan.proposals).hasSize(1)
    val proposal = scan.proposals.single()
    assertThat(proposal.group.part).isEqualTo(CopyPart.PHONE)
    assertThat(proposal.group.copies).hasSize(2)
    assertThat(proposal.status).isEqualTo(IdenticalStatus.CLEANABLE)
    assertThat(proposal.keep!!.path).isEqualTo("DCIM/Camera/IMG_1.jpg")
    assertThat(proposal.remove.single().path).isEqualTo("Download/IMG_1.jpg")
    assertThat(scan.copiesToRemove).isEqualTo(1)
  }

  @Test
  fun ilPianoPortaLeCopieDelTelefonoEDelNasNeiCestiniConIlTimbroVisto() = runBlocking<Unit> {
    val media = phoneCopy("DCIM/Camera/", "IMG_2.jpg", mtime = 100)
    phoneCopy("Download/", "IMG_2.jpg", mtime = 900)
    nasCopy("alessio", "Backup/pixel/2026/IMG_2.jpg", mtime = 300)
    nasCopy("alessio", "Foto/IMG_2.jpg", mtime = 700)
    val finder = finder()

    val scan = finder.scan()
    assertThat(scan.proposals).hasSize(2)
    val plan = finder.plan(scan.cleanable)

    assertThat(plan.groups).isEqualTo(2)
    assertThat(plan.phone).hasSize(1)
    assertThat(plan.phone.single().ref.volume).isEqualTo(Fx.VOLUME)
    assertThat(plan.phone.single().ref.mediaStoreId).isAtLeast(500L)
    assertThat(plan.nas).hasSize(1)
    val nas = plan.nas.single()
    assertThat(nas.share).isEqualTo("alessio")
    assertThat(nas.path).isEqualTo("Foto/IMG_2.jpg")
    assertThat(nas.modifiedAt).isEqualTo(700)
    assertThat(nas.size).isEqualTo(fingerprint.size)
    assertThat(nas.area).isEqualTo(CopyArea.MY_PRIVATE)
    assertThat(plan.copies).isEqualTo(2)
    assertThat(plan.bytes).isEqualTo(2 * fingerprint.size)
    assertThat(catalog.media(media)!!.locations.count { it.isPresent }).isEqualTo(4)
  }

  @Test
  fun unaCopiaPrivataEUnaComuneNonSiToccano_MaiLUltimaCopiaPrivata() = runBlocking<Unit> {
    nasCopy("alessio", "Foto/a.jpg", mtime = 500)
    nasCopy("NAS", "Famiglia/a.jpg", mtime = 100)

    val scan = finder().scan()

    val proposal = scan.proposals.single()
    assertThat(proposal.status).isEqualTo(IdenticalStatus.NOTHING_TO_REMOVE)
    assertThat(proposal.blocked.map { it.reason }).containsExactly(BlockReason.LAST_PRIVATE)
    assertThat(finder().plan(scan.proposals).isEmpty).isTrue()
  }

  @Test
  fun e_u1_UnaCopiaNellAreaDiUnAltroAccountNonSiProponeESiConta() = runBlocking<Unit> {
    nasCopy("alessio", "Foto/a.jpg", mtime = 100)
    nasCopy("alessio", "Foto/b.jpg", mtime = 200)
    // La share "marco" non e' un'area che questo account conosce (ne' privata ne' comune): area sconosciuta = di un altro.
    nasCopy("marco", "Foto/c.jpg", mtime = 50)
    val finder = finder()

    val scan = finder.scan()

    val proposal = scan.proposals.single()
    assertThat(proposal.inOthersArea).isEqualTo(1)
    assertThat(proposal.remove.map { it.path }).containsExactly("Foto/b.jpg")
    val plan = finder.plan(scan.cleanable)
    assertThat(plan.nas.map { it.share to it.path }).containsExactly("alessio" to "Foto/b.jpg")
  }

  @Test
  fun senzaHashCompletoIlDoppioneEDaVerificare_ENonEntraNelPiano() = runBlocking<Unit> {
    val noHash = Fingerprint(bytes.size.toLong(), QuickHashAlgorithm.ofContent(bytes), fullHash = null)
    val media = nasCopy("alessio", "Video/v.mp4", mtime = 100, content = noHash)
    val version = catalog.media(media)!!.currentVersionId
    // Una seconda copia che l'app ha scritto da sola (copia verificata): due posizioni della stessa versione, nessun hash completo.
    catalog.attachCopy(media, version, Location.Nas(Fx.ME, "alessio", NasPath.of("Video/v2.mp4")), FileStamp(noHash.size, 300), verifiedAtMillis = null)
    val finder = finder()

    val scan = finder.scan()

    val proposal = scan.proposals.single()
    assertThat(proposal.status).isEqualTo(IdenticalStatus.TO_VERIFY)
    assertThat(proposal.group.verified).isFalse()
    assertThat(scan.toVerify).hasSize(1)
    assertThat(proposal.remove).isEmpty()
    assertThat(finder.plan(scan.proposals).isEmpty).isTrue()
  }

  @Test
  fun iNascostiNonEscono_NelloScanNelPianoDopoAverlaLetta() = runBlocking<Unit> {
    val shown = phoneCopy("DCIM/Camera/", "A.jpg", mtime = 100)
    phoneCopy("Download/", "A.jpg", mtime = 900)
    val other = Fingerprint.ofContent(Random(78).nextBytes(2_500))
    val secret = phoneCopy("DCIM/Camera/", "B.jpg", mtime = 100, content = other)
    phoneCopy("Download/", "B.jpg", mtime = 900, content = other)
    val finder = finder()

    // Nascosto prima dello scan: il gruppo non c'e'.
    catalog.setPersonalFlags(listOf(secret)) { it.copy(hidden = true) }
    val scan = finder.scan()
    assertThat(scan.proposals.map { it.group.media }).containsExactly(shown)

    // Nascosto dopo lo scan: il piano lo lascia fuori (l'ultimo filtro e' quello del piano).
    val before = finder.scan().proposals
    catalog.setPersonalFlags(listOf(shown)) { it.copy(hidden = true) }
    assertThat(finder.plan(before).isEmpty).isTrue()
    assertThat(finder.scan().proposals).isEmpty()
  }

  @Test
  fun unaCopiaNonPiuPresenteNonEntraNelPiano() = runBlocking<Unit> {
    val media = phoneCopy("DCIM/Camera/", "IMG_3.jpg", mtime = 100)
    phoneCopy("Download/", "IMG_3.jpg", mtime = 900)
    val finder = finder()
    val proposals = finder.scan().cleanable
    // Prima del piano l'utente la toglie da solo (o sparisce): non si chiede il cestino per un file che non c'e'.
    val gone = catalog.media(media)!!.locations.first { it.location.let { l -> l is Location.Phone && l.displayName == "IMG_3.jpg" && l.relativePath == "Download/" } }
    catalog.markVanished(gone.location.key)

    val plan = finder.plan(proposals)

    assertThat(plan.isEmpty).isTrue()
    assertThat(plan.skipped).isEqualTo(1)
  }

  @Test
  fun e_u5_UnMediaEliminatoEsceDaAlbumESeTornaDalCestinoRientra() = runBlocking<Unit> {
    val media = phoneCopy("DCIM/Camera/", "IMG_5.jpg", mtime = 100)
    val albumId = "00000000-0000-4000-8000-0000000000e5"
    db.albumDao().upsert(AlbumEntity(albumId, "Prova", coverMediaId = null, createdAt = now, updatedAt = now))
    db.albumDao().upsertItem(AlbumItemEntity(albumId, media.value, position = 1_000, addedAt = now))
    suspend fun inAlbum() = db.albumDao().summaries().single { it.albumId == albumId }.visibleCount

    assertThat(inAlbum()).isEqualTo(1)

    // La pulizia manda nel cestino l'unica copia: il media non e' piu' da nessuna parte, e l'album non lo conta.
    val location = catalog.media(media)!!.locations.single().location
    catalog.markVanished(location.key)
    assertThat(catalog.albums.visibleAmong(listOf(media))).isEmpty()
    assertThat(db.albumDao().summaries().none { it.albumId == albumId }).isTrue()
    // L'elemento resta nell'album (stato): niente e' stato tolto.
    assertThat(db.albumDao().items(albumId).map { it.mediaId }).containsExactly(media.value)

    // Dal cestino torna lo stesso file, nello stesso posto: stesso media, stesso id, e rientra nell'album.
    val back = phoneCopy("DCIM/Camera/", "IMG_5.jpg", mtime = 100)
    assertThat(back).isEqualTo(media)
    assertThat(catalog.albums.visibleAmong(listOf(media))).containsExactly(media)
    assertThat(inAlbum()).isEqualTo(1)
  }

  @Test
  fun ilCatalogoSenzaDoppioniDaUnaScansioneVuota() = runBlocking<Unit> {
    phoneCopy("DCIM/Camera/", "IMG_6.jpg", mtime = 100)
    val snapshot: TimelineSnapshot = CatalogTimelineSource(db, catalog).buildIndex()
    assertThat(snapshot.size).isEqualTo(1)
    assertThat(finder().scan().proposals).isEmpty()
    assertThat(NoIdenticalDuplicates.scan().proposals).isEmpty()
    assertThat(AccountId.of("alessio")).isEqualTo(Fx.ME)
  }
}
