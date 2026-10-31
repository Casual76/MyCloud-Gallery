package dev.pampa.mycloudgallery.core.describe.rules

import dev.pampa.mycloudgallery.core.describe.contract.DescribeStrings
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.contract.MediaFacts
import dev.pampa.mycloudgallery.core.describe.noop.NoA11yTool
import dev.pampa.mycloudgallery.core.describe.noop.NoDescribePeople
import dev.pampa.mycloudgallery.core.describe.noop.NoDescribeText
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import java.time.DateTimeException
import java.time.ZoneId

/**
 * Le parole italiane delle descrizioni (quelle di `GridStrings` di feature-library e le quattro di D22). L'app e' solo in italiano (le date
 * passano da `GalleryDates`): servono ai `feature-*` che non hanno un descrittore legato da :app, e al descrittore stesso in produzione.
 * `ItalianDescribeStrings` (package `testing`) ha le stesse parole per le prove: un test le tiene uguali.
 */
object ItalianDescribeWords {
  val VALUE: DescribeStrings = DescribeStrings(
    kindPhoto = "Foto", kindVideo = "Video", kindMotion = "Foto in movimento", kindRaw = "Foto RAW", kindAnimated = "Immagine animata",
    favoriteFeminine = "preferita", favoriteMasculine = "preferito", editedFeminine = "modificata", editedMasculine = "modificato",
    unreadable = "non leggibile", estimated = "data stimata", duration = "durata %1\$s", group = "%1\$s scatti",
    similarCopies = "%1\$s copie simili", phoneOnly = "solo sul telefono", nasOnly = "solo sul NAS", both = "sul telefono e sul NAS",
    safe = "al sicuro sul NAS", toVerify = "copia sul NAS da verificare", backupProblem = "problema con il backup",
    withPeople = "con %1\$s", and = "e", others = "e altri %1\$s", withText = "con la scritta «%1\$s»",
  )
}

/**
 * Il descrittore per gli schermi (D22): una sola strada per le descrizioni di griglie, viewer, album, ricordi, mappa e ricerca.
 *
 * - [install] lo lega :app (P13) **una volta**, all'avvio, con le porte vere (Persone, testo letto, strumento di accessibilita') e i luoghi:
 *   da quel momento [shared] da' sempre lui, con la sua cache di `warm` che vale per tutte le schermate.
 * - Finche' non e' legato, [shared] da' un descrittore **con le porte null** (`NoDescribePeople`, `NoDescribeText`, `NoA11yTool`): solo i fatti
 *   del media, mai un nome, mai un testo letto (ripiego chiuso).
 * - [describeCell] costruisce la frase di una cella dell'istantanea; null se il giorno e' inutilizzabile (indice rovinato: la schermata dice
 *   quello che diceva prima per un media senza data).
 *
 * Costruita a richiesta, mai salvata, mai nei log (D22, C438).
 */
object MediaDescribers {

  @Volatile
  private var installed: MediaDescriber? = null

  private val plainDefault: MediaDescriber by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DefaultMediaDescriber(NoPlaceNames, NoDescribePeople, NoDescribeText, ItalianDescribeWords.VALUE, NoA11yTool)
  }

  /** Lo lega :app (P13) con le porte vere. Una volta all'avvio. */
  fun install(describer: MediaDescriber) {
    installed = describer
  }

  /** Torna al ripiego senza porte (le prove, e il blocco dell'app se lo vuole: dopo `forget`). */
  fun reset() {
    installed = null
  }

  /** Quello legato da :app, o il ripiego senza persone ne' testo con i [places] e le [strings] dati. */
  fun shared(places: PlaceNames = NoPlaceNames, strings: DescribeStrings = ItalianDescribeWords.VALUE): MediaDescriber =
    installed ?: if (places === NoPlaceNames && strings === ItalianDescribeWords.VALUE) plainDefault
    else DefaultMediaDescriber(places, NoDescribePeople, NoDescribeText, strings, NoA11yTool)

  /**
   * La frase della cella [index] di [snapshot] per [surface] (di norma [DescribeSurface.IN_APP]). Il fuso [zone] e' quello del telefono: l'ora
   * si omette se non concorda col giorno. Null se il giorno della cella non e' una data vera.
   */
  fun describeCell(
    describer: MediaDescriber,
    snapshot: TimelineSnapshot,
    index: Int,
    zone: ZoneId,
    surface: DescribeSurface = DescribeSurface.IN_APP,
  ): String? {
    val facts = try {
      MediaFacts.of(snapshot, index, zone).also { it.day }
    } catch (_: IllegalArgumentException) {
      return null
    } catch (_: DateTimeException) {
      return null
    }
    return describer.describe(snapshot.idAt(index), facts, surface)
  }
}
