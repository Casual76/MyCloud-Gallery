package dev.pampa.mycloudgallery.memories

import android.content.Context
import android.os.PowerManager
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.imaging.ImagingPipeline
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.notify.FileNotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.notify.FileNotifySettings
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRun
import dev.pampa.mycloudgallery.core.memories.notify.SeenTodayOpened
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryChoice
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.MemoryPhotoChoice
import dev.pampa.mycloudgallery.core.memories.plan.people.PeoplePlanner
import dev.pampa.mycloudgallery.core.memories.settings.less.AliasFollowingSettings
import dev.pampa.mycloudgallery.core.memories.settings.less.PeopleLessJanitor
import dev.pampa.mycloudgallery.core.memories.settings.less.PhaseGatedPeople
import dev.pampa.mycloudgallery.core.memories.shelf.FileShelfStore
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMemoriesSettings
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.notify.MemoryNotifier
import dev.pampa.mycloudgallery.utility.SearchShotVectors
import dev.pampa.mycloudgallery.work.MemoryNotifySlot
import dev.pampa.mycloudgallery.work.WorkManagerNotifyScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * COME SI MONTANO I RICORDI NEL PROCESSO (Fase 9, P13; docs/fase9-progetto.md §1.4; "per P13" di P6, P7, P8, P10). Le stesse righe le usano i test
 * d'integrazione (Robolectric), cambiando solo cio' che sta sotto [GalleryGraph].
 *
 * Questo file **nomina** [VisibleMedia] e [HiddenMask] (la visibilita' del motore e il filtro finale della scelta delle foto): sta nell'elenco `wiring` del
 * registro `HiddenSurfaces`. Le superfici (sezione, pagina, "Tutti i ricordi", presentazione, persone, notifica) sono le righe `MEMORIES` e `NOTIFICATIONS`
 * di quel registro, provate sul motore vero.
 */
object MemoriesWiring {

  /** I file dei ricordi in `noBackupFilesDir` (scaffale, registro e preferenze delle notifiche, chi e' in tendina, musica, ritardi): l'azzeramento li toglie. */
  const val DIR: String = "memories"

  /**
   * La visibilita' dei media per il motore (P6, `MemoryVisibility`): l'istantanea **senza nascosti** di [visible], le due letture che **lanciano** (un guasto
   * non e' una Libreria vuota, R17). L'unico punto di :app dove il motore incontra i contratti dei Nascosti.
   */
  fun visibility(visible: VisibleMedia, mask: HiddenMask): MemoryVisibility = object : MemoryVisibility {
    override val snapshot: StateFlow<TimelineSnapshot?> get() = visible.snapshot

    override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = visible.visibleOrThrow(ids)

    override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = mask.hiddenAmongOrThrow(ids)

    override fun toString(): String = "AppMemoryVisibility"
  }

  /** Il passo 5 per ricordo (P6, P7): i segnali una volta per ricordo, poi la scelta con il filtro finale dei Nascosti attorno ([VisiblePhotoPicker]). */
  fun choice(loader: PickSignalsLoader, mask: HiddenMask): MemoryChoice = MemoryPhotoChoice(loader, VisiblePhotoPicker(MemoryPhotoPicker(), mask))

  @OptIn(ExperimentalCoroutinesApi::class)
  fun build(
    context: Context,
    scope: CoroutineScope,
    gallery: GalleryGraph,
    albums: AlbumsGraph,
    faces: FacesGraph,
    /** La ricerca per persona (il ponte `PeopleBridge`): le celle **visibili** di una persona. */
    lookup: PeopleLookup,
    vectors: SearchShotVectors,
    today: Today,
    /** La cartella dei ritagli di adesso (`CutoutFence.folder`). */
    cutoutsFolder: () -> String,
    slot: MemoryNotifySlot,
    afterFirstDraw: suspend () -> Unit,
  ): MemoriesGraph {
    val app = context.applicationContext
    val dir = File(app.noBackupFilesDir, DIR)
    val visibility = visibility(gallery.visibleMedia, gallery.hiddenMask)
    val clock = AppMemoryClock(today)
    val counters = MemoriesCounters(LateDeliveries(File(dir, LateDeliveries.FILE), clock::today))
    val nas = gallery.nas
    // I ricordi stanno nei dati personali (D10, D11): si montano con la galleria partita e i dati personali agganciati, come gli album.
    val ready: StateFlow<Boolean> = if (nas == null) {
      MutableStateFlow(false)
    } else {
      combine(gallery.started, nas.organizer.partsState) { started, parts -> started && parts != null }.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val thumbs = LocalThumbs(ImagingPipeline.thumbnailSource(app), catalogPhoneRefs { gallery.coreIfReady()?.catalog })
    val power = app.getSystemService(PowerManager::class.java)
    val graphRef = arrayOfNulls<MemoriesGraph>(1)
    val notifier = lazy {
      MemoryNotifier(app, graphRef[0]!!.memories, visibility, dir, places = gallery.places::labelOfId, thumbnails = LocalNotifyThumbnails(thumbs), events = counters)
    }
    val notifySettings = lazy { FileNotifySettings(dir) }
    val peopleChanged: Flow<Unit> = faces.live.flatMapLatest { live -> live?.people?.list ?: flowOf(null) }.drop(1).map { }

    val build: suspend (CoroutineScope) -> MemoriesLive? = build@{ mountScope ->
      val parts = nas?.organizer?.parts ?: return@build null
      val switch = FacesPeopleSwitch(faces, mountScope)
      val people = PhaseGatedPeople(
        IndexMemoryPeople({ faces.live.value?.people }, lookup) { faces.live.value?.people?.list?.value to visibility.snapshot.value?.seq },
        switch,
        mountScope,
      )
      // P8: le scelte "Mostra meno" e "Mai" seguono gli alias delle persone; il motore e il janitor vedono le stesse scelte.
      val personal = AliasFollowingSettings(SyncedMemoriesSettings(parts.personalStore, mountScope), people)
      val cutouts = CatalogMemoryCutouts(gallery.database, cutoutsFolder)
      val loader = PickSignalsLoader(
        vectors = SearchMemoryVectors(vectors),
        pixels = LocalMemoryPixels(thumbs, LocalMemoryPixels.powerSaveOf(power)),
        people = people,
      )
      val choice = choice(loader, gallery.hiddenMask)
      val trips = AlbumsMemoryTrips(albums)
      val engine = MemoryEngine(
        library = visibility,
        personal = personal,
        store = FileShelfStore(dir),
        clock = clock,
        scope = mountScope,
        planner = IndexedPlanner(people = PeoplePlanner()),
        trips = trips,
        people = people,
        cutouts = cutouts,
        choice = choice,
      )
      val janitor = PeopleLessJanitor(personal, people, switch, engine)
      val bell = notifier.value
      val run = MemoryNotifyRun(
        MemoryNotifyPolicy(), notifySettings.value, FileNotifyLedgerStore(dir), engine, visibility, SeenTodayOpened(personal), bell, bell, clock,
        WorkManagerNotifyScheduler(app), counters,
      )
      MemoriesLive(mountScope, engine, personal, people, janitor, run, trips, cutouts, choice)
    }
    return MemoriesGraph(
      parent = scope,
      ready = ready,
      canMount = nas != null,
      build = build,
      visibility = visibility,
      notifier = notifier,
      notifySettings = notifySettings,
      musicSettings = lazy { FileMusicSettings(dir) },
      slot = slot,
      afterFirstDraw = afterFirstDraw,
      counters = counters,
      peopleChanged = peopleChanged,
      localThumbnail = { id, px -> thumbs.bitmap(id, px) },
      placeLabel = gallery.places::labelOfId,
      today = clock::today,
    ).also { graphRef[0] = it }
  }
}
