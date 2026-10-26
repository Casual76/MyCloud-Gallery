package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La proiezione degli album personali sul catalogo vero (D5, E-I18; Room in memoria, Robolectric): dallo stato dei dati personali alle
 * tabelle v6, per id e per impronta, con gli elementi "in attesa" fuori dalle righe, l'unione d'identita' che converge senza perdere ne'
 * album ne' ordine ne' copertina, e mai una riga di un album condiviso toccata. La ricostruzione identica da zero e' in
 * [AlbumProjectionPropertyTest].
 */
@RunWith(AndroidJUnit4::class)
class CatalogAlbumProjectionTest {

  private val room = AlbumsRoom()
  private var clock = T0

  // L'ora del NAS avanza a ogni modifica: la voce scritta dopo e' piu' recente (a pari ora l'ordine lo deciderebbe l'id).
  private val store = room.store(nasNow = { clock += 10; clock })
  private val albums = PersonalAlbums(store)
  private val projection = CatalogAlbumProjection(room.catalog, room.db)
  private var nonce = 0

  @After
  fun close() = room.close()

  private fun newAlbumId(): String = AlbumId.derived(AlbumsRoom.ME, ByteArray(16) { (nonce + it).toByte() }.also { nonce++ }).value

  private suspend fun project(only: Set<String>? = null) = projection.project(store.effective(), only)

  private suspend fun album(title: String = "Gita"): String = newAlbumId().also { albums.define(it, title) }

  private fun fpText(n: Int): String = AlbumDerivation.fpText(room.fingerprintOf(n))!!

  /** Una voce di un altro dispositivo dello stesso account: id suoi (che qui non ci sono) e l'impronta del media. */
  private fun remote(album: String, field: String, at: Long, value: PersonalValue, fp: PersonalFingerprint? = null, author: String = OTHER) =
    PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, field), at, author, value, fp)

  private fun unknown(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  @Test
  fun unAlbumDeiDatiPersonaliDiventaRigheConPosizioneEImpronta() = runBlocking<Unit> {
    val (a, b, c) = Triple(room.record(1), room.record(2), room.record(3))
    val id = album()
    albums.addItem(id, a.value, 1_000)
    albums.addItem(id, b.value, 2_000)
    albums.addItem(id, c.value, 3_000)
    albums.setCover(id, b.value)

    val report = project()

    assertThat(report.albums).isEqualTo(1)
    assertThat(report.items).isEqualTo(3)
    assertThat(report.pending).isEqualTo(0)
    val row = room.db.albumDao().get(id)!!
    assertThat(row.title).isEqualTo("Gita")
    assertThat(row.kind).isEqualTo("manual")
    assertThat(row.sort).isEqualTo("newest")
    assertThat(row.state).isNull()
    assertThat(row.coverMediaId).isEqualTo(b.value)
    val items = room.db.albumDao().items(id)
    assertThat(items.map { it.mediaId to it.position }).containsExactly(a.value to 1_000L, b.value to 2_000L, c.value to 3_000L).inOrder()
    assertThat(items.map { it.fp }).containsExactly(fpText(1), fpText(2), fpText(3)).inOrder()
    assertThat(items.map { it.origin }.toSet()).containsExactly("manual")
    // Il conteggio che la Libreria legge e' quello delle righe mostrabili.
    assertThat(room.db.albumDao().summaries().single().visibleCount).isEqualTo(3)
  }

  @Test
  fun unElementoDiUnAltroDispositivoSiRitrovaPerImprontaConUnIdSconosciuto() = runBlocking<Unit> {
    val b = room.record(2)
    val id = newAlbumId()
    store.applyRemote(
      listOf(
        remote(id, PersonalFields.ALBUM_DEFINITION, 10, PersonalValue.Text("Dal tablet")),
        remote(id, PersonalFields.albumItem(unknown(7).value), 11, PersonalValue.Number(1_500), room.fingerprintOf(2)),
        remote(id, PersonalFields.ALBUM_COVER, 12, PersonalValue.Text(unknown(7).value), room.fingerprintOf(2)),
      ),
    )

    project()

    assertThat(room.db.albumDao().items(id).map { it.mediaId to it.position }).containsExactly(b.value to 1_500L)
    assertThat(room.db.albumDao().get(id)!!.coverMediaId).isEqualTo(b.value)
  }

  @Test
  fun unaImprontaCheIndicaPiuMediaNonRisolveNienteEL_ElementoRestaInAttesa() = runBlocking<Unit> {
    // Due media con la stessa impronta rapida (L32): non si confondono mai, nemmeno per mettere una foto in un album.
    room.insertRaw(unknown(1), 77, "ab".repeat(32), T0, 20_260_101)
    room.insertRaw(unknown(2), 77, "ab".repeat(32), T0, 20_260_101)
    val id = newAlbumId()
    store.applyRemote(
      listOf(
        remote(id, PersonalFields.ALBUM_DEFINITION, 10, PersonalValue.Text("Ambiguo")),
        remote(id, PersonalFields.albumItem(unknown(9).value), 11, PersonalValue.Number(1_000), PersonalFingerprint(77, QuickHash("ab".repeat(32)))),
      ),
    )

    val report = project()

    assertThat(room.db.albumDao().items(id)).isEmpty()
    assertThat(report.pending).isEqualTo(1)
    assertThat(projection.pendingOf(id)).isEqualTo(1)
  }

  @Test
  fun unElementoInAttesaTornaQuandoIlSuoMediaArriva() = runBlocking<Unit> {
    val id = newAlbumId()
    store.applyRemote(
      listOf(
        remote(id, PersonalFields.ALBUM_DEFINITION, 10, PersonalValue.Text("Dopo")),
        remote(id, PersonalFields.albumItem(unknown(3).value), 11, PersonalValue.Number(1_000), room.fingerprintOf(9)),
      ),
    )
    assertThat(project().pending).isEqualTo(1)
    assertThat(room.db.albumDao().summaries()).isEmpty()

    val arrived = room.record(9)
    // Una scansione con novita': la proiezione dice quali album riguarda (quelli con elementi in attesa).
    val affected = projection.affectedBy(listOf(arrived))
    assertThat(affected).containsExactly(id)
    val report = project(affected)

    assertThat(report.pending).isEqualTo(0)
    assertThat(room.db.albumDao().items(id).map { it.mediaId }).containsExactly(arrived.value)
    assertThat(projection.pending.value).isEqualTo(0)
  }

  @Test
  fun cancellareUnAlbumTogliLeRigheERicrearloLeRitrova() = runBlocking<Unit> {
    val a = room.record(1)
    val id = album("Prima")
    albums.addItem(id, a.value, 1_000)
    project()
    assertThat(room.db.albumDao().items(id)).hasSize(1)

    albums.delete(id)
    project()
    assertThat(room.db.albumDao().get(id)).isNull()
    assertThat(room.db.albumDao().items(id)).isEmpty()
    // Gli elementi restano nello stato: lo stesso id ricreato li ritrova.
    albums.define(id, "Dopo")
    project()
    assertThat(room.db.albumDao().get(id)!!.title).isEqualTo("Dopo")
    assertThat(room.db.albumDao().items(id).map { it.mediaId }).containsExactly(a.value)
  }

  @Test
  fun unAlbumSenzaPiuVociPerdeLeRigheEUnoSenzaDefinizioneNonNasce() = runBlocking<Unit> {
    val a = room.record(1)
    val orphan = newAlbumId()
    albums.addItem(orphan, a.value, 1_000)
    val gone = album("Sparito")
    project()
    assertThat(room.db.albumDao().get(orphan)).isNull()
    // Le righe di un album personale che lo stato non ha piu' si tolgono alla ricostruzione.
    room.db.albumDao().insert(AlbumEntity(newAlbumId(), "Vecchio", null, T0, T0))
    assertThat(room.db.albumDao().all().filter { it.state == null }).hasSize(2)
    val report = projection.rebuild(store.effective())
    assertThat(report.removed).isAtLeast(1)
    assertThat(room.db.albumDao().all().map { it.albumId }).containsExactly(gone)
  }

  @Test
  fun unAlbumCondivisoNonSiTocca_NeConLoStessoIdNeSenzaVoci() = runBlocking<Unit> {
    val a = room.record(1)
    val sameId = newAlbumId()
    val onlyShared = newAlbumId()
    room.db.albumDao().insert(AlbumEntity(sameId, "Condiviso", null, T0, T0, state = "active"))
    room.db.albumDao().insert(AlbumEntity(onlyShared, "Altro condiviso", null, T0, T0, state = "active"))
    room.db.albumDao().upsertItem(AlbumItemEntity(onlyShared, a.value, 1_000, T0, addedBy = "marco"))
    albums.define(sameId, "Personale con lo stesso id")
    albums.addItem(sameId, a.value, 5_000)

    project()
    projection.rebuild(store.effective())

    val shared = room.db.albumDao().get(sameId)!!
    assertThat(shared.title).isEqualTo("Condiviso")
    assertThat(shared.state).isEqualTo("active")
    assertThat(room.db.albumDao().items(sameId)).isEmpty()
    assertThat(room.db.albumDao().get(onlyShared)!!.title).isEqualTo("Altro condiviso")
    assertThat(room.db.albumDao().items(onlyShared).single().addedBy).isEqualTo("marco")
  }

  @Test
  fun unGiroIdenticoNonCambiaNiente() = runBlocking<Unit> {
    val (a, b) = room.record(1) to room.record(2)
    val id = album()
    albums.addItem(id, a.value, 1_000)
    albums.addItem(id, b.value, 2_000)
    albums.setCover(id, a.value)
    project()
    val first = dump(room)
    project()
    projection.rebuild(store.effective())
    assertThat(dump(room)).isEqualTo(first)
  }

  // --- Album smart ------------------------------------------------------------------------------------------------------------

  @Test
  fun leRigheDelMotoreDiUnoSmartRestanoSalvoQuelleCheLoStatoVieta() = runBlocking<Unit> {
    val ms = (1..4).map { room.record(it) }
    val id = newAlbumId()
    albums.setKind(id, AlbumKind.SMART)
    albums.setSmart(id, PersonalFields.SMART_TEXT, "neve")
    albums.define(id, "Neve")
    project()
    // Il motore (P6) scrive le sue righe `auto` e i suggeriti.
    room.db.albumDao().upsertItems(ms.take(3).map { AlbumItemEntity(id, it.value, 0, T0, origin = "auto") })
    room.db.albumChoiceDao().upsertSuggestions(listOf(AlbumSuggestionEntity(id, ms[3].value, "s1", T0)))
    project()
    assertThat(room.db.albumDao().items(id).map { it.origin }.toSet()).containsExactly("auto")
    assertThat(room.db.albumDao().items(id)).hasSize(3)
    assertThat(room.db.albumRuleDao().rules(id).map { it.rule to it.value }).containsExactly("text" to "neve")

    // Un'esclusione toglie la riga automatica e resta per sempre.
    albums.exclude(id, ms[0].value)
    project()
    assertThat(room.db.albumDao().items(id).map { it.mediaId }).containsExactly(ms[1].value, ms[2].value)
    assertThat(room.db.albumChoiceDao().exclusions(id).map { it.mediaId }).containsExactly(ms[0].value)

    // Un suggerito accettato entra e non e' piu' un suggerito.
    albums.accept(id, ms[3].value)
    project()
    assertThat(room.db.albumDao().items(id).single { it.mediaId == ms[3].value }.origin).isEqualTo("accepted")
    assertThat(room.db.albumChoiceDao().suggestions(id)).isEmpty()

    // Congelato: le regole non agiscono, le righe automatiche se ne vanno (restano gli elementi a mano).
    albums.addItem(id, ms[1].value, 1_000)
    albums.setFrozen(id, true)
    project()
    assertThat(room.db.albumDao().get(id)!!.frozen).isTrue()
    assertThat(room.db.albumDao().items(id).map { it.mediaId to it.origin }).containsExactly(ms[1].value to "manual", ms[3].value to "accepted")
  }

  // --- Unione d'identita' (E-I18) ---------------------------------------------------------------------------------------------

  @Test
  fun dueIdPerLoStessoFileConvergonoSenzaPerdereAlbumOrdineNeCopertina() = runBlocking<Unit> {
    val (a, b) = room.twins(40)
    val c = room.record(3)
    val survivor = minOf(a, b)
    val id = album("Oslo")
    // Due dispositivi hanno messo lo stesso file con id diversi (e posizioni diverse); la copertina e' sull'id del secondo.
    albums.addItem(id, a.value, 1_000)
    albums.addItem(id, b.value, 2_500)
    albums.addItem(id, c.value, 3_000)
    albums.setCover(id, b.value)
    project()
    assertThat(room.db.albumDao().items(id)).hasSize(3)

    assertThat(room.catalog.merge(a, b)).isEqualTo(MediaCatalog.MergeOutcome.Merged(survivor, maxOf(a, b)))
    val affected = projection.affectedBy(listOf(a, b))
    assertThat(affected).containsExactly(id)
    project(affected)

    // Un elemento solo per quel file; il posto e' quello della voce piu' recente (b, scritta dopo); gli altri non si sono mossi.
    assertThat(room.db.albumDao().items(id).map { it.mediaId to it.position }).containsExactly(survivor.value to 2_500L, c.value to 3_000L).inOrder()
    assertThat(room.db.albumDao().get(id)!!.coverMediaId).isEqualTo(survivor.value)
    // Non e' cambiato nulla nello stato: l'album e' lo stesso e il prossimo giro da' lo stesso risultato.
    val before = dump(room)
    projection.rebuild(store.effective())
    assertThat(dump(room)).isEqualTo(before)
  }

  @Test
  fun unaRimozioneFattaConUnIdVecchioToglieLElementoAncheDopoLUnione() = runBlocking<Unit> {
    val (a, b) = room.twins(41)
    val id = album("Oslo")
    albums.addItem(id, b.value, 2_000)
    albums.addItem(id, a.value, 1_000)
    project()
    room.catalog.merge(a, b)
    // L'utente toglie il file (da un dispositivo che lo chiama con l'altro id): la lapide piu' recente vale per entrambe le voci.
    albums.removeItem(id, a.value)
    albums.removeItem(id, b.value)
    project(projection.affectedBy(listOf(a, b)))
    assertThat(room.db.albumDao().items(id)).isEmpty()
  }

  /** Il media cambia versione (una modifica): l'id e' lo stesso, l'elemento resta; l'impronta di prima si ritrova ancora su un altro dispositivo. */
  @Test
  fun unaNuovaVersioneDelMediaNonStaccaLElemento() = runBlocking<Unit> {
    val a = room.record(1)
    val id = album()
    albums.addItem(id, a.value, 1_000)
    albums.setCover(id, a.value)
    project()
    val parent = room.catalog.media(a)!!.currentVersionId
    val edited = room.catalog.addEditedVersion(a, parent, Sha256("%064x".format(999_001)), QuickHash("%064x".format(999_002)), 4_321)
    assertThat(edited).isNotNull()

    project()
    assertThat(room.db.albumDao().items(id).map { it.mediaId }).containsExactly(a.value)
    assertThat(room.db.albumDao().get(id)!!.coverMediaId).isEqualTo(a.value)

    // L'altro dispositivo ha scritto la voce con l'impronta della versione di prima e un id che qui non c'e': la versione vecchia c'e' ancora.
    val other = newAlbumId()
    store.applyRemote(
      listOf(
        remote(other, PersonalFields.ALBUM_DEFINITION, 10, PersonalValue.Text("Dal tablet")),
        remote(other, PersonalFields.albumItem(unknown(5).value), 11, PersonalValue.Number(1_000), room.fingerprintOf(1)),
        remote(other, PersonalFields.albumItem(unknown(6).value), 12, PersonalValue.Number(2_000), PersonalFingerprint(4_321, QuickHash("%064x".format(999_002)))),
      ),
    )
    project()
    assertThat(room.db.albumDao().items(other).map { it.mediaId }).containsExactly(a.value)
  }

  // --- Volumi ----------------------------------------------------------------------------------------------------------------

  @Test
  fun dueMilaElementiSiProiettanoInUnaTransazionePerIdEPerImpronta() = runBlocking<Unit> {
    val total = 2_000
    for (i in 0 until total) room.insertRaw(unknown(10_000 + i), 5_000L + i, "%064x".format(i + 1), T0 + i, 20_260_101 + i % 28)
    val id = newAlbumId()
    albums.define(id, "Mille e mille")
    for (i in 0 until total) {
      val media = unknown(10_000 + i)
      if (i % 2 == 0) {
        albums.addItem(id, media.value, (i + 1) * 1_000L, PersonalFingerprint(5_000L + i, QuickHash("%064x".format(i + 1))))
      } else {
        // L'altro dispositivo ha un altro id per lo stesso file: si ritrova per impronta.
        albums.addItem(id, unknown(90_000 + i).value, (i + 1) * 1_000L, PersonalFingerprint(5_000L + i, QuickHash("%064x".format(i + 1))))
      }
    }
    val started = System.nanoTime()
    val report = project()
    val millis = (System.nanoTime() - started) / 1_000_000
    assertThat(report.items).isEqualTo(total)
    assertThat(report.pending).isEqualTo(0)
    assertThat(room.db.albumDao().items(id)).hasSize(total)
    assertThat(room.db.albumDao().summaries().single().visibleCount).isEqualTo(total)
    assertThat(millis).isLessThan(30_000)
  }

  private companion object {
    val OTHER: String = TrustIds.random()
  }
}

/** Lo stato delle tabelle derivate degli album personali, come testo e in un ordine fisso: per confrontare due proiezioni. */
suspend fun dump(room: AlbumsRoom): List<String> = buildList {
  for (album in room.db.albumDao().all().filter { it.state == null }.sortedBy { it.albumId }) {
    add("album ${album.albumId} title=${album.title} cover=${album.coverMediaId} created=${album.createdAt} updated=${album.updatedAt} kind=${album.kind} sort=${album.sort} frozen=${album.frozen} shared=${album.sharedId}")
    room.db.albumDao().items(album.albumId).forEach { add("  item ${it.mediaId} pos=${it.position} at=${it.addedAt} origin=${it.origin} fp=${it.fp} by=${it.addedBy}") }
    room.db.albumRuleDao().rules(album.albumId).forEach { add("  rule ${it.rule}/${it.position} value=${it.value} media=${it.mediaId}") }
    room.db.albumChoiceDao().exclusions(album.albumId).forEach { add("  excl ${it.mediaId} fp=${it.fp}") }
  }
}
