package dev.pampa.mycloudgallery.feature.library.grid

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryPresence
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.time.ZoneId
import org.junit.Test

/**
 * I segni della cella della Fase 5A (D15, D36; `docs/fase5-progetto.md` §2.12): dipendono dai **soli flag** della cella (nessuno stato
 * di caricamento sulle celle), `PROBLEM` vince su "al sicuro" e il lettore di schermo li dice. Nessuna composizione: la descrizione e la
 * traduzione dei flag sono funzioni pure.
 */
class CellBackupMarksTest {

  private val strings = GridStrings(
    today = "Oggi", yesterday = "Ieri", kindPhoto = "Foto", kindVideo = "Video", kindMotion = "Foto in movimento",
    kindRaw = "Foto RAW", kindAnimated = "Immagine animata", favoriteFeminine = "preferita", favoriteMasculine = "preferito",
    editedFeminine = "modificata", editedMasculine = "modificato", unreadable = "non leggibile", estimated = "data stimata",
    duration = "durata %1\$s", group = "%1\$s scatti", phoneOnly = "solo sul telefono", nasOnly = "solo sul NAS",
    both = "sul telefono e sul NAS", safe = "al sicuro sul NAS", toVerify = "copia sul NAS da verificare",
    backupProblem = "problema con il backup", similarCopies = "%1\$s copie simili",
    open = "Apri", select = "Seleziona", deselect = "Deseleziona", scrubber = "Scorri la timeline",
  )

  private val library = SyntheticLibrary(seed = 1L, count = 300)

  /** La prima cella che e' una pila (copertina di un gruppo, 2 o piu' elementi): serve il suo numero. */
  private val pile: Int = (0 until library.snapshot.size).first { library.snapshot.groupSizeAt(it) > 1 }

  private fun describe(index: Int, flags: Int): String {
    val snapshot = library.snapshot.withFlags(index, flags)
    return GridText.describe(snapshot, index, strings, FakePlaces(), ZoneId.of("UTC"))
  }

  @Test
  fun ilProblemaDelBackupVinceSuAlSicuro() {
    val flags = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.BACKUP_PROBLEM
    assertThat(PresenceBadge.of(flags)).isEqualTo(PresenceBadge.PROBLEM)
    assertThat(PresenceBadge.PROBLEM.toGallery()).isEqualTo(GalleryPresence.Problem)
    assertThat(PresenceBadge.SAFE.toGallery()).isEqualTo(GalleryPresence.Safe)
  }

  @Test
  fun ilProblemaEUnDiscoIntero() {
    assertThat(GalleryPresence.Problem.prominent).isTrue()
  }

  @Test
  fun lettoreDiSchermoDiceIlProblemaEMaiAlSicuro() {
    val text = describe(0, CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.BACKUP_PROBLEM)
    assertThat(text).endsWith("problema con il backup")
    assertThat(text).doesNotContain("al sicuro")
    assertThat(describe(0, CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE)).endsWith("al sicuro sul NAS")
  }

  @Test
  fun lePileDiCopieSimiliDiconoQuanteSono() {
    val size = library.snapshot.groupSizeAt(pile)
    val flags = library.snapshot.flagsAt(pile) or CellFlags.SIMILAR_COPIES
    assertThat(describe(pile, flags)).contains("$size copie simili")
    assertThat(describe(pile, flags)).doesNotContain("scatti")
  }

  @Test
  fun unaRaffica_NonDiceCopieSimili() {
    val size = library.snapshot.groupSizeAt(pile)
    val flags = library.snapshot.flagsAt(pile) and CellFlags.SIMILAR_COPIES.inv()
    assertThat(describe(pile, flags)).contains("$size scatti")
    assertThat(describe(pile, flags)).doesNotContain("copie simili")
  }

  @Test
  fun iSegniSonoLeggiDaiSoliFlagDellaCella() {
    // Nessuno stato di caricamento sulle celle (D15): ne' "in coda" ne' "in caricamento" hanno un bit.
    val bits = CellFlags.ALL.keys
    assertThat(bits.filter { it.contains("QUEUE", ignoreCase = true) || it.contains("UPLOAD", ignoreCase = true) }).isEmpty()
    assertThat(bits).containsAtLeast("BACKUP_PROBLEM", "SIMILAR_COPIES")
  }
}
