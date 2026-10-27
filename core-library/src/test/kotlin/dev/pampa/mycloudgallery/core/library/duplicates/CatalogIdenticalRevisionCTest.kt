package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.similar.Fx
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Revisione di Fase 7, pacchetto C, sul catalogo vero (Room in memoria): **DP-1** i doppioni identici fra cartelle del NAS (file senza hash completo,
 * come li registra la scansione vera), **DP-2** la copia dell'area comune che e' un elemento di un album o ha la gemella del guardiano, **R5** il piano
 * che si rifa' sui dati di adesso, **R18** il timbro delle copie del telefono.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogIdenticalRevisionCTest : SimilarCatalogTestBase() {

  private val bytes = Random(91).nextBytes(3_000)
  private val full = Fingerprint.ofContent(bytes)

  /** L'impronta come la dice la scansione del NAS: dimensione e hash rapido, **mai** l'hash completo. */
  private fun scanned(content: ByteArray = bytes) = Fingerprint(content.size.toLong(), QuickHashAlgorithm.ofContent(content), fullHash = null)

  private fun finder(
    sharedMembers: Set<MediaId> = emptySet(),
    twins: Boolean = true,
    maxSets: Int = 200,
  ): CatalogIdenticalDuplicates {
    val snapshot = MutableStateFlow<TimelineSnapshot?>(ColumnarTimelineSnapshot.EMPTY)
    return CatalogIdenticalDuplicates(
      catalog, CatalogVisibleMedia(db, snapshot),
      sharedAlbums = AlbumMembership { media -> media.filterTo(HashSet()) { it in sharedMembers } },
      twins = if (twins) NasTwins(catalog, db, Fx.ME, maxSets = maxSets) else null,
    )
  }

  private var ordinal = 0L

  private suspend fun nas(
    share: String,
    path: String,
    content: Fingerprint,
    kind: MediaKind = MediaKind.PHOTO,
    mtime: Long = 100,
  ): MediaId {
    val location = Location.Nas(Fx.ME, share, NasPath.of(path), fileId = ++ordinal + mtime * 1_000)
    val observation = Observation(location, FileStamp(content.size, mtime), content, kind, MediaSource.OTHER)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
  }

  /** Cio' che fa il giro di sicurezza del backup quando prende la coda `FULL_HASH`: legge il file e consegna l'hash completo al catalogo. */
  private suspend fun hashed(media: MediaId, content: Fingerprint = full) {
    catalog.recordFullHash(media, versionOf(media), content.fullHash!!)
  }

  private suspend fun fullHashQueued(media: MediaId): Boolean =
    catalog.workStates(media).any { it.stage == WorkStage.FULL_HASH && it.status == WorkStatus.PENDING }

  // --- DP-1 ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun dp1_dueFileUgualiInDueCartelleDelNas_sonoDueMediaEPrimaNonSiTrovano() = runBlocking<Unit> {
    val a = nas("alessio", "Foto/Oslo/a.jpg", scanned())
    val b = nas("alessio", "Vacanze/2026/a.jpg", scanned())

    // La scansione vera li registra separati (nel dubbio non si unisce, KEEP_SEPARATE): due media, nessun doppione. Il difetto.
    assertThat(a).isNotEqualTo(b)
    val before = finder(twins = false).scan()
    assertThat(before.proposals).isEmpty()
  }

  @Test
  fun dp1_ilGiroMetteInCodaLHashCompletoSoloDoveServeEPoiLiUnisce() = runBlocking<Unit> {
    val a = nas("alessio", "Foto/Oslo/a.jpg", scanned())
    val b = nas("alessio", "Vacanze/2026/a.jpg", scanned())
    // Un file qualunque, senza gemelli: non si legge per intero (dove serve, non su tutta la Libreria).
    val alone = nas("alessio", "Foto/solo.jpg", scanned(Random(5).nextBytes(2_000)))
    val finder = finder()

    // Non ancora provati uguali: la pagina non li propone, ma dice che sta controllando.
    val scan = finder.scan()
    assertThat(scan.proposals).isEmpty()
    assertThat(scan.verifying).isEqualTo(1)

    val first = finder.settle(1.minutes)

    assertThat(first.queued).isEqualTo(2)
    assertThat(first.merged).isEqualTo(0)
    assertThat(fullHashQueued(a)).isTrue()
    assertThat(fullHashQueued(b)).isTrue()
    assertThat(fullHashQueued(alone)).isFalse()
    // Un secondo giro non rimette in coda (idempotente) e non unisce senza l'hash completo.
    val again = finder.settle(1.minutes)
    assertThat(again.merged).isEqualTo(0)
    assertThat(catalog.media(a)).isNotNull()
    assertThat(catalog.media(b)).isNotNull()

    // Il lavoro di sicurezza del backup legge i due file e consegna gli hash completi.
    hashed(a)
    hashed(b)
    val second = finder.settle(1.minutes)

    assertThat(second.merged).isEqualTo(1)
    assertThat(finder.scan().verifying).isEqualTo(0)
    val groups = finder.scan().proposals
    assertThat(groups).hasSize(1)
    val proposal = groups.single()
    assertThat(proposal.group.part).isEqualTo(CopyPart.NAS)
    assertThat(proposal.group.copies).hasSize(2)
    assertThat(proposal.status).isEqualTo(IdenticalStatus.CLEANABLE)
    assertThat(proposal.remove).hasSize(1)
    // E il piano porta nel cestino del NAS una sola delle due copie: l'altra resta.
    val plan = finder.plan(groups)
    assertThat(plan.nas).hasSize(1)
    assertThat(plan.phone).isEmpty()
  }

  @Test
  fun dp1_dueFileDiversiCheSomiglianoSoloPerDimensioneEHashRapido_nonSiUniscono() = runBlocking<Unit> {
    val other = Random(92).nextBytes(3_000)
    // Stessa dimensione e stesso hash rapido (lo si forza), contenuti diversi: gli hash completi lo dicono.
    val quick = QuickHashAlgorithm.ofContent(bytes)
    val first = Fingerprint(bytes.size.toLong(), quick, fullHash = null)
    val second = Fingerprint(other.size.toLong(), quick, fullHash = null)
    val a = nas("alessio", "Foto/a.jpg", first)
    val b = nas("alessio", "Foto/b.jpg", second)
    val finder = finder()
    finder.settle(1.minutes)

    hashed(a, full)
    hashed(b, Fingerprint.ofContent(other))
    val report = finder.settle(1.minutes)

    assertThat(report.merged).isEqualTo(0)
    assertThat(finder.scan().proposals).isEmpty()
    assertThat(finder.scan().verifying).isEqualTo(0)
  }

  @Test
  fun dp1_unMediaDiUnGruppoNonSiToccaNelDubbioNonSiUnisce() = runBlocking<Unit> {
    val a = nas("alessio", "Foto/a.jpg", scanned())
    val b = nas("alessio", "Vacanze/a.jpg", scanned())
    val c = nas("alessio", "Foto/c.jpg", scanned(Random(93).nextBytes(2_000)))
    catalog.group(MediaGroupKind.BURST, listOf(a, c), a)

    val report = finder().settle(1.minutes)

    // `a` sta in una raffica: ne' in coda ne' unito. `b` da solo non ha un gemello libero.
    assertThat(report.queued).isEqualTo(0)
    assertThat(fullHashQueued(a)).isFalse()
    assertThat(fullHashQueued(b)).isFalse()
  }

  @Test
  fun dp1_ilGiroRispettaIlTettoDegliInsiemi() = runBlocking<Unit> {
    repeat(4) { n ->
      val content = scanned(Random(200 + n).nextBytes(1_000 + n))
      nas("alessio", "Foto/$n/x.jpg", content)
      nas("alessio", "Vacanze/$n/x.jpg", content)
    }
    val finder = finder(maxSets = 2)

    val report = finder.settle(1.minutes)

    // Due insiemi per giro (quattro media): gli altri aspettano il giro dopo, e la pagina li conta come "in verifica".
    assertThat(report.queued).isEqualTo(4)
    assertThat(finder.scan().verifying).isEqualTo(4)
  }

  // --- DP-2 ----------------------------------------------------------------------------------------------------------------------

  /** Un media con una copia privata verificata e una nell'area comune (stesso contenuto con hash completo: un media, due posti). */
  private suspend fun privateAndCommon(privatePath: String, commonPath: String): MediaId {
    val media = nas("NAS", commonPath, full)
    val version = versionOf(media)
    catalog.attachCopy(media, version, Location.Nas(Fx.ME, "alessio", NasPath.of(privatePath)), FileStamp(full.size, 300), verifiedAtMillis = 5_000)
    return media
  }

  @Test
  fun dp2_laCopiaDellAreaComuneDiUnElementoDiAlbumPerRiferimentoNonSiProponeMai() = runBlocking<Unit> {
    val media = privateAndCommon("Foto/x.jpg", "Famiglia/Oslo/x.jpg")

    // Senza sapere degli album la copia comune e' un "doppione" da togliere: toglierebbe l'elemento dall'album per tutti.
    val blind = finder(twins = false).scan().proposals.single()
    assertThat(blind.remove.map { it.area }).containsExactly(CopyArea.COMMON)

    // Il media e' attestato da un album condiviso (il file sta fuori da `Album condivisi/`): resta, e la pagina lo dice.
    val known = finder(sharedMembers = setOf(media), twins = false)
    val proposal = known.scan().proposals.single()
    assertThat(proposal.remove).isEmpty()
    assertThat(proposal.blocked.map { it.reason }).containsExactly(BlockReason.SHARED_ALBUM)
    assertThat(known.plan(listOf(blind)).isEmpty).isTrue()
  }

  @Test
  fun dp2_laCopiaDellAreaComuneConLaGemellaDelGuardianoNonSiProponeMai() = runBlocking<Unit> {
    privateAndCommon("Backup/Area comune/2026/10/x.jpg", "Famiglia/Oslo/x.jpg")

    val proposal = finder(twins = false).scan().proposals.single()

    assertThat(proposal.remove).isEmpty()
    assertThat(proposal.status).isEqualTo(IdenticalStatus.NOTHING_TO_REMOVE)
    assertThat(proposal.blocked.map { it.reason }).containsExactly(BlockReason.GUARDIAN_TWIN)
  }

  // --- R5 ------------------------------------------------------------------------------------------------------------------------

  /** Un media con due copie nell'area privata: A verificata (da tenere) e B nella cartella di backup (da togliere). */
  private suspend fun twoPrivate(): Pair<MediaId, Location.Nas> {
    val media = nas("alessio", "Backup/Pixel 9/2026/10/x.jpg", full, mtime = 700)
    val keepPlace = Location.Nas(Fx.ME, "alessio", NasPath.of("Foto/Oslo/x.jpg"))
    catalog.attachCopy(media, versionOf(media), keepPlace, FileStamp(full.size, 300), verifiedAtMillis = 5_000)
    return media to keepPlace
  }

  @Test
  fun r5_laCopiaDaTenereSparitaFraLaLetturaEIlTocco_nonPortaNelCestinoL_Altra() = runBlocking<Unit> {
    val (media, keepPlace) = twoPrivate()
    val finder = finder(twins = false)
    val proposals = finder.scan().cleanable
    assertThat(proposals.single().keep!!.path).isEqualTo("Foto/Oslo/x.jpg")
    assertThat(proposals.single().remove.single().path).isEqualTo("Backup/Pixel 9/2026/10/x.jpg")

    // Nel frattempo la copia da tenere sparisce (tolta dal PC): quella da togliere sarebbe l'ultima copia privata.
    catalog.markVanished(keepPlace.key)
    val plan = finder.plan(proposals)

    assertThat(plan.isEmpty).isTrue()
    assertThat(plan.skipped).isEqualTo(1)
    assertThat(catalog.media(media)!!.locations.count { it.isPresent }).isEqualTo(1)
  }

  @Test
  fun r5_senzaLaCopiaVerificataIlPianoNonTogliePiuNiente() = runBlocking<Unit> {
    val (media, _) = twoPrivate()
    val third = Location.Nas(Fx.ME, "alessio", NasPath.of("Altro/x.jpg"))
    catalog.attachCopy(media, versionOf(media), third, FileStamp(full.size, 400), verifiedAtMillis = null)
    val finder = finder(twins = false)
    val proposals = finder.scan().cleanable
    assertThat(proposals.single().group.safe).isTrue()

    // La copia verificata cambia (stesso posto, byte nuovi: la verifica non vale piu'): nessuna copia riletta resterebbe dopo la pulizia.
    val keep = catalog.media(media)!!.locations.first { (it.location as? Location.Nas)?.path?.value == "Foto/Oslo/x.jpg" }
    catalog.recordAll(
      listOf(Observation(keep.location, FileStamp(full.size, 999), full, MediaKind.PHOTO, MediaSource.OTHER)),
      MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0 + 1,
    )

    val plan = finder.plan(proposals)

    assertThat(plan.isEmpty).isTrue()
  }

  @Test
  fun r5_seNienteECambiatoIlPianoEQuelloDiPrima() = runBlocking<Unit> {
    twoPrivate()
    val finder = finder(twins = false)
    val proposals = finder.scan().cleanable

    val plan = finder.plan(proposals)

    assertThat(plan.nas.map { it.path }).containsExactly("Backup/Pixel 9/2026/10/x.jpg")
  }

  // --- UT-1 ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun ut1_conLAccessoParzialeLaLibreriaNonELeggibile_nessunDoppioneSiProponeNeSiPianifica() = runBlocking<Unit> {
    twoPrivate()
    // L'accesso alle sole foto selezionate: l'istantanea della Libreria non c'e' (null), come per i simili. `VisibleMedia.visible` legge solo il database.
    val snapshot = MutableStateFlow<TimelineSnapshot?>(null)
    val restricted = CatalogIdenticalDuplicates(catalog, CatalogVisibleMedia(db, snapshot))
    val open = CatalogIdenticalDuplicates(catalog, CatalogVisibleMedia(db, MutableStateFlow<TimelineSnapshot?>(ColumnarTimelineSnapshot.EMPTY)))
    val proposals = open.scan().cleanable
    assertThat(proposals).hasSize(1)

    val scan = restricted.scan()

    assertThat(scan.restricted).isTrue()
    assertThat(scan.proposals).isEmpty()
    assertThat(restricted.plan(proposals).isEmpty).isTrue()
    // Appena la Libreria si legge, gli stessi doppioni ci sono.
    snapshot.value = ColumnarTimelineSnapshot.EMPTY
    assertThat(restricted.scan().cleanable).hasSize(1)
  }
}
