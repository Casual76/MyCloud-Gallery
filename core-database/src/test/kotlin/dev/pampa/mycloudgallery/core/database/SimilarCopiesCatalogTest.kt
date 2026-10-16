package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.VisualSignatureEntity
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le copie simili nel catalogo (Fase 5A, P1; D36, `docs/fase5-progetto.md` §2.12, E-L10…E-L13; `docs/modello-dati.md` §12): i
 * candidati per `sort_time`, le firme, il gruppo `SIMILAR_COPIES`, "Separa", la copertina e il **nascosto contagioso** (E-H1).
 */
@RunWith(AndroidJUnit4::class)
class SimilarCopiesCatalogTest : CatalogTestBase() {

  /** Un secondo intero, per avere piu' media nello stesso secondo di scatto. */
  private val second0 = (T0 / 1_000) * 1_000

  @Before
  fun phoneCursor() = runBlocking {
    catalog.saveMediaStoreCursor(MediaStoreCursor("external_primary", msVersion = Db.STORE_V1))
  }

  /** Un file del telefono con la data di scatto [takenAt] **letta dai metadati** (META ha gia' girato). */
  private suspend fun shot(
    n: Int,
    takenAt: Long,
    kind: MediaKind = MediaKind.PHOTO,
    width: Int = 4000,
    height: Int = 3000,
    source: CaptureTimeSource = CaptureTimeSource.EXIF_WITH_OFFSET,
    confidence: CaptureTimeConfidence = CaptureTimeConfidence.EXACT,
  ): MediaId {
    val id = catalog.record(phoneObservation("IMG_$n.jpg", 200L + n, smallFull(n), takenAt, kind = kind)).mediaId!!
    catalog.updateMetadata(
      catalog.versionOf(id),
      exifMetadata(takenAt, width, height, source, confidence, durationMillis = if (kind.isVideo) 8_000 else null),
    )
    return id
  }

  private suspend fun row(id: MediaId) = db.mediaDao().get(id.value)!!

  private suspend fun flags(id: MediaId) = row(id).cellFlags

  private suspend fun indexed(ids: List<MediaId>) = catalog.indexRows(ids).map { it.mediaId }

  private suspend fun hidden(id: MediaId) = db.personalDao().get(id.value)?.hidden == true

  private fun grouped(outcome: SimilarGroupOutcome): SimilarGroupOutcome.Grouped = outcome as SimilarGroupOutcome.Grouped

  /** Il media porta un ruolo di gruppo o il segno delle copie (qualunque dei tre bit). */
  private suspend fun inGroup(id: MediaId) = flags(id) and (CellFlags.GROUP_MEMBER or CellFlags.GROUP_COVER or CellFlags.SIMILAR_COPIES) != 0

  // --- Candidati -----------------------------------------------------------------------------------------------------------------

  @Test
  fun iCandidatiSonoMediaDelloStessoTipoNelloStessoSecondoLettoDaiMetadati() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 900, width = 2000, height = 1500)
    val nextSecond = shot(3, second0 + 1_000)
    val v1 = shot(4, second0 + 200, kind = MediaKind.VIDEO)
    val v2 = shot(5, second0 + 300, kind = MediaKind.VIDEO)
    // Non sono candidati: data stimata, data del MediaStore, META non ancora passata, fuori dalla Libreria.
    val estimated = shot(6, second0 + 400, source = CaptureTimeSource.FILE_NAME, confidence = CaptureTimeConfidence.ESTIMATED)
    val mediaStore = shot(7, second0 + 500, source = CaptureTimeSource.MEDIASTORE, confidence = CaptureTimeConfidence.OFFSET_ASSUMED)
    val noMeta = catalog.record(phoneObservation("IMG_8.jpg", 208, smallFull(8), second0 + 600)).mediaId!!
    val gone = shot(9, second0 + 700)
    catalog.markVanished(phoneObservation("IMG_9.jpg", 209, smallFull(9), 0).location.key)
    // Una raffica: i suoi membri non si spostano.
    val burst1 = shot(10, second0 + 800)
    val burst2 = shot(11, second0 + 810)
    catalog.group(MediaGroupKind.BURST, listOf(burst1, burst2), burst1)

    val page = catalog.similarRuns(fromSecond = 0, limit = 100)
    assertThat(page.nextSecond).isNull()
    assertThat(page.runs.map { it.candidates.map { c -> c.mediaId }.toSet() }).containsExactly(setOf(a, b), setOf(v1, v2))
    val photoRun = page.runs.first { !it.candidates.first().isVideo }
    assertThat(photoRun.second).isEqualTo(second0 / 1_000)
    assertThat(photoRun.candidates.map { it.aspectMilli }).containsExactly(1333, 1333)
    val video = page.runs.first { it.candidates.first().isVideo }.candidates.first()
    assertThat(video.durationMs).isEqualTo(8_000)
    assertThat(video.takenAtUtcMillis).isIn(listOf(second0 + 200, second0 + 300))
    assertThat(photoRun.candidates.all { it.group == null && it.signatureAlgorithm == null }).isTrue()
    assertThat(page.runs.flatMap { it.candidates }.map { it.mediaId }).containsNoneOf(nextSecond, estimated, mediaStore, noMeta, gone, burst1, burst2)

    // I candidati dei secondi di alcuni media (dopo che META ha letto l'ora di uno nuovo).
    // (quelli del secondo, per tipo: le foto e i video dello stesso secondo sono due gruppi)
    assertThat(catalog.similarRunsFor(listOf(a)).map { it.candidates.map { c -> c.mediaId }.toSet() }).containsExactly(setOf(a, b), setOf(v1, v2))
    assertThat(catalog.similarRunsFor(listOf(nextSecond))).isEmpty()
    // Un media che non e' candidato non entra nei gruppi del suo secondo.
    assertThat(catalog.similarRunsFor(listOf(estimated)).flatMap { it.candidates }.map { it.mediaId }).doesNotContain(estimated)
    assertThat(catalog.similarRunsFor(emptyList())).isEmpty()
  }

  @Test
  fun lePagineNonSpezzanoMaiUnSecondoEPercorronoTuttoSenzaSalti() = runBlocking<Unit> {
    // 12 secondi con una coppia ciascuno e un secondo con tre media, in mezzo.
    val expected = ArrayList<Set<MediaId>>()
    var n = 1
    for (i in 0 until 12) {
      val second = second0 + i * 1_000L
      val members = if (i == 5) listOf(shot(n++, second + 10), shot(n++, second + 20), shot(n++, second + 30)) else listOf(shot(n++, second + 10), shot(n++, second + 20))
      expected += members.toSet()
    }
    for (limit in listOf(1, 2, 3, 5, 7, 100)) {
      val seen = ArrayList<Set<MediaId>>()
      var from = 0L
      var pages = 0
      while (true) {
        val page = catalog.similarRuns(from, limit)
        pages++
        seen += page.runs.map { run -> run.candidates.map { it.mediaId }.toSet() }
        from = page.nextSecond ?: break
        assertThat(pages).isLessThan(40)
      }
      assertThat(seen).containsExactlyElementsIn(expected).inOrder()
    }
  }

  @Test
  fun leFirmeSiSalvanoPerVersioneESostituisconoQuellaVecchia() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val versionA = catalog.versionOf(a)
    val versionB = catalog.versionOf(b)
    assertThat(catalog.similarUnsigned(1)).isEqualTo(2)
    assertThat(catalog.signatureOf(versionA)).isNull()

    val signature = VisualSignature.of(algorithm = 1, frames = 1, bytes = ByteArray(1_024) { (it % 251).toByte() })
    assertThat(catalog.saveSignature(versionA, signature)).isTrue()
    assertThat(catalog.signatureOf(versionA)).isEqualTo(signature)
    assertThat(catalog.signaturesOf(listOf(versionA, versionB))).containsExactly(versionA, signature)
    assertThat(catalog.similarUnsigned(1)).isEqualTo(1)
    assertThat(catalog.similarRuns(0, 100).runs.single().candidates.associate { it.mediaId to it.signatureAlgorithm })
      .containsExactly(a, 1, b, null)
    // Una firma di un altro algoritmo non vale per questo: va ricalcolata (e sostituisce la vecchia).
    assertThat(catalog.similarUnsigned(2)).isEqualTo(2)
    val newer = VisualSignature.of(algorithm = 2, frames = 2, bytes = ByteArray(2_048) { 7 })
    assertThat(catalog.saveSignature(versionA, newer)).isTrue()
    assertThat(catalog.signatureOf(versionA)).isEqualTo(newer)
    assertThat(db.visualSignatureDao().count()).isEqualTo(1)
    assertThat(catalog.similarUnsigned(2)).isEqualTo(1)
    // Non tocca l'indice della timeline.
    val seq = catalog.changeSeq()
    catalog.saveSignature(versionB, signature)
    assertThat(catalog.changeSeq()).isEqualTo(seq)

    // Una versione che non c'e', e una riga rovinata (si ricalcola, non si confronta).
    assertThat(catalog.saveSignature(VersionId("00000000-0000-4000-8000-0000000000ee"), signature)).isFalse()
    db.visualSignatureDao().upsert(VisualSignatureEntity(versionB.value, 1, 3, ByteArray(4), T0))
    assertThat(catalog.signatureOf(versionB)).isNull()
    assertThat(catalog.signaturesOf(listOf(versionA, versionB)).keys).containsExactly(versionA)
  }

  // --- Il gruppo --------------------------------------------------------------------------------------------------------------------

  @Test
  fun unGruppoDiCopieSimiliMostraUnaCellaSolaConIlSegnoSullaCopertina() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    val lone = shot(4, second0 + 5_000)
    val changes = Changes(this, catalog)

    val outcome = grouped(catalog.groupSimilar(listOf(a, b, c), cover = b))
    assertThat(outcome.members).containsExactly(a, b, c)
    assertThat(outcome.hidden).isFalse()
    assertThat(outcome.dissolved).isEqualTo(0)
    assertThat(changes.next().let { it.structural && it.ids.containsAll(listOf(a, b, c)) }).isTrue()
    assertThat(changes.none()).isTrue()

    assertThat(db.mediaDao().group(outcome.group.value)!!.kind).isEqualTo(MediaGroupKind.SIMILAR_COPIES.code)
    assertThat(indexed(listOf(a, b, c, lone))).containsExactly(lone.value, b.value)
    // Il segno "copie" solo sulla copertina; i membri dietro come in ogni gruppo.
    assertThat(CellFlags.has(flags(b), CellFlags.SIMILAR_COPIES or CellFlags.GROUP_COVER)).isTrue()
    assertThat(CellFlags.has(flags(a), CellFlags.SIMILAR_COPIES)).isFalse()
    assertThat(CellFlags.has(flags(a), CellFlags.GROUP_MEMBER)).isTrue()
    assertThat(CellFlags.has(flags(c), CellFlags.GROUP_MEMBER)).isTrue()
    assertThat(db.timelineIndexDao().groupSizes().single().let { it.groupId to it.size }).isEqualTo(outcome.group.value to 3)
    // Una raffica non porta il segno delle copie.
    val x = shot(5, second0 + 9_000)
    val y = shot(6, second0 + 9_010)
    catalog.group(MediaGroupKind.BURST, listOf(x, y), x)
    assertThat(CellFlags.has(flags(x), CellFlags.GROUP_COVER)).isTrue()
    assertThat(CellFlags.has(flags(x), CellFlags.SIMILAR_COPIES)).isFalse()

    // Le copie del gruppo e la copertina per la striscia del viewer.
    val info = catalog.similarGroupOf(a)!!
    assertThat(info.group).isEqualTo(outcome.group)
    assertThat(info.cover).isEqualTo(b)
    assertThat(info.members.map { it.mediaId }).containsExactly(a, b, c)
    assertThat(info.members.first { it.mediaId == a }.let { it.onPhone to it.bytes }).isEqualTo(true to 2_000L)
    assertThat(info.members.first().width).isEqualTo(4000)
    assertThat(info.hidden).isFalse()
    assertThat(catalog.similarGroupOf(lone)).isNull()
    assertThat(catalog.similarGroupOf(x)).isNull() // una raffica non e' un gruppo di copie simili
    assertThat(catalog.similarGroupIds(null, 10)).containsExactly(outcome.group)
    assertThat(catalog.similarGroupIds(outcome.group, 10)).isEmpty()
    changes.job.cancel()
  }

  @Test
  fun unMembroInUnaRafficaUnaCoppiaRawJpegOUnProbabileDoppioneRifiutaTuttoSenzaScrivere() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val burstMate = shot(3, second0 + 300)
    val pairMate = shot(4, second0 + 400)
    val burst = catalog.group(MediaGroupKind.BURST, listOf(b, burstMate), b)
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    val inBurst = catalog.groupSimilar(listOf(a, b), a) as SimilarGroupOutcome.Refused
    assertThat(inBurst.reason).isEqualTo(SimilarRefusal.IN_BURST)
    assertThat(inBurst.offenders).containsExactly(b, SimilarRefusal.IN_BURST)
    // Niente scritto: ne' un gruppo, ne' un cambiamento, ne' il nascosto; la raffica e' com'era.
    assertThat(row(a).groupId).isNull()
    assertThat(row(b).groupId).isEqualTo(burst.value)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
    assertThat(changes.none()).isTrue()
    assertThat(catalog.similarGroupIds(null, 10)).isEmpty()

    // Anche con piu' membri a posto: un solo membro in una raffica rifiuta tutto.
    val c = shot(5, second0 + 500)
    assertThat((catalog.groupSimilar(listOf(a, c, burstMate), a) as SimilarGroupOutcome.Refused).offenders.keys).containsExactly(burstMate)

    val pair = catalog.group(MediaGroupKind.RAW_JPEG_PAIR, listOf(pairMate, c), pairMate)
    val inPair = catalog.groupSimilar(listOf(a, pairMate), a) as SimilarGroupOutcome.Refused
    assertThat(inPair.reason).isEqualTo(SimilarRefusal.IN_RAW_JPEG_PAIR)
    assertThat(row(pairMate).groupId).isEqualTo(pair.value)

    // Un `PROBABLE_DUPLICATE`: lo stesso video su telefono e NAS in attesa dell'hash completo.
    val quick = smallQuick(40)
    val phoneVideo = catalog.recordAll(
      listOf(phoneObservation("VID_1.mp4", 1, quick, second0, kind = MediaKind.VIDEO)),
      MediaCatalog.WhenUndecided.KEEP_SEPARATE,
    ).single().mediaId!!
    catalog.recordAll(listOf(nasObservation("Video/VID_1.mp4", quick, kind = MediaKind.VIDEO)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
    val dup = catalog.groupSimilar(listOf(a, phoneVideo), a) as SimilarGroupOutcome.Refused
    assertThat(dup.reason).isEqualTo(SimilarRefusal.IN_PROBABLE_DUPLICATE)
    assertThat(dup.offenders.keys).containsExactly(phoneVideo)
    changes.job.cancel()
  }

  @Test
  fun unGruppoNonNascePerErroriDiProgrammaEUnaIdentitaAssorbitaVaAlSopravvissuto() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val ghost = MediaId("00000000-0000-4000-8000-0000000000aa")
    assertThat((catalog.groupSimilar(listOf(a, ghost), a) as SimilarGroupOutcome.Refused).reason).isEqualTo(SimilarRefusal.UNKNOWN_MEDIA)
    assertThat((catalog.groupSimilar(listOf(a), a) as SimilarGroupOutcome.Refused).reason).isEqualTo(SimilarRefusal.TOO_FEW_MEMBERS)
    assertThat((catalog.groupSimilar(listOf(a, a), a) as SimilarGroupOutcome.Refused).reason).isEqualTo(SimilarRefusal.TOO_FEW_MEMBERS)
    assertThat((catalog.groupSimilar(listOf(a, b), ghost) as SimilarGroupOutcome.Refused).reason).isEqualTo(SimilarRefusal.COVER_NOT_A_MEMBER)
    assertThat((catalog.groupSimilar(listOf(a, b), shot(3, second0 + 300)) as SimilarGroupOutcome.Refused).reason)
      .isEqualTo(SimilarRefusal.COVER_NOT_A_MEMBER)

    // `group()` non e' la porta delle copie simili.
    assertThrows(IllegalArgumentException::class.java) { runBlocking { catalog.group(MediaGroupKind.SIMILAR_COPIES, listOf(a, b), a) } }

    // Un'identita' assorbita da un'unione vale per il sopravvissuto: due media nati separati (stessa impronta rapida, hash completo
    // non ancora noto) si uniscono quando gli hash arrivano, e il perdente diventa un alias.
    val quick = smallQuick(60)
    val first = catalog.record(phoneObservation("IMG_60.jpg", 260, quick, second0 + 600), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    val second = catalog.recordAll(listOf(nasObservation("Vecchi/IMG_60.jpg", quick)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
      .single().mediaId!!
    val sha = smallFull(60).fullHash!!
    catalog.recordFullHash(first, catalog.versionOf(first), sha)
    catalog.recordFullHash(second, catalog.versionOf(second), sha)
    val survivor = minOf(first, second)
    val loser = maxOf(first, second)
    assertThat(catalog.resolve(loser)).isEqualTo(survivor)
    val c = shot(4, second0 + 400)
    val outcome = grouped(catalog.groupSimilar(listOf(loser, c), c))
    assertThat(outcome.members).containsExactly(survivor, c)
  }

  @Test
  fun iMembriDiUnGruppoDiCopieSimiliPassanoAlNuovoEIlVecchioRimastoSoloSiScioglie() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    val d = shot(4, second0 + 400)
    val first = grouped(catalog.groupSimilar(listOf(a, b), a))

    // c e b formano un altro gruppo: b lascia il primo, che resta con a solo e si scioglie.
    val second = grouped(catalog.groupSimilar(listOf(b, c), c))
    assertThat(second.dissolved).isEqualTo(1)
    assertThat(db.mediaDao().group(first.group.value)).isNull()
    assertThat(row(a).groupId).isNull()
    assertThat(inGroup(a)).isFalse()
    assertThat(indexed(listOf(a, b, c, d))).containsExactly(d.value, c.value, a.value)

    // Tutti e quattro insieme: il gruppo vecchio non rimane con meno di due, quindi si scioglie anche lui.
    val all = grouped(catalog.groupSimilar(listOf(a, b, c, d), d))
    assertThat(all.dissolved).isEqualTo(1)
    assertThat(db.mediaDao().group(second.group.value)).isNull()
    assertThat(indexed(listOf(a, b, c, d))).containsExactly(d.value)
  }

  // --- Nascosto contagioso (E-H1) ----------------------------------------------------------------------------------------------------

  @Test
  fun unGruppoCheNasceConUnMembroNascostoLoENascostoPerIntero() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    now = T0 + 10
    catalog.setPersonalFlags(b, PersonalFlags(favorite = true, hidden = true))
    val hiddenAt = db.personalDao().get(b.value)!!.hiddenChangedAt
    val changes = Changes(this, catalog)
    now = T0 + 20

    val outcome = grouped(catalog.groupSimilar(listOf(a, b, c), cover = a))

    // Nella stessa transazione: tutti nascosti, con l'istante del cambio (la sincronizzazione dei dati personali lo vede).
    assertThat(outcome.hidden).isTrue()
    listOf(a, b, c).forEach { assertThat(hidden(it)).isTrue() }
    assertThat(db.personalDao().get(a.value)!!.hiddenChangedAt).isEqualTo(T0 + 20)
    assertThat(db.personalDao().get(c.value)!!.hiddenChangedAt).isEqualTo(T0 + 20)
    assertThat(db.personalDao().get(b.value)!!.hiddenChangedAt).isEqualTo(hiddenAt) // gia' nascosto: il suo istante non cambia
    assertThat(db.personalDao().get(b.value)!!.favorite).isTrue() // il preferito non e' contagioso
    assertThat(db.personalDao().get(a.value)!!.favorite).isFalse()
    // Un solo cambiamento, strutturale, con tutti dentro; nessuno compare piu' in Libreria, il gruppo lo dice.
    assertThat(changes.next().let { it.structural && it.ids.containsAll(listOf(a, b, c)) }).isTrue()
    assertThat(changes.none()).isTrue()
    assertThat(indexed(listOf(a, b, c))).isEmpty()
    assertThat(catalog.similarGroupOf(c)!!.hidden).isTrue()
    assertThat(db.timelineIndexDao().hiddenRows().map { it.mediaId }).containsExactly(a.value, b.value, c.value)
    changes.job.cancel()
  }

  @Test
  fun nascondereOMostrareUnaCopiaValeSempreChePerTutteLeCopie() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    val outside = shot(4, second0 + 5_000)
    grouped(catalog.groupSimilar(listOf(a, b, c), cover = a))
    assertThat(indexed(listOf(a, b, c))).containsExactly(a.value)

    // Nascondere la copertina: tutte nascoste, per la stessa via (con `hidden_changed_at`), e nessun'altra copia al suo posto.
    now = T0 + 100
    catalog.setPersonalFlags(a, PersonalFlags(hidden = true))
    listOf(a, b, c).forEach {
      assertThat(hidden(it)).isTrue()
      assertThat(db.personalDao().get(it.value)!!.hiddenChangedAt).isEqualTo(T0 + 100)
    }
    assertThat(indexed(listOf(a, b, c))).isEmpty()

    // Mostrare una copia che non e' la copertina: tornano tutte, e la cella e' quella del gruppo.
    now = T0 + 200
    catalog.setPersonalFlags(c, PersonalFlags(hidden = false))
    listOf(a, b, c).forEach {
      assertThat(hidden(it)).isFalse()
      assertThat(db.personalDao().get(it.value)!!.hiddenChangedAt).isEqualTo(T0 + 200)
    }
    assertThat(indexed(listOf(a, b, c))).containsExactly(a.value)
    assertThat(CellFlags.has(flags(a), CellFlags.SIMILAR_COPIES)).isTrue()

    // Anche la via per selezione passa dalla stessa funzione.
    now = T0 + 300
    catalog.setPersonalFlags(listOf(b)) { it.copy(hidden = true) }
    listOf(a, b, c).forEach { assertThat(hidden(it)).isTrue() }
    assertThat(indexed(listOf(a, b, c))).isEmpty()
    // Un media fuori dal gruppo non c'entra.
    assertThat(hidden(outside)).isFalse()
    assertThat(indexed(listOf(outside))).containsExactly(outside.value)
    // Il preferito non e' contagioso.
    catalog.setPersonalFlags(a, PersonalFlags(favorite = true, hidden = true))
    assertThat(db.personalDao().get(b.value)!!.favorite).isFalse()
  }

  @Test
  fun separaNonToglieIlNascostoEIlGruppoRimastoConUnaCopiaSiSciogli() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val group = grouped(catalog.groupSimilar(listOf(a, b), cover = a)).group
    catalog.setPersonalFlags(a, PersonalFlags(hidden = true))
    assertThat(hidden(b)).isTrue()

    val outcome = catalog.separate(b) as SeparateOutcome.Separated
    assertThat(outcome.dissolved).isTrue()
    assertThat(outcome.group).isNull()
    assertThat(outcome.remaining).containsExactly(a)
    assertThat(db.mediaDao().group(group.value)).isNull()
    // "Separa" non mostra niente: restano nascoste, ognuna per conto suo.
    assertThat(hidden(a)).isTrue()
    assertThat(hidden(b)).isTrue()
    assertThat(indexed(listOf(a, b))).isEmpty()
    assertThat(inGroup(a)).isFalse()
    // Nascoste e sciolte: ora si mostrano da sole (il contagio vale solo dentro un gruppo).
    catalog.setPersonalFlags(a, PersonalFlags(hidden = false))
    assertThat(hidden(b)).isTrue()
    assertThat(indexed(listOf(a, b))).containsExactly(a.value)
  }

  @Test
  fun unGruppoConLaCopertinaNascostaNonFaComparireUnAltroMembroNellIndice() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    grouped(catalog.groupSimilar(listOf(a, b, c), cover = b))

    // La copertina nascosta passando dalla via normale: nessun altro membro prende il suo posto.
    catalog.setPersonalFlags(b, PersonalFlags(hidden = true))
    assertThat(indexed(listOf(a, b, c))).isEmpty()
    assertThat(db.timelineIndexDao().indexRows(listOf(a.value, b.value, c.value))).isEmpty()
    listOf(a, c).forEach { assertThat(CellFlags.has(flags(it), CellFlags.GROUP_MEMBER)).isTrue() }
    listOf(a, b, c).forEach { assertThat(CellFlags.has(flags(it), CellFlags.GROUP_COVER)).isFalse() }

    // Anche se un dato arrivasse a meta' (solo la copertina nascosta, senza il contagio): il gruppo resta nascosto per intero.
    catalog.setPersonalFlags(b, PersonalFlags(hidden = false))
    assertThat(indexed(listOf(a, b, c))).containsExactly(b.value)
    db.personalDao().upsert(MediaPersonalEntity(b.value, favorite = false, hidden = true, updatedAt = T0, hiddenChangedAt = T0))
    catalog.setPersonalFlags(a, PersonalFlags(favorite = true)) // tocca un altro membro: il gruppo si ricalcola
    assertThat(hidden(a)).isFalse() // il contagio e' di chi nasconde, qui si e' scritto a mano
    assertThat(indexed(listOf(a, b, c))).isEmpty()
    listOf(a, b, c).forEach { assertThat(CellFlags.has(flags(it), CellFlags.GROUP_COVER)).isFalse() }
  }

  // --- "Separa" e la copertina scelta ---------------------------------------------------------------------------------------------

  @Test
  fun separareUnaCopiaLaFaTornareUnaCellaASeEIlGruppoTieneLeAltre() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    val group = grouped(catalog.groupSimilar(listOf(a, b, c), cover = c)).group

    val outcome = catalog.separate(a) as SeparateOutcome.Separated
    assertThat(outcome.dissolved).isFalse()
    assertThat(outcome.group).isEqualTo(group)
    assertThat(outcome.remaining).containsExactly(b, c)
    assertThat(outcome.coverChanged).isFalse()
    assertThat(indexed(listOf(a, b, c))).containsExactly(c.value, a.value)
    assertThat(CellFlags.has(flags(a), CellFlags.GROUP_MEMBER)).isFalse()
    assertThat(CellFlags.has(flags(c), CellFlags.SIMILAR_COPIES or CellFlags.GROUP_COVER)).isTrue()
    assertThat(db.timelineIndexDao().groupSizes().single().size).isEqualTo(2)

    // Separare la copertina: ne resta una provvisoria (la piu' recente), e lo dice.
    val coverOut = catalog.separate(c) as SeparateOutcome.Separated
    assertThat(coverOut.dissolved).isTrue() // b resta solo
    assertThat(coverOut.coverChanged).isTrue()
    assertThat(coverOut.remaining).containsExactly(b)
    assertThat(indexed(listOf(a, b, c))).containsExactly(c.value, b.value, a.value)

    // Non in un gruppo di copie simili: niente da separare.
    assertThat(catalog.separate(a)).isEqualTo(SeparateOutcome.NotInGroup)
    val x = shot(4, second0 + 400)
    val y = shot(5, second0 + 410)
    catalog.group(MediaGroupKind.BURST, listOf(x, y), x)
    assertThat(catalog.separate(x)).isEqualTo(SeparateOutcome.NotInGroup)
    assertThat(row(x).groupId).isNotNull()
    assertThat(catalog.separate(MediaId("00000000-0000-4000-8000-0000000000aa"))).isEqualTo(SeparateOutcome.NotInGroup)
  }

  @Test
  fun separandoLaCopertinaDiUnGruppoDiTreLaNuovaCopertinaEUnMembroERestaUnaSolaCella() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    val d = shot(4, second0 + 400)
    val group = grouped(catalog.groupSimilar(listOf(a, b, c, d), cover = b)).group

    val outcome = catalog.separate(b) as SeparateOutcome.Separated
    assertThat(outcome.coverChanged).isTrue()
    assertThat(outcome.dissolved).isFalse()
    assertThat(outcome.remaining).containsExactly(a, c, d)
    // La copertina provvisoria e' la piu' recente dei rimasti, ed e' una sola cella.
    val cover = db.mediaDao().group(group.value)!!.coverMediaId!!
    assertThat(cover).isEqualTo(d.value)
    assertThat(indexed(listOf(a, b, c, d))).containsExactly(d.value, b.value)
  }

  @Test
  fun laCopertinaScelta_MostraQuestaComePrincipale_VinceEResta() = runBlocking<Unit> {
    val a = shot(1, second0 + 100)
    val b = shot(2, second0 + 200)
    val c = shot(3, second0 + 300)
    val outsider = shot(4, second0 + 400)
    val group = grouped(catalog.groupSimilar(listOf(a, b, c), cover = a)).group
    assertThat(indexed(listOf(a, b, c))).containsExactly(a.value)

    assertThat(catalog.setSimilarCover(group, c)).isTrue()
    assertThat(indexed(listOf(a, b, c))).containsExactly(c.value)
    assertThat(CellFlags.has(flags(c), CellFlags.SIMILAR_COPIES or CellFlags.GROUP_COVER)).isTrue()
    assertThat(CellFlags.has(flags(a), CellFlags.SIMILAR_COPIES or CellFlags.GROUP_COVER)).isFalse()
    assertThat(CellFlags.has(flags(a), CellFlags.GROUP_MEMBER)).isTrue()
    assertThat(catalog.similarGroupOf(a)!!.cover).isEqualTo(c)
    // Un altro ricalcolo del gruppo (un preferito sul membro a) non la rovina.
    catalog.setPersonalFlags(a, PersonalFlags(favorite = true))
    assertThat(indexed(listOf(a, b, c))).containsExactly(c.value)
    // Gia' la copertina: nessun cambiamento.
    val changes = Changes(this, catalog)
    assertThat(catalog.setSimilarCover(group, c)).isTrue()
    assertThat(changes.none()).isTrue()
    changes.job.cancel()

    // Non un membro, non un gruppo di copie simili, non un gruppo che c'e'.
    assertThat(catalog.setSimilarCover(group, outsider)).isFalse()
    val x = shot(5, second0 + 500)
    val y = shot(6, second0 + 510)
    val burst = catalog.group(MediaGroupKind.BURST, listOf(x, y), x)
    assertThat(catalog.setSimilarCover(burst, y)).isFalse()
    assertThat(catalog.setSimilarCover(dev.pampa.mycloudgallery.core.model.MediaGroupId("00000000-0000-4000-8000-0000000000bb"), c)).isFalse()
  }

  // --- La cella riassume le copie (§2.12 "Segni della cella") ---------------------------------------------------------------------------

  @Test
  fun laCellaDelGruppoEAlSicuroSoloSeOgniCopiaSulTelefonoLo() = runBlocking<Unit> {
    // a: l'originale del telefono; b: la copia convertita sul NAS (pixel migliori, e' la copertina).
    val a = shot(1, second0 + 100)
    val b = catalog.recordAll(listOf(nasObservation("Cloud/IMG_1.jpg", smallFull(2))), seenAt = T0).single().mediaId!!
    catalog.updateMetadata(catalog.versionOf(b), exifMetadata(second0 + 100, 6000, 4000))
    val copyOfB = catalog.media(b)!!.locations.single().id
    catalog.markVerified(copyOfB, smallFull(2).fullHash!!, T0)
    assertThat(catalog.safety(b)!!.status).isEqualTo(SafetyStatus.SAFE)
    grouped(catalog.groupSimilar(listOf(a, b), cover = b))

    // La copertina e' al sicuro ma l'originale del telefono no: niente scudo, e la cella dice che c'e' anche sul telefono.
    var cell = flags(b)
    assertThat(CellFlags.has(cell, CellFlags.SAFE)).isFalse()
    assertThat(CellFlags.has(cell, CellFlags.ON_PHONE)).isTrue()
    assertThat(CellFlags.has(cell, CellFlags.ON_NAS)).isTrue()
    assertThat(CellFlags.has(cell, CellFlags.SIMILAR_COPIES)).isTrue()

    // L'originale va al sicuro: scudo.
    val copyOfA = AttachedCopy(a, catalog.versionOf(a), nasLocation("Backup/S25/IMG_1.jpg"), FileStamp(2_000, T0), T0 + 1)
    catalog.attachCopies(listOf(copyOfA))
    cell = flags(b)
    assertThat(CellFlags.has(cell, CellFlags.SAFE)).isTrue()
    assertThat(CellFlags.has(cell, CellFlags.BACKUP_PROBLEM)).isFalse()

    // Il problema di una copia sale sulla cella e prevale sullo scudo; tolto, torna lo scudo.
    catalog.markVerified(catalog.nasLocationIdOf(a), smallFull(99).fullHash!!, T0 + 2)
    catalog.markBackupProblem(a, "verify_failed")
    cell = flags(b)
    assertThat(CellFlags.has(cell, CellFlags.BACKUP_PROBLEM)).isTrue()
    assertThat(CellFlags.has(cell, CellFlags.SAFE)).isFalse()
    // L'aggregato non sporca i membri: la copia non toccata e' al sicuro per conto suo.
    assertThat(CellFlags.has(flags(b), CellFlags.SIMILAR_COPIES)).isTrue()
    assertThat(CellFlags.has(flags(a), CellFlags.SIMILAR_COPIES)).isFalse()
  }
}
