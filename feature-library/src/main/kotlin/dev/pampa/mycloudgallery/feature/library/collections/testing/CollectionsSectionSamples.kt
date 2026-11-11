package dev.pampa.mycloudgallery.feature.library.collections.testing

import android.content.res.Resources
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPage
import dev.pampa.mycloudgallery.feature.library.utility.utilityEntryGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
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
            "people/enable", "Attiva riconoscimento dei volti", "Raggruppa le foto per persona",
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

  /** Utility (P8): Modificate, Doppioni, Eliminati di recente, Libera spazio (H5: senza Screenshot, Video e WhatsApp, che sono nella scheda Album). */
  fun utility(): FixedCollectionsSection = FixedCollectionsSection(
    CollectionSections.UTILITY,
    CollectionEntryGroup(
      title = "Utility",
      entries = listOf(
        CollectionEntry("utility/edited", "Modificate", "37 elementi", Icons.Rounded.AutoFixHigh, FluidTone.Warning),
        CollectionEntry("utility/duplicates", "Doppioni", "Copie e scatti quasi uguali", Icons.Rounded.BurstMode, FluidTone.Neutral),
        CollectionEntry("utility/recently_deleted", "Eliminati di recente", "Telefono e NAS", Icons.Rounded.Delete, FluidTone.Warning),
        CollectionEntry("backup/free-space", "Libera spazio", "Già al sicuro sul NAS", Icons.Rounded.CleaningServices, FluidTone.Primary),
      ),
    ),
  )

  /** Tutte, nell'ordine in cui le registrerebbe `:app`: serve a vedere la scheda piena. */
  fun all(): List<FixedCollectionsSection> = listOf(albums(), smart(), trips(), people(), nas(), utility())

  // --- CU-9 (fase H): le sezioni COME IN PRODUZIONE --------------------------------------------------------------------------------------------------
  // Gli screenshot per l'utente devono mostrare cio' che c'e' nell'app. Qui le sezioni hanno le righe che scrivono i fornitori veri: l'Utility dal
  // costruttore di produzione (`utilityEntryGroup`, con "Libera spazio" di `:app`), le Cartelle NAS con le quattro voci e i testi di feature-nas, e la
  // sezione "Da tenere al sicuro" **vuota**, come la lega `:app` (`AppCollectionsSections.safe`): toglie quella della scheda, cosi' "Eliminati di
  // recente" compare una volta sola (nell'Utility). Persone, Ricordi, Viaggi e Luoghi con la forma delle righe dei loro moduli (che qui non si
  // vedono) e con le copertine, i volti e i luoghi che i test passano.

  /** I luoghi della Libreria di prova (id GeoNames di `FakePlaceData`), dal piu' fotografato: Firenze, Milano, Roma, Lisbona. */
  val PRODUCTION_PLACES: List<Pair<Int, String>> = listOf(3176959 to "Firenze", 3173435 to "Milano", 3169070 to "Roma", 2267057 to "Lisbona")

  /**
   * Le sezioni come le consegna `:app` in produzione. [photos] le foto per le copertine dei ricordi e dei viaggi (dalla Libreria visibile di prova),
   * [faces] i volti delle persone, [fresh] = la libreria appena installata (nessun ricordo, nessuna persona, nessun viaggio, nessuna posizione).
   */
  fun production(resources: Resources, photos: List<MediaId>, faces: List<FaceLocator>, fresh: Boolean = false): List<FixedCollectionsSection> {
    fun photo(n: Int): MediaId = photos[(n * 37) % photos.size]
    val trips = FixedCollectionsSection(
      CollectionSections.TRIPS,
      if (fresh) null else CollectionEntryGroup(
        title = "Viaggi",
        code = CollectionSections.TRIPS,
        entries = listOf(
          CollectionEntry("trip/t1", "Toscana, agosto 2025", "Viaggio · 640 elementi", Icons.Rounded.Flight, FluidTone.Info, cover = photo(3)),
          CollectionEntry("trip/t2", "Sabato 12 aprile 2025", "Evento · 96 elementi", Icons.Rounded.Event, FluidTone.Info, cover = photo(11)),
        ),
      ),
    )
    val people = if (fresh) {
      people(recognitionOn = false)
    } else {
      FixedCollectionsSection(
        CollectionSections.PEOPLE,
        CollectionEntryGroup(
          title = "Persone",
          code = CollectionSections.PEOPLE,
          entries = listOf(
            CollectionEntry("person/p1", "Giulia", "212 foto", Icons.Rounded.Person, FluidTone.Primary, faces = faces.take(1)),
            CollectionEntry("person/p2", "Marco", "148 foto", Icons.Rounded.Person, FluidTone.Primary, faces = faces.drop(1).take(1)),
            CollectionEntry("people/all", "Tutte le persone", "5 persone", Icons.Rounded.Group, FluidTone.Neutral, faces = faces.take(3)),
          ),
        ),
      )
    }
    val memories = FixedCollectionsSection(
      CollectionSections.MEMORIES,
      CollectionEntryGroup(
        title = "Ricordi",
        code = CollectionSections.MEMORIES,
        entries = if (fresh) {
          listOf(CollectionEntry("memories/empty", "Ancora nessun ricordo", "Arrivano quando ci sono abbastanza foto", Icons.Rounded.AutoAwesome, FluidTone.Neutral))
        } else {
          listOf(
            CollectionEntry("m_1", "Un anno fa", "18 foto · ottobre 2025", Icons.Rounded.History, FluidTone.Primary, trailing = "Nuovo", cover = photo(5)),
            CollectionEntry("m_2", "Estate al mare", "42 foto · agosto 2025", Icons.Rounded.Flight, FluidTone.Neutral, cover = photo(17)),
            CollectionEntry("m_3", "Il meglio di ottobre 2026", "60 foto", Icons.Rounded.CalendarMonth, FluidTone.Neutral, cover = photo(23)),
            CollectionEntry("memories/all", "Tutti i ricordi", "3 ricordi", Icons.Rounded.PhotoLibrary, FluidTone.Neutral),
          )
        },
      ),
    )
    val places = FixedCollectionsSection(
      CollectionSections.PLACES,
      CollectionEntryGroup(
        title = "Luoghi",
        code = CollectionSections.PLACES,
        entries = if (fresh) {
          listOf(CollectionEntry("map", "Mappa", "Nessuna foto con la posizione", Icons.Rounded.Map, FluidTone.Info))
        } else {
          listOf(CollectionEntry("map", "Mappa", "1.240 foto con la posizione", Icons.Rounded.Map, FluidTone.Info, coverPlaces = PRODUCTION_PLACES.take(4).map { it.first })) +
            PRODUCTION_PLACES.mapIndexed { i, (id, name) ->
              CollectionEntry("place_$id", name, listOf("320 foto", "88 foto", "61 foto", "24 foto")[i], Icons.Rounded.Place, FluidTone.Neutral, coverPlace = id)
            }
        },
      ),
    )
    val nas = FixedCollectionsSection(
      CollectionSections.NAS,
      CollectionEntryGroup(
        title = "Cartelle NAS",
        code = CollectionSections.NAS,
        entries = listOf(
          CollectionEntry("nas/folders", "Cartelle NAS", "Sfoglia, crea, rinomina, sposta ed elimina", Icons.Rounded.Folder, FluidTone.Primary),
          CollectionEntry("nas/space", "Spazio NAS", "Spazio libero, quota e file più grandi", Icons.Rounded.Storage, FluidTone.Info),
          CollectionEntry("nas/trash", "Eliminati di recente del NAS", "Il cestino del NAS: 30 giorni per ripensarci", Icons.Rounded.Delete, FluidTone.Danger),
          CollectionEntry("nas/operations", "Operazioni in corso", "Quello che sta facendo il NAS per te", Icons.Rounded.Sync, FluidTone.Success),
        ),
      ),
    )
    val freeSpace = CollectionEntry(
      "backup/free-space", "Libera spazio", "Già al sicuro sul NAS", Icons.Rounded.CleaningServices, FluidTone.Primary,
    )
    val utility = FixedCollectionsSection(
      CollectionSections.UTILITY,
      utilityEntryGroup(resources, mapOf(UtilityPage.EDITED to if (fresh) 0 else 37), canTrash = true, extra = listOf(freeSpace)),
    )
    // Come `AppCollectionsSections.safe`: la sezione c'e' ma senza voci, e toglie quella della scheda.
    val safe = FixedCollectionsSection(CollectionSections.SAFE, CollectionEntryGroup("", emptyList(), code = CollectionSections.SAFE))
    return listOf(albums(), smart(), trips, people, memories, places, nas, utility, safe)
  }
}
