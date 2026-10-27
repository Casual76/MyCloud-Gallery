package dev.pampa.mycloudgallery.feature.library.collections.testing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WbSunny
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.collections.FixedCollectionsSection

// Le sezioni degli altri pacchetti "come se ci fossero" (D33): per i test, gli screenshot e per chi sviluppa una sezione senza aspettare
// le altre. Dati inventati (nessun titolo, luogo o nome vero), in `src/main` come gli altri fake del modulo e mai nella release
// (`tools/check.ps1` cerca le classi `testing` nel dex). Ogni funzione e' il fornitore finto di una sezione: `FixedCollectionsSection`.

/** I fornitori finti di tutte le sezioni del §2.7 che la scheda non fa da sola. */
object CollectionsSectionSamples {

  /** Album: i miei e i condivisi insieme, con la parola "Condiviso" sui condivisi e il nome di chi li ha creati. Non si nasconde. */
  fun albums(): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.ALBUMS,
    CollectionEntryGroup(
      title = "Album",
      hideable = false,
      entries = listOf(
        CollectionEntry("albums/new", "Nuovo album", "Manuale o intelligente", Icons.Rounded.PhotoAlbum, FluidTone.Primary),
        CollectionEntry("albums/a1", "Estate in famiglia", "Di Giulia · 214 elementi", Icons.Rounded.PhotoAlbum, FluidTone.Info, trailing = "Condiviso"),
        CollectionEntry("albums/a2", "Casa nuova", "48 elementi", Icons.Rounded.PhotoAlbum, FluidTone.Neutral),
        CollectionEntry("albums/all", "Tutti gli album", "12 album", Icons.Rounded.PhotoAlbum, FluidTone.Neutral),
      ),
    ),
  )

  /** Album smart: separati dagli altri perche' si comportano diversamente (le "Suggerite"). */
  fun smart(): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.SMART,
    CollectionEntryGroup(
      title = "Album smart",
      entries = listOf(
        CollectionEntry("smart/s1", "Oslo 2026", "marzo 2026, Oslo · 312 elementi", Icons.Rounded.AutoAwesome, FluidTone.Primary, badge = 7),
        CollectionEntry("smart/s2", "Estati al mare", "ogni estate, mare · 1.204 elementi", Icons.Rounded.WbSunny, FluidTone.Warning),
      ),
    ),
  )

  /** Viaggi: le proposte dal piu' recente. */
  fun trips(): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.TRIPS,
    CollectionEntryGroup(
      title = "Viaggi",
      entries = listOf(
        CollectionEntry("trips/t1", "Toscana, agosto 2025", "9 giorni · 640 elementi", Icons.Rounded.Luggage, FluidTone.Info),
        CollectionEntry("trips/t2", "Sabato 12 aprile 2025", "Una giornata · 96 elementi", Icons.Rounded.Luggage, FluidTone.Success),
      ),
    ),
  )

  /**
   * Persone (Fase 8): finche' il riconoscimento dei volti e' spento la sezione ha la sola riga che lo propone; acceso, le persone.
   * Una sezione consegnata come dato non ha bisogno di niente di piu': e' una riga che `:app` sa aprire.
   *
   * Il testo della riga "Attiva" (P9): i dati dei volti **viaggiano fra i dispositivi dell'account** (nomi, unioni e divisioni arrivano
   * sul tablet), restano nell'area privata e si cancellano con "Disattiva e cancella": non sono "solo su questo telefono". Lo stesso testo
   * della sezione vera (`feature-people`, `PeopleSectionSources`).
   */
  fun people(recognitionOn: Boolean = false): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.PEOPLE,
    CollectionEntryGroup(
      title = "Persone",
      entries = if (recognitionOn) {
        listOf(CollectionEntry("people/p1", "Persone", "3 persone riconosciute", Icons.Rounded.Face, FluidTone.Primary))
      } else {
        listOf(
          CollectionEntry(
            "people/enable", "Attiva riconoscimento dei volti", "Raggruppa le foto per persona. I nomi viaggiano fra i tuoi dispositivi e restano tuoi.",
            Icons.Rounded.Face, FluidTone.Primary,
          ),
        )
      },
    ),
  )

  /** Cartelle NAS (oggi "Sul NAS": lo stesso gruppo che `:app` passa come `extraGroups`). */
  fun nas(operations: Int = 0): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.NAS,
    CollectionEntryGroup(
      title = "Cartelle NAS",
      entries = listOf(
        CollectionEntry("nas/folders", "Cartelle NAS", "Sfoglia, crea, rinomina, sposta ed elimina", Icons.Rounded.Folder, FluidTone.Primary),
        CollectionEntry("nas/space", "Spazio NAS", "Spazio libero, quota e file più grandi", Icons.Rounded.Storage, FluidTone.Info),
        CollectionEntry(
          "nas/operations", "Operazioni in corso",
          if (operations > 0) "$operations in corso adesso" else "Quello che sta facendo il NAS per te",
          Icons.Rounded.Sync, FluidTone.Success, badge = operations,
        ),
      ),
    ),
  )

  /** Utility (P8): le pagine-filtro, Doppioni, Eliminati di recente, Libera spazio. */
  fun utility(): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.UTILITY,
    CollectionEntryGroup(
      title = "Utility",
      entries = listOf(
        CollectionEntry("utility/duplicates", "Doppioni", "Copie identiche e foto simili", Icons.Rounded.ContentCopy, FluidTone.Warning),
        CollectionEntry("utility/screenshots", "Screenshot", "109 elementi", Icons.Rounded.Screenshot, FluidTone.Neutral),
        CollectionEntry("utility/videos", "Video", "82 elementi", Icons.Rounded.Videocam, FluidTone.Info),
        CollectionEntry("utility/trash", "Eliminati di recente", "Telefono e NAS insieme", Icons.Rounded.Delete, FluidTone.Danger),
        CollectionEntry("backup/free-space", "Libera spazio", "Liberalo dal telefono, in sicurezza", Icons.Rounded.CleaningServices, FluidTone.Primary),
      ),
    ),
  )

  /** Tutte, nell'ordine in cui le registrerebbe `:app`: serve a vedere la scheda piena. */
  fun all(): List<FixedCollectionsSection> = listOf(albums(), smart(), trips(), people(), nas(), utility())
}
