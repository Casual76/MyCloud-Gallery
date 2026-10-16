package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.testing.InMemorySimilarChoices
import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il rilevatore sul catalogo vero (Fase 5A, P15; §2.12, E-L10...E-L13): i gruppi nascono, si disfano e cambiano copertina con il
 * catalogo di Room in memoria, la sorgente dei pixel e' scritta dal test. Il nascosto contagioso passa dal catalogo (indice della
 * timeline), "Separa" non si riforma, un media ambiguo non entra, il giro riprende dove era.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogSimilarCopiesTest : SimilarCatalogTestBase() {

  private val source = ScriptedSource()
  private val choices = InMemorySimilarChoices()

  private fun detector(page: Int = 500, settled: Boolean = false) = CatalogSimilarCopies(catalog, source, choices, pageSize = page, initiallySettled = settled)

  /** Il disegno [seed] con il rumore di una ricompressione: tutte le copie dello stesso scatto. */
  private fun copyOf(seed: Long, n: Long = 0L): Raster = jitter(pattern(seed), 1, seed * 100 + n)

  private suspend fun timelineSize(): Int = CatalogTimelineSource(db, catalog).buildIndex().size

  private suspend fun indexed(ids: List<MediaId>): List<String> = catalog.indexRows(ids).map { it.mediaId }

  /** Un telefono con la foto originale e una copia ricompressa piu' piccola sul NAS, entrambe con la loro firma pronta. */
  private suspend fun originalAndCloudCopy(seed: Long = 1): Pair<MediaId, MediaId> {
    val phone = phoneShot(width = 4000, height = 3000)
    val cloud = nasShot("Cloud/IMG_$seed.jpg", width = 2000, height = 1500)
    source.photo(phone, copyOf(seed, 1))
    source.photo(cloud, copyOf(seed, 2))
    return phone to cloud
  }

  // --- E-L10: l'originale e la copia ricompressa stanno in una cella ---------------------------------------------------------------

  @Test
  fun lOriginaleDelTelefonoELaCopiaDelCloudStannoInUnaCellaConLaMigliore() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val detector = detector()
    assertThat(detector.progress.value.settled).isFalse() // prima del primo giro non si sa
    val report = detector.run(10.minutes)
    assertThat(report.signed).isEqualTo(2)
    assertThat(report.grouped).isEqualTo(1)
    assertThat(report.pending).isEqualTo(0)
    assertThat(detector.progress.value.settled).isTrue()

    val group = detector.groupOf(cloud)!!
    assertThat(group.copies.map { it.mediaId }).containsExactly(phone, cloud)
    assertThat(group.cover).isEqualTo(phone) // piu' pixel
    assertThat(group.coverChosenByUser).isFalse()
    assertThat(group.hidden).isFalse()
    assertThat(group.copies.first().mediaId).isEqualTo(phone) // la copertina per prima
    assertThat(group.copies.first { it.mediaId == phone }.places).containsExactly(CopyPlace.PHONE)
    assertThat(group.copies.first { it.mediaId == cloud }.places).containsExactly(CopyPlace.NAS_PRIVATE)
    assertThat(group.copies.first { it.mediaId == phone }.cameraOriginal).isTrue()
    assertThat(group.copies.first { it.mediaId == cloud }.cameraOriginal).isFalse()
    assertThat(group.copies.first { it.mediaId == phone }.let { it.width to it.height }).isEqualTo(4000 to 3000)
    // In Libreria resta una cella sola, con il segno delle copie.
    assertThat(indexed(listOf(phone, cloud))).containsExactly(phone.value)
    assertThat(CellFlags.has(db.mediaDao().get(phone.value)!!.cellFlags, CellFlags.SIMILAR_COPIES)).isTrue()
    assertThat(timelineSize()).isEqualTo(1)
    // Il gruppo e' del tipo giusto.
    assertThat(db.mediaDao().group(groupIdOf(phone)!!)!!.kind).isEqualTo(MediaGroupKind.SIMILAR_COPIES.code)
  }

  @Test
  fun unGiroSenzaNovitaNonScriveNienteNeCambiaLIndice() = runBlocking<Unit> {
    originalAndCloudCopy()
    val detector = detector()
    detector.run(10.minutes)
    val seq = catalog.changeSeq()
    val second = detector.run(10.minutes)
    assertThat(second.signed).isEqualTo(0)
    assertThat(second.grouped).isEqualTo(0)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
  }

  @Test
  fun unaCopiaDiUnAltroScattoNellaStessaSecondaNonSiUnisce() = runBlocking<Unit> {
    val a = phoneShot()
    val b = phoneShot()
    source.photo(a, copyOf(1))
    source.photo(b, copyOf(2))
    val report = detector().run(10.minutes)
    assertThat(report.grouped).isEqualTo(0)
    assertThat(timelineSize()).isEqualTo(2)
    assertThat(detector().groupOf(a)).isNull()
  }

  @Test
  fun leFotoCheNonHannoUnaDataLettaDaiMetadatiNonSonoCandidate() = runBlocking<Unit> {
    val a = phoneShot()
    val estimated = phoneShot(source = dev.pampa.mycloudgallery.core.model.CaptureTimeSource.FILE_NAME)
    source.photo(a, copyOf(1))
    source.photo(estimated, copyOf(1, 5))
    assertThat(detector().run(10.minutes).grouped).isEqualTo(0)
    assertThat(source.asked).isEmpty() // nemmeno si guardano
  }

  // --- E-L12: raffiche e RAW+JPEG non si toccano; un media ambiguo non entra ---------------------------------------------------------

  @Test
  fun leRaffichePrimaDelRilevatoreNonSonoCandidateENonSiGuardanoNemmeno() = runBlocking<Unit> {
    val x = phoneShot()
    val y = phoneShot()
    catalog.group(MediaGroupKind.BURST, listOf(x, y), x)
    source.photo(x, copyOf(1))
    source.photo(y, copyOf(1, 2))
    val report = detector().run(10.minutes)
    assertThat(report.grouped).isEqualTo(0)
    assertThat(source.asked).isEmpty()
    assertThat(db.mediaDao().group(groupIdOf(x)!!)!!.kind).isEqualTo(MediaGroupKind.BURST.code)
  }

  @Test
  fun unMediaCheSomigliaADueNonSimiliFraLoroNonEntraInNessunGruppo() = runBlocking<Unit> {
    val a = phoneShot()
    val b = phoneShot()
    val c = phoneShot()
    val base = pattern(3)
    fun shift(delta: Int) = RasterOps.map(base) { r, g, bl -> Triple(r + delta / 255.0, g + delta / 255.0, bl + delta / 255.0) }
    source.photo(a, base)
    source.photo(b, shift(2))
    source.photo(c, shift(-2))
    val sigs = listOf(base, shift(2), shift(-2)).map { VisualSignatures.of(it) }
    assertThat(SimilarityRules.visual(sigs[0], sigs[1])).isEqualTo(VisualVerdict.SAME)
    assertThat(SimilarityRules.visual(sigs[0], sigs[2])).isEqualTo(VisualVerdict.SAME)
    assertThat(SimilarityRules.visual(sigs[1], sigs[2])).isEqualTo(VisualVerdict.DIFFERENT)
    val report = detector().run(10.minutes)
    assertThat(report.ambiguous).isEqualTo(1)
    assertThat(report.grouped).isEqualTo(0)
    assertThat(timelineSize()).isEqualTo(3)
    assertThat(groupIdOf(a)).isNull()
    assertThat(groupIdOf(b)).isNull()
    assertThat(groupIdOf(c)).isNull()
  }

  // --- E-H1, E-L11: il nascosto e' contagioso, passando dal catalogo -----------------------------------------------------------------

  @Test
  fun unGruppoConUnMembroNascostoNonCompareNellIndiceDellaTimeline() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val other = phoneShot(takenAt = Fx.SECOND0 + 5_000)
    now = Fx.T0 + 10
    catalog.setPersonalFlags(cloud, PersonalFlags(hidden = true))
    assertThat(timelineSize()).isEqualTo(2) // il telefono e l'altra foto (la copia nascosta no)
    val report = detector().run(10.minutes)
    assertThat(report.grouped).isEqualTo(1)
    // Il gruppo e' nascosto per intero: la stessa foto non ricompare dalla porta della sua copia.
    assertThat(db.personalDao().get(phone.value)?.hidden).isTrue()
    assertThat(db.personalDao().get(cloud.value)?.hidden).isTrue()
    assertThat(indexed(listOf(phone, cloud))).isEmpty()
    assertThat(timelineSize()).isEqualTo(1)
    val snapshot = CatalogTimelineSource(db, catalog).buildIndex()
    assertThat((0 until snapshot.size).map { snapshot.idAt(it) }).containsExactly(other)
    assertThat(detector().groupOf(phone)!!.hidden).isTrue()
    // "Separa" non toglie il nascosto.
    assertThat(detector().separate(cloud)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(db.personalDao().get(phone.value)?.hidden).isTrue()
    assertThat(db.personalDao().get(cloud.value)?.hidden).isTrue()
    assertThat(timelineSize()).isEqualTo(1)
  }

  // --- E-L13: "Separa" non si riforma mai ------------------------------------------------------------------------------------------

  @Test
  fun separaScioglieIlGruppoELaSeparazioneSopravviveAUnNuovoGiro() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val detector = detector()
    detector.run(10.minutes)
    assertThat(timelineSize()).isEqualTo(1)

    assertThat(detector.separate(cloud)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(timelineSize()).isEqualTo(2)
    assertThat(detector.groupOf(phone)).isNull()
    assertThat(choices.separations.single().let { setOf(it.low, it.high) }).containsExactly(phone, cloud)

    // Un nuovo giro, e un altro ancora, anche con un rilevatore nuovo (processo ricominciato): non si riforma.
    val again = detector.run(10.minutes)
    assertThat(again.grouped).isEqualTo(0)
    assertThat(detector().run(10.minutes).grouped).isEqualTo(0)
    assertThat(timelineSize()).isEqualTo(2)
    assertThat(groupIdOf(phone)).isNull()
    assertThat(groupIdOf(cloud)).isNull()
    // Separare un media che non e' in un gruppo non fa niente.
    assertThat(detector.separate(cloud)).isEqualTo(ChoiceOutcome.NOT_IN_GROUP)
  }

  @Test
  fun unaSeparazioneArrivataDaUnAltroDispositivoSciogliIlGruppoAlGiroDopo() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val detector = detector()
    detector.run(10.minutes)
    assertThat(timelineSize()).isEqualTo(1)
    // Il tablet ha separato le due copie: la voce arriva e il rilevatore la vede.
    choices.saveSeparation(cloud, listOf(phone))
    val report = detector.run(10.minutes)
    assertThat(report.dissolved).isEqualTo(1)
    assertThat(timelineSize()).isEqualTo(2)
    // E se l'utente le riammette (lapide), tornano unibili.
    choices.allowAgain(phone, cloud)
    assertThat(detector.run(10.minutes).grouped).isEqualTo(1)
    assertThat(timelineSize()).isEqualTo(1)
  }

  @Test
  fun separandoUnaCopiaDaTreRestanoInsiemeLeAltreDue() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val third = nasShot("Vecchi/IMG_1.jpg", width = 1600, height = 1200)
    source.photo(third, copyOf(1, 3))
    val detector = detector()
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.copies).hasSize(3)

    assertThat(detector.separate(third)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(detector.groupOf(phone)!!.copies.map { it.mediaId }).containsExactly(phone, cloud)
    assertThat(timelineSize()).isEqualTo(2)
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.copies.map { it.mediaId }).containsExactly(phone, cloud)
    assertThat(groupIdOf(third)).isNull()
  }

  // --- La copertina ------------------------------------------------------------------------------------------------------------------

  @Test
  fun laScelta_MostraQuestaComePrincipale_vinceSullaRegolaEResta() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val detector = detector()
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.cover).isEqualTo(phone)

    assertThat(detector.chooseCover(cloud)).isEqualTo(ChoiceOutcome.APPLIED)
    val chosen = detector.groupOf(phone)!!
    assertThat(chosen.cover).isEqualTo(cloud)
    assertThat(chosen.coverChosenByUser).isTrue()
    assertThat(indexed(listOf(phone, cloud))).containsExactly(cloud.value)
    assertThat(choices.chosen).containsExactly(cloud)

    // La regola non la disfa: nemmeno quando arriva una copia con piu' pixel.
    val huge = nasShot("Cloud/grande.jpg", width = 8000, height = 6000)
    source.photo(huge, copyOf(1, 9))
    val report = detector.run(10.minutes)
    assertThat(report.grouped).isEqualTo(1)
    val after = detector.groupOf(phone)!!
    assertThat(after.copies).hasSize(3)
    assertThat(after.cover).isEqualTo(cloud)
    assertThat(after.coverChosenByUser).isTrue()
    assertThat(detector.chooseCover(MediaId.random())).isEqualTo(ChoiceOutcome.NOT_IN_GROUP)
  }

  @Test
  fun senzaScelta_LaCopertinaSegueLaRegolaEMuoveQuandoCambianoIFatti() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val detector = detector()
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.cover).isEqualTo(phone)
    // Arriva una copia con piu' pixel: la regola 1 la mette in copertina.
    val huge = nasShot("Cloud/grande.jpg", width = 8000, height = 6000)
    source.photo(huge, copyOf(1, 9))
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.cover).isEqualTo(huge)
    assertThat(indexed(listOf(phone, cloud, huge))).containsExactly(huge.value)
    // Dopo una scelta e poi un'altra, vale l'ultima (e la vecchia non resta).
    detector.chooseCover(cloud)
    detector.chooseCover(phone)
    assertThat(detector.groupOf(huge)!!.cover).isEqualTo(phone)
    assertThat(choices.chosen).containsExactly(phone)
  }

  @Test
  fun laCopertinaSegueLaRegolaDelFormatoEDellOriginaleSulCatalogoVero() = runBlocking<Unit> {
    // Stessi pixel: l'HEIC originale del telefono e il JPEG convertito dal cloud (piu' pesante): vince l'originale.
    val heic = phoneShot(width = 4000, height = 3000, mime = "image/heic", bytes = 1_800)
    val jpeg = nasShot("Cloud/IMG_9.jpg", width = 4000, height = 3000, mime = "image/jpeg", bytes = 4_000)
    source.photo(heic, copyOf(2, 1))
    source.photo(jpeg, copyOf(2, 2))
    val detector = detector()
    detector.run(10.minutes)
    assertThat(detector.groupOf(heic)!!.cover).isEqualTo(heic)
    assertThat(detector.groupOf(jpeg)!!.copies.first { it.mediaId == heic }.cameraOriginal).isTrue()

    // Senza il telefono: la copia in Backup/ e' l'originale (il nostro backup carica gli originali) e vince sul JPEG del cloud.
    val backupHeic = nasShot("Backup/S25/2026/10/IMG_8.heic", takenAt = Fx.SECOND0 + 60_000, width = 4000, height = 3000, mime = "image/heic", bytes = 1_800)
    val cloudJpeg = nasShot("Cloud/IMG_8.jpg", takenAt = Fx.SECOND0 + 60_000, width = 4000, height = 3000, mime = "image/jpeg", bytes = 4_000)
    source.photo(backupHeic, copyOf(3, 1))
    source.photo(cloudJpeg, copyOf(3, 2))
    detector.run(10.minutes)
    val nasOnly = detector.groupOf(cloudJpeg)!!
    assertThat(nasOnly.copies.first { it.mediaId == backupHeic }.cameraOriginal).isTrue()
    assertThat(nasOnly.copies.first { it.mediaId == cloudJpeg }.cameraOriginal).isFalse()
    assertThat(nasOnly.cover).isEqualTo(backupHeic)

    // Stesso formato: meno compressione vince anche su un originale (regola 2, prima della 3).
    val backupJpeg = nasShot("Backup/S25/2026/10/IMG_7.jpg", takenAt = Fx.SECOND0 + 90_000, width = 4000, height = 3000, mime = "image/jpeg", bytes = 3_000)
    val betterJpeg = nasShot("Cloud/IMG_7.jpg", takenAt = Fx.SECOND0 + 90_000, width = 4000, height = 3000, mime = "image/jpeg", bytes = 4_000)
    source.photo(backupJpeg, copyOf(4, 1))
    source.photo(betterJpeg, copyOf(4, 2))
    detector.run(10.minutes)
    assertThat(detector.groupOf(backupJpeg)!!.cover).isEqualTo(betterJpeg)
  }

  @Test
  fun unaDataCorrettaCheNonStaPiuConLeAltreFaUscireLaCopiaDalGruppo() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val third = nasShot("Vecchi/IMG_1.jpg", width = 1600, height = 1200)
    source.photo(third, copyOf(1, 3))
    val detector = detector()
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.copies).hasSize(3)
    // La data della copia del cloud viene corretta (E-L8) di qualche secondo: non e' piu' lo stesso istante.
    catalog.updateMetadata(versionOf(cloud), Fx.exif(Fx.SECOND0 + 7_000))
    detector.run(10.minutes)
    assertThat(detector.groupOf(phone)!!.copies.map { it.mediaId }).containsExactly(phone, third)
    assertThat(groupIdOf(cloud)).isNull()
    // Due soli membri e una data corretta: il gruppo si scioglie.
    catalog.updateMetadata(versionOf(third), Fx.exif(Fx.SECOND0 + 3_000))
    detector.run(10.minutes)
    assertThat(groupIdOf(phone)).isNull()
    assertThat(timelineSize()).isEqualTo(3)
  }

  // --- Il riepilogo del primo backup ----------------------------------------------------------------------------------------------------

  @Test
  fun ilRiepilogoContaIMediaConUnaCopiaSimileSulNas() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val lone = phoneShot(takenAt = Fx.SECOND0 + 8_000)
    val detector = detector()
    assertThat(detector.withSimilarOnNas(listOf(phone, cloud, lone))).isEmpty() // il confronto non c'e' ancora stato
    detector.run(10.minutes)
    // La foto del telefono ha una copia simile sul NAS (il cloud); la copia sul NAS non ha un'altra copia sul NAS.
    assertThat(detector.withSimilarOnNas(listOf(phone, cloud, lone))).containsExactly(phone)
    assertThat(detector.withSimilarOnNas(emptyList())).isEmpty()
    // Una seconda copia sul NAS: ora anche la prima copia sul NAS ne ha una.
    val second = nasShot("Vecchi/IMG_1.jpg", width = 1600, height = 1200)
    source.photo(second, copyOf(1, 3))
    detector.run(10.minutes)
    assertThat(detector.withSimilarOnNas(listOf(phone, cloud, second, lone))).containsExactly(phone, cloud, second)
    // Separare cambia il conteggio (si rilegge, non resta quello vecchio).
    detector.separate(second)
    detector.separate(cloud)
    assertThat(detector.withSimilarOnNas(listOf(phone, cloud, second))).isEmpty()
  }

  // --- Il giro a tappe -----------------------------------------------------------------------------------------------------------------

  @Test
  fun unGiroConBudgetMinimoAvanzaUnSecondoAllaVoltaEFinisceComeUnoCompleto() = runBlocking<Unit> {
    val pairs = (1..6).map { i ->
      val a = phoneShot(takenAt = Fx.SECOND0 - i * 10_000L)
      val b = nasShot("Cloud/c$i.jpg", takenAt = Fx.SECOND0 - i * 10_000L)
      source.photo(a, copyOf(i.toLong(), 1))
      source.photo(b, copyOf(i.toLong(), 2))
      a to b
    }
    val detector = detector(page = 3)
    var calls = 0
    var grouped = 0
    while (calls < 40) {
      calls++
      val report = detector.run(Duration.ZERO)
      grouped += report.grouped
      if (detector.progress.value.settled) break
      assertThat(detector.progress.value.running).isFalse()
    }
    assertThat(grouped).isEqualTo(6)
    assertThat(calls).isAtLeast(3) // a tappe, non in un colpo
    assertThat(detector.progress.value.settled).isTrue()
    pairs.forEach { (a, b) -> assertThat(detector.groupOf(b)!!.copies.map { it.mediaId }).containsExactly(a, b) }
    assertThat(timelineSize()).isEqualTo(6)
  }

  // --- La sorgente dei pixel ----------------------------------------------------------------------------------------------------------

  @Test
  fun unaMiniaturaMancanteRimandaIlCandidatoEChiedeLaPriorita() = runBlocking<Unit> {
    val phone = phoneShot()
    val nas = nasShot("Vecchi/IMG_1.jpg", width = 4000, height = 3000)
    source.photo(phone, copyOf(1))
    source.script[nas] = SignaturePixels.Later(contentKey = 777L)
    val detector = detector()
    val first = detector.run(10.minutes)
    assertThat(first.grouped).isEqualTo(0)
    assertThat(first.pending).isEqualTo(1)
    assertThat(detector.progress.value.settled).isFalse()
    assertThat(detector.awaitingThumbnails.value).containsExactly(777L)
    // La Preparazione l'ha fatta: al giro dopo il candidato si firma e si unisce.
    source.photo(nas, copyOf(1, 2))
    val second = detector.run(10.minutes)
    assertThat(second.grouped).isEqualTo(1)
    assertThat(detector.progress.value.settled).isTrue()
    assertThat(detector.awaitingThumbnails.value).isEmpty()
    // Chi era gia' firmato non si rilegge.
    assertThat(source.asked.count { it == phone }).isEqualTo(1)
  }

  @Test
  fun uncandidatoIllegibileNonSiRiprovaEIlRilevatoreNonSiFerma() = runBlocking<Unit> {
    val good = phoneShot()
    val broken = phoneShot()
    source.photo(good, copyOf(1))
    // `broken` non e' nello script: Never. Un altro lancia.
    val detector = detector()
    val report = detector.run(10.minutes)
    assertThat(report.grouped).isEqualTo(0)
    assertThat(detector.progress.value.settled).isTrue() // un illeggibile non fa aspettare per sempre
    val asks = source.asked.count { it == broken }
    detector.run(10.minutes)
    assertThat(source.asked.count { it == broken }).isEqualTo(asks) // non si riprova in questa vita del processo

    source.failWith = IllegalStateException("guasto")
    val c = phoneShot(takenAt = Fx.SECOND0 + 20_000)
    val d = phoneShot(takenAt = Fx.SECOND0 + 20_000)
    source.photo(c, copyOf(2))
    source.photo(d, copyOf(2, 2))
    val survived = detector.run(10.minutes)
    assertThat(survived.grouped).isEqualTo(0)
    assertThat(detector.progress.value.settled).isTrue()
  }

  @Test
  fun ilConfrontoNonFinitoSiVedeNelProgressoEUnRefreshLoRiapre() = runBlocking<Unit> {
    val detector = detector(settled = true)
    assertThat(detector.progress.value.settled).isTrue()
    detector.run(10.minutes)
    assertThat(detector.progress.value.settled).isTrue()
    // META legge l'ora di due foto nuove: il confronto non e' piu' finito.
    val a = phoneShot(takenAt = Fx.SECOND0 + 30_000)
    val b = phoneShot(takenAt = Fx.SECOND0 + 30_000)
    source.photo(a, copyOf(5))
    source.photo(b, copyOf(5, 2))
    detector.refreshProgress()
    assertThat(detector.progress.value.settled).isFalse()
    detector.run(10.minutes)
    assertThat(detector.progress.value.settled).isTrue()
    detector.refreshProgress()
    assertThat(detector.progress.value.settled).isTrue()
  }

  // --- Copie che adesso non si raggiungono -------------------------------------------------------------------------------------------

  @Test
  fun unaCopiaDelNasSpentoRestaNelGruppoEUnArrivoLaPortaConSe() = runBlocking<Unit> {
    val (phone, cloud) = originalAndCloudCopy()
    val detector = detector()
    detector.run(10.minutes)
    // Il NAS si spegne: la copia non e' piu' un candidato (presenza nulla) ma il gruppo non si disfa.
    catalog.markShareUnavailable(Fx.ME, "alessio")
    detector.run(10.minutes)
    assertThat(groupIdOf(cloud)).isEqualTo(groupIdOf(phone))
    // Arriva un'altra copia del telefono: il gruppo si allarga e la copia irraggiungibile non resta sola.
    val fresh = phoneShot(width = 3000, height = 2250)
    source.photo(fresh, copyOf(1, 7))
    detector.run(10.minutes)
    assertThat(groupIdOf(fresh)).isNotNull()
    assertThat(groupIdOf(cloud)).isEqualTo(groupIdOf(fresh))
    assertThat(groupIdOf(phone)).isEqualTo(groupIdOf(fresh))
  }

  @Test
  fun iVideoSenzaFotogrammiNonSiUniscono() = runBlocking<Unit> {
    val a = phoneShot(kind = MediaKind.VIDEO)
    val b = nasShot("Video/V1.mp4", kind = MediaKind.VIDEO)
    // La sorgente non da' fotogrammi (NoVideoFrames del null-object): Never.
    val real = CatalogSignatureSource(catalog, phone = { null }, nas = NasThumbnailPixels(NoBlobs), video = NoVideoFrames)
    val detector = CatalogSimilarCopies(catalog, real, choices)
    assertThat(detector.run(10.minutes).grouped).isEqualTo(0)
    assertThat(detector.progress.value.settled).isTrue()
    assertThat(groupIdOf(a)).isNull()
    assertThat(groupIdOf(b)).isNull()
  }

  @Test
  fun iVideoConFotogrammiFintiSiUnisconoSuDueIstanti() = runBlocking<Unit> {
    val a = phoneShot(kind = MediaKind.VIDEO)
    val b = nasShot("Video/V1.mp4", kind = MediaKind.VIDEO)
    val asked = ArrayList<VideoInstants>()
    val frames = VideoFrames { candidate, instants ->
      asked += instants
      val tweak = if (candidate.mediaId == a) 0L else 1L
      SignaturePixels.Ready(listOf(copyOf(7, tweak), copyOf(8, tweak)))
    }
    val real = CatalogSignatureSource(catalog, phone = { null }, nas = NasThumbnailPixels(NoBlobs), video = frames)
    val detector = CatalogSimilarCopies(catalog, real, choices)
    assertThat(detector.run(10.minutes).grouped).isEqualTo(1)
    assertThat(catalog.signatureOf(versionOf(a))!!.frames).isEqualTo(2)
    // Gli istanti dipendono dalla sola durata (8 s): la copertina a 1 s e il centro a 4 s.
    assertThat(asked.map { it.atMs }.distinct()).containsExactly(listOf(1_000L, 4_000L))
    assertThat(detector.groupOf(b)!!.copies.all { it.kind == MediaKind.VIDEO }).isTrue()
  }

  @Test
  fun troppiCandidatiNellaStessaSecondaNonSiFirmanoNeSiDecidono() = runBlocking<Unit> {
    val many = (1..SimilarityRules.MAX_RUN + 1).map { phoneShot().also { id -> source.photo(id, copyOf(1, id.value.hashCode().toLong())) } }
    val report = detector().run(10.minutes)
    assertThat(report.grouped).isEqualTo(0)
    assertThat(report.ambiguous).isEqualTo(many.size)
    assertThat(source.asked).isEmpty()
  }
}

/** Una replica senza blob: nessuna miniatura, e nessuna lettura. */
internal object NoBlobs : ReplicaBlobs {
  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? = null

  override fun read(location: BlobLocation): ByteBuffer = error("Nessun blob da leggere.")

  override suspend fun estimate(quality: QualityLevel): SpaceEstimate = error("Non serve.")
}
