package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumCopies
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.personal.CatalogAlbums
import dev.pampa.mycloudgallery.core.albums.personal.PersonalAlbumCopies
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LocalFlag
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.integration.closeScope
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 7 (P9, D22; integrazione 26/10): lo STACK VERO degli album personali (P3: `CatalogAlbums`, la proiezione sulle tabelle v6, la lettura, le copie di
// Q5 e gli elementi in attesa) sopra il catalogo VERO del mondo casuale, con lo store vero dei dati personali (`LedgerPersonalStore`) e la visibilita' vera
// (`CatalogVisibleMedia`, `CatalogHiddenMask`). Gli album si scrivono come li scriverebbe un altro dispositivo dello stesso account (voci personali grezze): con
// elementi che qui sono nascosti, una copertina scelta altrove e poi nascosta qui (E-H6), id che questo catalogo non conosce e si ritrovano per impronta.

/** La proiezione dei dati personali sul mondo: solo l'impronta dei media (come `RoomFingerprints` dei test di `core-albums`); i preferiti e i nascosti non si toccano. */
internal class WorldFingerprints(private val w: BuiltWorld) : PersonalProjection {
  override suspend fun apply(entry: PersonalEntry): ProjectionResult = ProjectionResult.NOT_PROJECTED

  override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

  override suspend fun localFlags(): List<LocalFlag> = emptyList()

  override suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? {
    val id = runCatching { MediaId.parse(mediaId) }.getOrNull() ?: return null
    val media = w.catalog.resolve(id) ?: return null
    val current = w.db.mediaDao().get(media.value)?.currentVersionId ?: return null
    val version = w.db.versionDao().forMedia(media.value).firstOrNull { it.versionId == current } ?: return null
    return PersonalFingerprint(version.size, QuickHash(version.quickHash))
  }

  override suspend fun mediaWithFingerprint(fingerprint: PersonalFingerprint): MediaId? =
    w.db.versionDao().byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .mapNotNull { w.catalog.resolve(MediaId(it.mediaId)) }.distinct().singleOrNull()
}

internal class RealPersonalStack private constructor(val w: BuiltWorld) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val nonces = AtomicInteger()
  private val me = AccountId.of(HiddenWorldBuilder.ME)

  val store = LedgerPersonalStore(TrustIds.random(), { T0 }, InMemoryPersonalLedger(), WorldFingerprints(w))
  val visibility = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
  val mask = CatalogHiddenMask(w.db)
  val family = FakeAlbumFamily(account = me)
  val albums = CatalogAlbums(
    store, w.catalog, w.db, visibility, mask, family, scope,
    newNonce = { ByteArray(AlbumId.NONCE_BYTES) { (it * 5 + nonces.incrementAndGet() + w.spec.seed.toInt()).toByte() } },
  )
  private val raw = PersonalAlbums(store)

  /** Un sottoinsieme riproducibile del mondo ([salt] lo cambia): i membri di un album, nascosti compresi. */
  fun pick(salt: Int, share: Int): List<Int> = w.spec.media.indices.filter { (it * 7 + salt + w.spec.seed.toInt()).mod(share) == 0 }

  /** L'album manuale con la copertina scelta altrove (E-H6): i suoi membri e la copertina scelta (il primo). */
  var manual: AlbumId = NO_ALBUM
  var manualMembers: List<Int> = emptyList()
  val chosenCover: Int? get() = manualMembers.firstOrNull()

  /** Lo smart: gli accettati, le righe automatiche del motore (P6, scritte a mano) e i suggeriti, tutti anche con nascosti dentro. */
  var smart: AlbumId = NO_ALBUM
  var smartAccepted: List<Int> = emptyList()
  var smartAuto: List<Int> = emptyList()
  var smartSuggested: List<Int> = emptyList()

  /** La copia personale di un album condiviso chiuso (Q5): gli elementi dati a `PersonalAlbumCopies.keep`, e se l'album esiste. */
  var copy: AlbumId = NO_ALBUM
  var copyMembers: List<Int> = emptyList()
  lateinit var copyResult: PersonalAlbumCopies.Result
  lateinit var copyAgain: PersonalAlbumCopies.Result

  /** L'album con elementi "in attesa": ritrovati per impronta, mai arrivati, e arrivati dopo (uno visibile e uno nascosto appena arrivato). */
  var pending: AlbumId = NO_ALBUM
  var byFingerprint: List<Int> = emptyList()
  var neverArrive: Int = 0
  var arrivedVisible: MediaId = NO_MEDIA
  var arrivedHidden: MediaId = NO_MEDIA

  private fun foreign(index: Int): MediaId = MediaId(UUID.nameUUIDFromBytes("estraneo-${w.spec.seed}-$index".toByteArray()).toString())

  private suspend fun created(outcome: CreateOutcome): AlbumId = (outcome as CreateOutcome.Created).id

  private suspend fun populate() {
    // --- Manuale, con la copertina scelta da un altro dispositivo ---
    manual = created(albums.create("Manuale", AlbumKind.MANUAL, null))
    manualMembers = pick(salt = 1, share = 3)
    albums.add(manual, manualMembers.map { w.ids[it] })
    chosenCover?.let { raw.setCover(manual.value, w.ids[it].value) }

    // --- Smart: accettati, righe automatiche del motore e suggeriti ---
    smart = created(albums.create("Smart", AlbumKind.SMART, SmartRules(period = null, place = null, text = null)))
    smartAccepted = pick(salt = 2, share = 4)
    albums.accept(smart, smartAccepted.map { w.ids[it] })
    smartAuto = pick(salt = 3, share = 5).filter { it !in smartAccepted }
    smartSuggested = pick(salt = 5, share = 4).filter { it !in smartAccepted && it !in smartAuto }
    w.db.albumDao().upsertItems(smartAuto.mapIndexed { n, i -> AlbumItemEntity(smart.value, w.ids[i].value, (n + 1) * 1_000L, T0, origin = "auto") })
    w.db.albumChoiceDao().upsertSuggestions(smartSuggested.map { AlbumSuggestionEntity(smart.value, w.ids[it].value, "s1", T0) })
    // Le righe del motore le scrive questo stack a mano: come farebbe `SmartUpkeep`, dice che il calcolo e' finito (revisione di fine Fase 7, AS-2: uno smart appena creato
    // e' "in calcolo" finche' il motore non ha finito; senza questa riga uno smart senza foto visibili resterebbe "in calcolo" e la pagina direbbe "cerco").
    albums.smartWorkFinished(listOf(smart))

    // --- La copia personale di un album chiuso (Q5): tutti i media dell'area comune, nascosti compresi (la lettura li toglie) ---
    val sharedId = AlbumId.derived(AccountId.of("test2"), ByteArray(AlbumId.NONCE_BYTES) { (it + w.spec.seed.toInt()).toByte() })
    copy = SharedAlbumCopies.copyIdFor(sharedId, me)
    copyMembers = w.spec.media.filter { it.where == Where.NAS_COMMON }.map { it.index }
    val keeper = PersonalAlbumCopies(store, w.catalog)
    copyResult = keeper.keep(copy, sharedId, "Copia", copyMembers.map { w.ids[it] })
    copyAgain = keeper.keep(copy, sharedId, "Copia", copyMembers.map { w.ids[it] })

    // --- Elementi in attesa: id che questo catalogo non conosce (un altro dispositivo li ha chiamati cosi') ---
    pending = created(albums.create("In attesa", AlbumKind.MANUAL, null))
    val fingerprints = WorldFingerprints(w)
    byFingerprint = pick(salt = 4, share = 5)
    var position = 0L
    for (index in byFingerprint) raw.addItem(pending.value, foreign(index).value, (++position) * 1_000L, fingerprints.fingerprintOfMedia(w.ids[index].value))
    neverArrive = 3
    repeat(neverArrive) { n -> raw.addItem(pending.value, foreign(1_000 + n).value, (++position) * 1_000L, PersonalFingerprint(4_096L + n, QuickHash("%064x".format(900 + n)))) }
    val seeds = listOf(arrivalBytes(1), arrivalBytes(2))
    for ((n, bytes) in seeds.withIndex()) {
      val fingerprint = Fingerprint.ofContent(bytes)
      raw.addItem(pending.value, foreign(2_000 + n).value, (++position) * 1_000L, PersonalFingerprint(fingerprint.size, fingerprint.quickHash))
    }
    albums.reconcileAll()
    // I due media arrivano adesso (una scansione): uno visibile, uno che arriva e viene subito nascosto.
    arrivedVisible = arrive(seeds[0], 1)
    arrivedHidden = arrive(seeds[1], 2)
    w.catalog.setPersonalFlags(listOf(arrivedHidden)) { it.copy(hidden = true) }
    albums.onCatalogChanged(listOf(arrivedVisible, arrivedHidden))
    albums.reconcileAll()
  }

  private fun arrivalBytes(n: Int): ByteArray = Random(w.spec.seed * 77 + n).nextBytes(1_024)

  private suspend fun arrive(bytes: ByteArray, n: Int): MediaId {
    val fingerprint = Fingerprint.ofContent(bytes)
    val where = Location.Phone("external_primary", "DCIM/Camera/", "IMG_arrivato_${w.spec.seed}_$n.jpg", 90_000L + n, "store-v1")
    val observation = Observation(where, FileStamp(fingerprint.size, T0 + n), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, T0)
    return checkNotNull(w.catalog.record(observation, MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId) { "il media arrivato non e' entrato" }
  }

  /** Il lavoro si chiude e si aspetta: una lettura di Room ancora in volo non deve finire sul database che il mondo chiude subito dopo. */
  override fun close() {
    closeScope(scope)
  }

  companion object {
    private const val T0: Long = 1_780_000_000_000L
    private val NO_ALBUM = AlbumId("00000000-0000-4000-8000-000000000000")
    private val NO_MEDIA = MediaId("00000000-0000-4000-8000-000000000001")

    /** Lo stack sopra il mondo [w], con gli album seminati e tutte le tabelle e l'elenco gia' ricalcolati. */
    suspend fun build(w: BuiltWorld): RealPersonalStack = RealPersonalStack(w).also { it.populate() }
  }
}
