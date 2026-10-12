package dev.pampa.mycloudgallery.core.family.survey

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.ExcludedFolder
import dev.pampa.mycloudgallery.core.family.contract.ExclusionReason
import dev.pampa.mycloudgallery.core.nas.contract.DirEntry
import dev.pampa.mycloudgallery.core.nas.contract.DirPage
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il sondaggio delle cartelle su elenchi sintetici modellati sui dati veri del NAS di casa (docs/inventario-nas.md
 * §4-bis): copertine con mediana di 40 KB nelle cartelle di musica, MKV e MP4 da gigabyte nelle cartelle dei film,
 * foto di famiglia da 1,8 MB di mediana. E-N14, E-N15. Limiti: 2 livelli, 5000 voci, annullabile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderSurveyTest {

  private companion object {
    const val KB = 1024L
    const val MB = 1024L * 1024
  }

  private fun dir(name: String) = DirEntry(name, isDirectory = true, size = 0, modifiedAt = 0)

  private fun file(name: String, size: Long) = DirEntry(name, isDirectory = false, size = size, modifiedAt = 0)

  /** Una share finta: i contenuti per percorso ("" = radice). Ricorda quali cartelle si sono aperte e con che limite. */
  private class FakeShare(private val tree: Map<String, List<DirEntry>>) : NasBrowser {
    val listed = mutableListOf<String>()
    val limits = mutableListOf<Int>()
    var failRoot = false
    var failOn: Set<String> = emptySet()

    override suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage> {
      val key = dir?.value ?: ""
      listed += key
      limits += limit
      if ((key == "" && failRoot) || key in failOn) return NasResult.Err(NasError.Timeout)
      val all = tree[key].orEmpty()
      return NasResult.Ok(DirPage(all.take(limit), truncated = all.size > limit))
    }
  }

  private fun survey(share: FakeShare, max: Int = DefaultFolderSurvey.MAX_ENTRIES) = DefaultFolderSurvey({ share }, max)

  private fun List<ExcludedFolder>.byPath() = associate { it.path to it.reason }

  @Test
  fun riconoscePerNomeLeCartelleDiMusicaEDiFilmENonQuelleDiFamiglia() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(
          dir("Musica"), dir("Film"), dir("Serie TV"), dir("Foto famiglia"), dir("Album foto 2019"), dir("Filmati"),
          dir("Video"), dir("Vacanze"), dir("Soundtrack"), dir("Copertine"), file("note.txt", 2 * KB),
        ),
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.excluded.byPath()).containsExactly(
      "Musica", ExclusionReason.MUSIC,
      "Film", ExclusionReason.MOVIES,
      "Serie TV", ExclusionReason.MOVIES,
      "Soundtrack", ExclusionReason.MUSIC,
      "Copertine", ExclusionReason.SMALL_COVERS,
    )
    // "Album foto", "Filmati" e "Video" sono cartelle di famiglia: non si propongono mai.
    assertThat(proposal.truncated).isFalse()
  }

  @Test
  fun riconoscePerContenutoUnaCartellaDiMusicaConLeSueCopertine() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("Rock"), dir("Jazz")),
        "Rock" to listOf(
          file("01.mp3", 8 * MB), file("02.mp3", 9 * MB), file("03.flac", 30 * MB), file("cover.jpg", 40 * KB), file("folder.jpg", 38 * KB),
        ),
        "Jazz" to listOf(file("a.m4a", 7 * MB), file("b.m4a", 6 * MB)),
      ),
    )
    val proposal = survey(share).propose("NAS")
    // Rock ha tre file audio: musica, e dentro non si guarda. Jazz ne ha due: non basta, e senza immagini non si propone.
    assertThat(proposal.excluded.byPath()).containsExactly("Rock", ExclusionReason.MUSIC)
    assertThat(share.listed).containsExactly("", "Rock", "Jazz")
  }

  @Test
  fun leCopertineSiRiconosconoDallaDimensioneEDallAudioAccanto() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("Album A"), dir("Scansioni"), dir("Screenshot"), dir("Gita"), dir("Foto belle")),
        // Due brani e una copertina piccola: la copertina non va in galleria (E-N14).
        "Album A" to listOf(file("1.mp3", 5 * MB), file("2.mp3", 5 * MB), file("front.jpg", 40 * KB)),
        // Nessun audio ma immagini che si chiamano cover e folder, tutte piccole.
        "Scansioni" to listOf(file("cover.jpg", 90 * KB), file("back.jpg", 80 * KB)),
        // Immagini piccole senza audio e senza nomi da copertina: sono screenshot, non copertine.
        "Screenshot" to listOf(file("a.png", 200 * KB), file("b.png", 180 * KB), file("c.png", 220 * KB)),
        // Audio con una foto grande: la foto e' vera, non una copertina.
        "Gita" to listOf(file("canzone.mp3", 4 * MB), file("IMG_1.jpg", 3 * MB)),
        "Foto belle" to (1..100).map { file("IMG_$it.jpg", 2 * MB) },
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.excluded.byPath()).containsExactly(
      "Album A", ExclusionReason.SMALL_COVERS,
      "Scansioni", ExclusionReason.SMALL_COVERS,
    )
  }

  @Test
  fun iFilmSiRiconosconoDaiContenitoriNonDaiVideoDelTelefono() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("Da vedere"), dir("Telefono"), dir("Una cosa")),
        "Da vedere" to listOf(file("a.mkv", 4096 * MB), file("b.avi", 700 * MB), file("c.mp4", 129 * MB)),
        // 1.577 MP4 da telefono (fino a 9,4 GB) sono i video di famiglia: mai nella proposta.
        "Telefono" to (1..50).map { file("VID_$it.mp4", 900 * MB) } + file("scherma.mov", 2000 * MB),
        "Una cosa" to listOf(file("registrazione.mkv", 13_000 * MB)),
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.excluded.byPath()).containsExactly("Da vedere", ExclusionReason.MOVIES)
  }

  @Test
  fun lecartelleDiSistemaEDelleAppSiRiconosconoDalNome() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("@eaDir"), dir("#recycle"), dir(".mycloudgallery"), dir("Plex Media Server"), dir(".Trash-1000"), dir("Documenti")),
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.excluded.byPath()).containsExactly(
      "@eaDir", ExclusionReason.SYSTEM_FOLDER,
      "#recycle", ExclusionReason.SYSTEM_FOLDER,
      ".mycloudgallery", ExclusionReason.APP_FOLDER,
      "Plex Media Server", ExclusionReason.APP_FOLDER,
      ".Trash-1000", ExclusionReason.SYSTEM_FOLDER,
    )
  }

  @Test
  fun siApronoSoloDueLivelliELeNipotiSiVedonoPerNome() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("Archivio"), dir("Altro")),
        "Archivio" to listOf(dir("Musica"), dir("2019"), dir("Film"), file("x.jpg", 2 * MB)),
        "Altro" to listOf(dir("Foto")),
        // Queste non si dovrebbero mai aprire.
        "Archivio/2019" to listOf(file("trappola.mp3", 1)),
        "Archivio/Musica" to listOf(file("trappola.mp3", 1)),
        "Altro/Foto" to listOf(file("trappola.mp3", 1)),
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(share.listed).containsExactly("", "Archivio", "Altro")
    assertThat(proposal.excluded.byPath()).containsExactly(
      "Archivio/Musica", ExclusionReason.MUSIC,
      "Archivio/Film", ExclusionReason.MOVIES,
    )
  }

  @Test
  fun unaCartellaGiaProposataNonSiApreEIFigliNonSiPropongonoDiNuovo() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("Musica")),
        "Musica" to listOf(dir("Film")),
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.excluded.byPath()).containsExactly("Musica", ExclusionReason.MUSIC)
    assertThat(share.listed).containsExactly("")
  }

  @Test
  fun alCinquemilaVociSiFermaEDiceCheEParziale() = runTest {
    val share = FakeShare(
      mapOf(
        "" to (1..6000).map { file("IMG_$it.jpg", 2 * MB) },
      ),
    )
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.scanned).isEqualTo(5000)
    assertThat(proposal.truncated).isTrue()
    // Il limite arriva al NAS: non si chiede un elenco piu' lungo di quello che resta.
    assertThat(share.limits).containsExactly(5000)
  }

  @Test
  fun illimiteDelleVociVaRipartitoFraLeCartelle() = runTest {
    val big = (1..2000).map { file("a$it.jpg", MB) }
    val share = FakeShare(
      mapOf(
        "" to listOf(dir("Uno"), dir("Due"), dir("Tre"), dir("Quattro")),
        "Uno" to big,
        "Due" to big,
        "Tre" to big,
        "Quattro" to big,
      ),
    )
    val proposal = survey(share).propose("NAS")
    // 4 voci della radice + 2000 + 2000 + 996 (in ordine di nome): la terza cartella e' troncata, la quarta non si apre.
    assertThat(proposal.scanned).isEqualTo(5000)
    assertThat(proposal.truncated).isTrue()
    assertThat(share.listed).containsExactly("", "Due", "Quattro", "Tre").inOrder()
  }

  @Test
  fun unaProposta_conMenoDelLimiteNonEParziale() = runTest {
    val share = FakeShare(mapOf("" to listOf(dir("Musica"), file("a.jpg", MB))))
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.scanned).isEqualTo(2)
    assertThat(proposal.truncated).isFalse()
  }

  @Test
  fun siPuoAnnullare() = runTest {
    val gate = CompletableDeferred<Unit>()
    val listed = mutableListOf<String>()
    val browser = object : NasBrowser {
      override suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage> {
        listed += dir?.value ?: ""
        if (dir == null) return NasResult.Ok(DirPage(listOf(dir("Uno"), dir("Due")), truncated = false))
        gate.await() // il NAS "non risponde" per la prima sottocartella
        return NasResult.Ok(DirPage(emptyList(), truncated = false))
      }
    }
    val job = launch { DefaultFolderSurvey({ browser }).propose("NAS") }
    runCurrent()
    assertThat(listed).containsExactly("", "Due").inOrder()
    job.cancel()
    advanceUntilIdle()
    assertThat(job.isCancelled).isTrue()
    // Annullato: non si e' passati alla cartella dopo.
    assertThat(listed).containsExactly("", "Due").inOrder()
  }

  @Test
  fun unNomeOstileEUnErroreDelNasNonFannoCadereIlSondaggio() = runTest {
    val share = FakeShare(
      mapOf(
        "" to listOf(dir(".."), dir("a\\b"), dir("CON"), dir("Rotta"), dir("Musica"), dir("Buona")),
        "Buona" to listOf(dir("Film")),
      ),
    )
    share.failOn = setOf("Rotta")
    val proposal = survey(share).propose("NAS")
    assertThat(proposal.excluded.byPath()).containsExactly("Musica", ExclusionReason.MUSIC, "Buona/Film", ExclusionReason.MOVIES)
    assertThat(share.listed).doesNotContain("..")

    val broken = FakeShare(emptyMap()).also { it.failRoot = true }
    val empty = survey(broken).propose("NAS")
    assertThat(empty.excluded).isEmpty()
    assertThat(empty.scanned).isEqualTo(0)
  }

  @Test
  fun laPropostaEOrdinataEDeterministica() = runTest {
    val share = FakeShare(mapOf("" to listOf(dir("zeta Film"), dir("Musica"), dir("alfa Film"), dir("beta music"))))
    val paths = survey(share).propose("NAS").excluded.map { it.path }
    assertThat(paths).containsExactly("alfa Film", "beta music", "Musica", "zeta Film").inOrder()
  }
}
