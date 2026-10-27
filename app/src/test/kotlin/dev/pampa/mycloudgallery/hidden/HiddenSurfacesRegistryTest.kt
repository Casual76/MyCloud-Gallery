package dev.pampa.mycloudgallery.hidden

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Fase 7 (P9, D22, C27, §4.3 "Nascosti: ogni superficie nuova si registra in `HiddenSurfaces` e ha il test «un nascosto perfetto non esce mai»"):
 * **il registro fa fallire chi dimentica una superficie.** Non e' un elenco da tenere a mano: lo si confronta con i sorgenti.
 *
 *  - ogni file di produzione che nomina `VisibleMedia`, `HiddenMask` o `WidgetFeed` (cioe' ogni superficie nuova che passa dalla sola fonte dei
 *    nascosti) e' elencato da una superficie del registro, o e' uno dei file che legano i contratti al catalogo;
 *  - ogni file elencato esiste, e ogni riga della tabella del §2.1 del progetto ha almeno una superficie;
 *  - una superficie provata con un riferimento (album, viaggi) o in attesa (Doppioni, "Aggiungi ad album") passa a `PRODUCTION` quando arriva il
 *    codice vero: se i suoi marcatori compaiono nel repository e la riga e' ancora quella di prima, il registro fallisce;
 *  - le prove "anche da" citate esistono davvero.
 * Ogni controllo e' provato al contrario su un repository finto (cartella temporanea).
 */
class HiddenSurfacesRegistryTest {

  private val names = HiddenSurfaces.contractNames

  @Test
  fun leSuperficiHannoIdUniciEOgniRigaDelProgettoHaUnaSuperficie() {
    val ids = HiddenSurfaces.entries.map { it.id }
    assertThat(ids).containsNoDuplicates()
    val covered = HiddenSurfaces.entries.map { it.row }.toSet() + HiddenSurfaces.gated.mapNotNull { it.row }
    assertWithMessage("righe del §2.1 senza superficie").that(SpecRow.entries.toSet() - covered).isEmpty()
    assertThat(HiddenSurfaces.entries.map { it.owner }.toSet()).containsAtLeast("P3", "P6", "P7", "P8", "P10")
  }

  @Test
  fun ognFileCheNominaIContrattiDeiNascostiEUnaSuperficieORiconosciuto() {
    val unaccounted = RepoFiles.unaccounted(HiddenSurfaces.entries, HiddenSurfaces.wiring, names)
    assertWithMessage(
      "file che usano VisibleMedia, HiddenMask o WidgetFeed e non stanno in HiddenSurfaces (una superficie nuova va registrata, con la sua sonda): $unaccounted",
    ).that(unaccounted).isEmpty()
  }

  @Test
  fun ognFileCheMostraNomiDiFileDelNasEUnaSuperficieUnLettoreDietroIlCancelloOLaPaginaInattiva() {
    val unaccounted = RepoFiles.unaccountedNames(HiddenSurfaces.entries, HiddenSurfaces.gated, HiddenSurfaces.dormant, HiddenSurfaces.nameBearingTypes)
    assertWithMessage(
      "file che usano LargestFile, NasTrashItem, NasFolderEntry o NasTrashRow (nome e cartella di un file del NAS) e non stanno in HiddenSurfaces: una pagina che mostra nomi " +
        "senza passare da VisibleMedia o HiddenMask va registrata, con la sua sonda o dietro il cancello (R4): $unaccounted",
    ).that(unaccounted).isEmpty()
  }

  @Test
  fun ilRegistroDeiNomiVedeLeSchermateVere() {
    val seen = RepoFiles.nameSurfaces(HiddenSurfaces.nameBearingTypes)
    // Controllo positivo: senza, il test di sopra passerebbe a vuoto.
    assertThat(seen.any { it.endsWith("feature/nas/space/NasSpaceScreen.kt") }).isTrue()
    assertThat(seen.any { it.endsWith("feature/nas/trash/NasTrashScreen.kt") }).isTrue()
    assertThat(seen.any { it.endsWith("feature/library/trash/NasTrashRows.kt") }).isTrue()
    assertThat(seen.any { it.endsWith("nas/NasOrganizer.kt") }).isTrue()
    // I core-* producono i dati: non sono schermate.
    assertThat(seen.none { it.startsWith("core-") }).isTrue()
  }

  @Test
  fun unaSchermataCheMostraNomiSenzaEssereRegistrataFaFallireIlControllo() {
    val root = fakeRepo { r ->
      write(r, "feature-nas/src/main/kotlin/x/NuovaPagina.kt", "package x\nclass NuovaPagina(val files: List<LargestFile>)\n")
      write(r, "feature-nas/src/main/kotlin/x/contract/Tipi.kt", "package x\nclass LargestFile\n")
      write(r, "core-nasops/src/main/kotlin/x/Servizio.kt", "package x\nclass Servizio(val files: List<LargestFile>)\n")
      write(r, "app/src/main/kotlin/x/Commento.kt", "package x\n// LargestFile in un commento\nclass Niente\n")
    }
    try {
      val missing = RepoFiles.unaccountedNames(HiddenSurfaces.entries, HiddenSurfaces.gated, HiddenSurfaces.dormant, HiddenSurfaces.nameBearingTypes, root)
      assertThat(missing).containsExactly("feature-nas/src/main/kotlin/x/NuovaPagina.kt")
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun ilRegistroVedeLeSuperficiVere() {
    val consumers = RepoFiles.consumers(names)
    // Controllo positivo: la scansione trova davvero i file (altrimenti il test di sopra passerebbe sempre).
    assertThat(consumers.any { it.endsWith("core/library/visibility/CatalogVisibility.kt") }).isTrue()
    assertThat(consumers.any { it.endsWith("core/library/visibility/RotatingWidgetFeed.kt") }).isTrue()
    assertThat(consumers).contains(HiddenSurfaces.WIDGET_CONTENT_FILE)
    assertThat(consumers.any { it.endsWith("gallery/GalleryGraph.kt") }).isTrue()
    // I contratti, i null-object e i fake non sono superfici.
    assertThat(consumers.none { "/contract/" in it || "/noop/" in it || "/testing/" in it }).isTrue()
  }

  @Test
  fun iFileElencatiEsistono() {
    for (entry in HiddenSurfaces.entries) for (path in entry.sources) assertWithMessage("${entry.id}: $path").that(RepoFiles.exists(path)).isTrue()
    for (reader in HiddenSurfaces.gated) for (path in reader.sources) assertWithMessage("${reader.id}: $path").that(RepoFiles.exists(path)).isTrue()
    for (path in HiddenSurfaces.dormant) assertWithMessage(path).that(RepoFiles.exists(path)).isTrue()
    for (path in HiddenSurfaces.wiring) assertWithMessage(path).that(RepoFiles.exists(path)).isTrue()
  }

  @Test
  fun quandoArrivaIlCodiceVeroLaRigaDiRiferimentoNonBastaPiu() {
    for (entry in HiddenSurfaces.entries.filter { it.backing != Backing.PRODUCTION }) {
      val arrived = entry.landedWhen.filter { RepoFiles.exists(it) }
      assertWithMessage(
        "${entry.id} (${entry.owner}): il codice vero c'e' ($arrived) ma la superficie e' ancora ${entry.backing}: scrivi la sonda sul codice vero e passa la riga a PRODUCTION",
      ).that(arrived).isEmpty()
    }
  }

  @Test
  fun leProveCitateEsistono() {
    val classes = RepoFiles.testClassNames()
    val cited = HiddenSurfaces.entries.flatMap { it.alsoProvedBy } + HiddenSurfaces.gated.flatMap { it.proofs }
    assertWithMessage("prove citate che non esistono").that(cited.filter { it !in classes }).isEmpty()
    assertThat(HiddenSurfaces.gated.map { it.id }).containsAtLeast("hidden-page", "recently-deleted")
  }

  // --- Il registro e' provato al contrario su un repository finto -----------------------------------------------------------------------

  private fun fakeRepo(block: (File) -> Unit): File {
    val root = Files.createTempDirectory("repo-finto").toFile()
    File(root, "settings.gradle").writeText("include ':core-common'\ninclude ':feature-albums'\ninclude ':feature-nas'\ninclude ':core-nasops'\ninclude ':app'\n")
    block(root)
    return root
  }

  private fun write(root: File, path: String, text: String) {
    File(root, path).also { it.parentFile.mkdirs() }.writeText(text)
  }

  @Test
  fun unaSuperficieNuovaNonRegistrataFaFallireIlControllo() {
    val root = fakeRepo { r ->
      write(r, "feature-albums/src/main/kotlin/x/ui/AlbumPage.kt", "package x\nimport dev.pampa.mycloudgallery.core.library.contract.VisibleMedia\nclass AlbumPage(val v: VisibleMedia)\n")
      write(r, "feature-albums/src/main/kotlin/x/contract/Contratto.kt", "package x\ninterface Usa { fun f(m: HiddenMask) }\n")
      write(r, "feature-albums/src/main/kotlin/x/noop/Niente.kt", "package x\nobject Niente : Usa { override fun f(m: HiddenMask) = Unit }\n")
      write(r, "app/src/debug/kotlin/x/Widget.kt", "package x\nclass Widget(val feed: WidgetFeed)\n")
      write(r, "app/src/main/kotlin/x/Commento.kt", "package x\n// non usa VisibleMedia, e' un commento\nclass Niente\n")
    }
    try {
      val missing = RepoFiles.unaccounted(HiddenSurfaces.entries, HiddenSurfaces.wiring, names, root)
      assertThat(missing).containsExactly("feature-albums/src/main/kotlin/x/ui/AlbumPage.kt", "app/src/debug/kotlin/x/Widget.kt")
      // Registrata (una riga che elenca il file) non e' piu' un problema.
      val registered = SurfaceEntry(
        "album-page", "Pagina dell'album", SpecRow.ALBUMS, "P10", Backing.PRODUCTION,
        sources = listOf("feature-albums/src/main/kotlin/x/ui/AlbumPage.kt", "app/src/debug/kotlin/x/Widget.kt"),
        probe = { w -> Probed(shown = w.libraryCells) },
      )
      assertThat(RepoFiles.unaccounted(HiddenSurfaces.entries + registered, HiddenSurfaces.wiring, names, root)).isEmpty()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun unaSuperficieInAttesaOdiRiferimentoNonPuoFingereDiEssereVeraQuandoIlCodiceArriva() {
    val pending = SurfaceEntry("futura", "Futura", SpecRow.UTILITY, "P8", Backing.PENDING, landedWhen = listOf("core-library/src/main/x/duplicates"))
    val root = fakeRepo { r -> write(r, "core-library/src/main/x/duplicates/Doppioni.kt", "package x\nclass Doppioni\n") }
    try {
      assertThat(pending.landedWhen.filter { RepoFiles.exists(it, root) }).isNotEmpty()
      assertThat(pending.landedWhen.filter { RepoFiles.exists(it) }).isEmpty()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun unaRigaSenzaSondaOSenzaMarcatoreNonSiScrive() {
    val noProbe = runCatching { SurfaceEntry("a", "A", SpecRow.LIBRARY, "x", Backing.PRODUCTION) }
    val noMarker = runCatching { SurfaceEntry("b", "B", SpecRow.LIBRARY, "x", Backing.REFERENCE, probe = { Probed(shown = emptySet()) }) }
    val pendingWithProbe = runCatching { SurfaceEntry("c", "C", SpecRow.LIBRARY, "x", Backing.PENDING, landedWhen = listOf("x"), probe = { Probed(shown = emptySet()) }) }
    assertThat(noProbe.isFailure).isTrue()
    assertThat(noMarker.isFailure).isTrue()
    assertThat(pendingWithProbe.isFailure).isTrue()
  }
}
