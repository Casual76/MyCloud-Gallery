package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.GroupCandidate
import dev.pampa.mycloudgallery.core.metadata.contract.GroupHint
import dev.pampa.mycloudgallery.core.metadata.contract.GroupingRules
import dev.pampa.mycloudgallery.core.metadata.contract.ProposedGroup
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Raffiche e coppie RAW+JPEG (D20): "nel dubbio non si raggruppa". */
class GroupingRulesTest {

  private val folder = "phone:external_primary:dcim/camera/"

  private fun photo(
    name: String,
    atMs: Long,
    model: String? = "SM-S931B",
    kind: MediaKind = MediaKind.PHOTO,
    folderKey: String = folder,
    hint: GroupHint? = null,
  ): GroupCandidate {
    val parts = dev.pampa.mycloudgallery.core.metadata.format.NameParts.of(name)
    return GroupCandidate(
      MediaId.random(), folderKey, name, "image/jpeg", kind, atMs,
      hint ?: GroupHint(parts.base, parts.burstIndex, parts.isCover, null, model),
    )
  }

  private val t0 = 1_710_238_800_000L

  private fun List<ProposedGroup>.describe(all: List<GroupCandidate>): List<String> = map { g ->
    val ix = { id: MediaId -> all.indexOfFirst { it.id == id } }
    "${g.kind}:${g.members.map(ix).joinToString(",")}@${ix(g.cover)}"
  }

  // ---- raffiche ----------------------------------------------------------------------------------------------------

  @Test
  fun unaRaffichaSamsungDiTreScatti() {
    val shots = listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101530_002.jpg", t0 + 300), photo("20240312_101530_003.jpg", t0 + 600))
    assertThat(GroupingRules.propose(shots).describe(shots)).containsExactly("BURST:0,1,2@0")
  }

  @Test
  fun unaRaffichaAndroidConLaCopertina() {
    val shots = listOf(
      photo("IMG_20240312_101530_BURST001.jpg", t0),
      photo("IMG_20240312_101530_BURST002_COVER.jpg", t0 + 200),
      photo("IMG_20240312_101530_BURST003.jpg", t0 + 400),
    )
    assertThat(GroupingRules.propose(shots).describe(shots)).containsExactly("BURST:0,1,2@1")
  }

  @Test
  fun unaRaffichaDiGoogleCameraIdentificataDalNumeroInTesta() {
    val shots = listOf(
      photo("00001IMG_00001_BURST20240312101530123.jpg", t0),
      photo("00002IMG_00002_BURST20240312101530123.jpg", t0 + 100),
      photo("00003IMG_00003_BURST20240312101530123_COVER.jpg", t0 + 200),
    )
    assertThat(GroupingRules.propose(shots).describe(shots)).containsExactly("BURST:0,1,2@2")
  }

  @Test
  fun leFotoPossonoArrivareInQualunqueOrdine() {
    val a = photo("20240312_101530_001.jpg", t0)
    val b = photo("20240312_101530_002.jpg", t0 + 300)
    val c = photo("20240312_101530_003.jpg", t0 + 600)
    val groups = GroupingRules.propose(listOf(c, a, b))
    assertThat(groups).hasSize(1)
    assertThat(groups[0].members).containsExactly(a.id, b.id, c.id).inOrder()
    assertThat(groups[0].cover).isEqualTo(a.id)
  }

  @Test
  fun unaRaffichaSiSpezzaDoveIlTempoSiInterrompe() {
    val shots = listOf(
      photo("20240312_101530_001.jpg", t0),
      photo("20240312_101530_002.jpg", t0 + 500),
      photo("20240312_101530_003.jpg", t0 + 5_000),
    )
    assertThat(GroupingRules.propose(shots).describe(shots)).containsExactly("BURST:0,1@0")
  }

  @Test
  fun aPiuDiUnSecondoL_UnoDall_AltroNonSiRaggruppa() {
    val shots = listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101530_002.jpg", t0 + 1_000))
    assertThat(GroupingRules.propose(shots)).isEmpty()
    val close = listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101530_002.jpg", t0 + 999))
    assertThat(GroupingRules.propose(close)).hasSize(1)
  }

  @Test
  fun nelDubbioNonSiRaggruppa() {
    // modelli diversi
    assertThat(GroupingRules.propose(listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101530_002.jpg", t0 + 100, model = "Pixel 9")))).isEmpty()
    // modello sconosciuto
    assertThat(GroupingRules.propose(listOf(photo("20240312_101530_001.jpg", t0, model = null), photo("20240312_101530_002.jpg", t0 + 100, model = null)))).isEmpty()
    // un solo scatto con suffisso
    assertThat(GroupingRules.propose(listOf(photo("20240312_101530_001.jpg", t0)))).isEmpty()
    // cartelle diverse
    assertThat(
      GroupingRules.propose(listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101530_002.jpg", t0 + 100, folderKey = "phone:external_primary:pictures/"))),
    ).isEmpty()
    // nomi base diversi
    assertThat(GroupingRules.propose(listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101531_002.jpg", t0 + 100)))).isEmpty()
    // lo stesso numero di scatto due volte: ambiguo
    assertThat(GroupingRules.propose(listOf(photo("20240312_101530_001.jpg", t0), photo("20240312_101530_001.JPG", t0 + 100)))).isEmpty()
    // il primo senza suffisso: una sola foto col suffisso
    assertThat(GroupingRules.propose(listOf(photo("20240312_101530.jpg", t0), photo("20240312_101530_001.jpg", t0 + 100)))).isEmpty()
    // video e RAW non sono raffiche
    assertThat(
      GroupingRules.propose(listOf(photo("20240312_101530_001.mp4", t0, kind = MediaKind.VIDEO), photo("20240312_101530_002.mp4", t0 + 100, kind = MediaKind.VIDEO))),
    ).isEmpty()
  }

  @Test
  fun inomiQualunqueNonSonoRaffiche() {
    // IMG_123 e foto (1) non hanno data e ora nel nome: non sono suffissi di raffica.
    assertThat(GroupingRules.propose(listOf(photo("IMG_123.jpg", t0), photo("IMG_124.jpg", t0 + 100)))).isEmpty()
    assertThat(GroupingRules.propose(listOf(photo("foto (1).jpg", t0), photo("foto (2).jpg", t0 + 100)))).isEmpty()
  }

  @Test
  fun leCopieNumerateConLaStessaOraNonSonoUnaRaffica() {
    // 20240312_101530(1) e (2) con lo stesso istante: due copie dello stesso scatto, non una raffica.
    val copies = listOf(photo("20240312_101530(1).jpg", t0), photo("20240312_101530(2).jpg", t0))
    assertThat(GroupingRules.propose(copies)).isEmpty()
    // Con ore diverse (a meno di 1 s) si.
    val shots = listOf(photo("20240312_101530(1).jpg", t0), photo("20240312_101530(2).jpg", t0 + 250))
    assertThat(GroupingRules.propose(shots)).hasSize(1)
  }

  @Test
  fun senzaSuggerimentoDelFileIlModelloNonSiConoscePerCuiNonSiRaggruppa() {
    val a = GroupCandidate(MediaId.random(), folder, "20240312_101530_001.jpg", "image/jpeg", MediaKind.PHOTO, t0, null)
    val b = GroupCandidate(MediaId.random(), folder, "20240312_101530_002.jpg", "image/jpeg", MediaKind.PHOTO, t0 + 100, null)
    assertThat(GroupingRules.propose(listOf(a, b))).isEmpty()
  }

  // ---- RAW+JPEG ---------------------------------------------------------------------------------------------------------

  @Test
  fun unaCoppiaRawJpeg() {
    val raw = photo("20240312_101530.dng", t0, kind = MediaKind.RAW_PHOTO)
    val jpg = photo("20240312_101530.jpg", t0 + 800)
    val groups = GroupingRules.propose(listOf(raw, jpg))
    assertThat(groups).hasSize(1)
    assertThat(groups[0].kind).isEqualTo(MediaGroupKind.RAW_JPEG_PAIR)
    assertThat(groups[0].cover).isEqualTo(jpg.id)
    assertThat(groups[0].members).containsExactly(jpg.id, raw.id)
  }

  @Test
  fun lACoppiaAcceptaHeicEIlNomeSenzaDistinzioneDiMaiuscole() {
    val raw = photo("IMG_0042.DNG", t0, kind = MediaKind.RAW_PHOTO)
    val heic = photo("img_0042.HEIC", t0 + 1_999)
    assertThat(GroupingRules.propose(listOf(raw, heic))).hasSize(1)
  }

  @Test
  fun laCoppiaVuoleMenoDiDueSecondi() {
    val raw = photo("20240312_101530.dng", t0, kind = MediaKind.RAW_PHOTO)
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101530.jpg", t0 + 2_000)))).isEmpty()
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101530.jpg", t0 - 1_999)))).hasSize(1)
  }

  @Test
  fun laCoppiaVuoleLaStessaCartellaELaStessaBase() {
    val raw = photo("20240312_101530.dng", t0, kind = MediaKind.RAW_PHOTO)
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101530.jpg", t0, folderKey = "phone:external_primary:pictures/")))).isEmpty()
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101531.jpg", t0)))).isEmpty()
  }

  @Test
  fun nelDubbioDellaCoppiaNonSiRaggruppa() {
    val raw = photo("20240312_101530.dng", t0, kind = MediaKind.RAW_PHOTO)
    // due immagini per un RAW
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101530.jpg", t0), photo("20240312_101530.heic", t0)))).isEmpty()
    // due RAW per un'immagine
    val raw2 = photo("20240312_101530.DNG", t0, kind = MediaKind.RAW_PHOTO)
    assertThat(GroupingRules.propose(listOf(raw, raw2, photo("20240312_101530.jpg", t0)))).isEmpty()
    // un PNG non e' la coppia di un RAW
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101530.png", t0)))).isEmpty()
    // un video con lo stesso nome base no
    assertThat(GroupingRules.propose(listOf(raw, photo("20240312_101530.jpg", t0, kind = MediaKind.VIDEO)))).isEmpty()
  }

  @Test
  fun unMediaStaInUnSoloGruppo() {
    // La coppia RAW+JPEG ha la precedenza: il JPEG non entra anche in una raffica.
    val raw = photo("20240312_101530_001.dng", t0, kind = MediaKind.RAW_PHOTO)
    val a = photo("20240312_101530_001.jpg", t0)
    val b = photo("20240312_101530_002.jpg", t0 + 200)
    val groups = GroupingRules.propose(listOf(raw, a, b))
    val seen = groups.flatMap { it.members }
    assertThat(seen).containsNoDuplicates()
    assertThat(groups.map { it.kind }).containsExactly(MediaGroupKind.RAW_JPEG_PAIR)
  }

  @Test
  fun idDuplicatiNonFormanoGruppi() {
    val a = photo("20240312_101530.dng", t0, kind = MediaKind.RAW_PHOTO)
    assertThat(GroupingRules.propose(listOf(a, a))).isEmpty()
    assertThat(GroupingRules.propose(emptyList())).isEmpty()
  }

  // ---- dai file di prova, passando dall'estrattore -------------------------------------------------------------------------

  @Test
  fun gliInsiemiDelCatalogoDannoIGruppiAttesi() = runTest {
    for (set in FixtureCatalog.groupSets()) {
      val extractor = testExtractor()
      val candidates = set.members.map { f ->
        val read = f.extract(extractor) as Extraction.Read
        GroupCandidate(
          MediaId.random(), folder, f.name, f.mime, read.kind, read.metadata.captureTime!!.utcMillis, read.groupHint,
        )
      }
      val described = GroupingRules.propose(candidates).describe(candidates)
      assertWithMessage(set.title).that(described).containsExactlyElementsIn(set.expectedGroups)
    }
  }

  @Test
  fun iFileDelCatalogoRispettanoIValoriAttesiDegliInsiemi() = runTest {
    for (set in FixtureCatalog.groupSets()) {
      for (f in set.members) {
        val failures = f.mismatches(f.extract(testExtractor()))
        assertWithMessage(f.name).that(failures).isEmpty()
      }
    }
    // Un hint prodotto dall'estrattore porta nome base, numero di scatto, copertina, sotto-secondi e modello.
    val cover = FixtureCatalog.groupSets()[1].members[1]
    val hint = (cover.extract(testExtractor()) as Extraction.Read).groupHint!!
    assertThat(hint.baseName).isEqualTo("IMG_20240312_111000")
    assertThat(hint.burstIndex).isEqualTo(2)
    assertThat(hint.isCover).isTrue()
    assertThat(hint.subSecondMs).isEqualTo(300)
    assertThat(hint.cameraModel).isEqualTo("SM-S931B")
    assertThat(ByteArrayMediaBytes(cover.bytes()).size).isGreaterThan(0)
  }
}
