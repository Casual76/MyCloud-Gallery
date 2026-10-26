package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli album personali veri, piu' dispositivi dello stesso account (D3-D5, D21; E-I18; "Fatto quando" di P3): ognuno con il suo catalogo e i suoi id
 * per gli stessi file. Aggiunte insieme che si fondono, elementi ritrovati per impronta, ordine che resta quello scelto con gli inserimenti in
 * mezzo, un media che sparisce e torna, regole degli smart che si rileggono uguali.
 */
@RunWith(AndroidJUnit4::class)
class CatalogAlbumsTest {

  private var tick = T0
  private val clock: () -> Long = { tick += 10; tick }
  private val devices = ArrayList<FamilyDevice>()

  private fun device(name: String, idBase: Int): FamilyDevice = FamilyDevice(name, idBase, clock).also { devices += it }

  @After
  fun close() = devices.forEach { it.close() }

  private suspend fun FamilyDevice.createAlbum(title: String = "Gita"): AlbumId =
    (albums.create(title, AlbumKind.MANUAL, null) as CreateOutcome.Created).id

  private suspend fun FamilyDevice.rows(id: AlbumId) = room.db.albumDao().items(id.value)

  /** L'ordine dell'album com'e' scelto, in un modo che non dipende dagli id dei media del dispositivo (le impronte). */
  private suspend fun FamilyDevice.orderedFingerprints(id: AlbumId): List<String?> =
    rows(id).sortedWith(compareBy({ it.position }, { it.fp.orEmpty() }, { it.mediaId })).map { it.fp }

  private fun fileFp(n: Int) = "${"%064x".format(n + 1)}:${5_000L + n}"

  // --- Due dispositivi aggiungono insieme 1.000 elementi diversi: tutti presenti ---------------------------------------------

  @Test
  fun dueDispositiviCheAggiungonoInsiemeMilleElementiDiversiLiHannoTuttiMaiUnoInMeno() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    phone.learnAll(0 until 2_000)
    tablet.learnAll(0 until 2_000)
    val album = phone.createAlbum("Mille e mille")
    exchange(phone, tablet)
    tablet.albums.reconcileAll()

    // Insieme: nessuno dei due ha ancora visto cio' che ha aggiunto l'altro (anche le posizioni coincidono).
    assertThat(phone.albums.add(album, (0 until 1_000).map { phone.idOf(it) })).isEqualTo(AddOutcome.Added(1_000, 0))
    assertThat(tablet.albums.add(album, (1_000 until 2_000).map { tablet.idOf(it) })).isEqualTo(AddOutcome.Added(1_000, 0))
    assertThat(phone.rows(album)).hasSize(1_000)
    assertThat(tablet.rows(album)).hasSize(1_000)

    exchange(phone, tablet)
    val started = System.nanoTime()
    phone.albums.reconcileAll()
    tablet.albums.reconcileAll()
    println("P3 misura: 2.000 elementi arrivati dall'altro dispositivo, ricostruzione su due dispositivi in ${(System.nanoTime() - started) / 1_000_000} ms")

    for (device in listOf(phone, tablet)) {
      assertThat(device.rows(album)).hasSize(2_000)
      assertThat(device.albums.all.value.byId(album)!!.count).isEqualTo(2_000)
      assertThat(device.albums.projection.pending.value).isEqualTo(0)
    }
    // Ogni dispositivo li vede con i SUOI id e nello stesso ordine.
    assertThat(phone.rows(album).map { it.mediaId }.toSet()).isEqualTo((0 until 2_000).map { phone.idOf(it).value }.toSet())
    assertThat(tablet.rows(album).map { it.mediaId }.toSet()).isEqualTo((0 until 2_000).map { tablet.idOf(it).value }.toSet())
    assertThat(phone.orderedFingerprints(album)).isEqualTo(tablet.orderedFingerprints(album))
    assertThat(phone.orderedFingerprints(album).toSet()).isEqualTo((0 until 2_000).map { fileFp(it) }.toSet())
  }

  // --- Unione d'identita' (E-I18) --------------------------------------------------------------------------------------------

  @Test
  fun lAggiuntaDelloStessoFileConDueIdDiversiEUnElementoSoloEUnaRimozioneLoTogliePerTutti() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    for (d in listOf(phone, tablet)) d.learnAll(0 until 4)
    val album = phone.createAlbum("Oslo")
    exchange(phone, tablet)
    tablet.albums.reconcileAll()

    // Lo stesso file (il 1) aggiunto da tutti e due prima di scambiarsi lo stato: due voci con due id diversi.
    phone.albums.add(album, listOf(phone.idOf(1), phone.idOf(2)))
    tablet.albums.add(album, listOf(tablet.idOf(1), tablet.idOf(3)))
    phone.albums.setCover(album, phone.idOf(1))
    exchange(phone, tablet)
    phone.albums.reconcileAll()
    tablet.albums.reconcileAll()

    for (d in listOf(phone, tablet)) {
      assertThat(d.rows(album).map { it.fp }.toSet()).isEqualTo(setOf(fileFp(1), fileFp(2), fileFp(3)))
      assertThat(d.rows(album)).hasSize(3)
      assertThat(d.albums.all.value.byId(album)!!.count).isEqualTo(3)
    }
    // La copertina scelta sul telefono con il suo id si ritrova sul tablet con l'altro.
    assertThat(tablet.room.db.albumDao().get(album.value)!!.coverMediaId).isEqualTo(tablet.idOf(1).value)
    assertThat(tablet.albums.all.value.byId(album)!!.cover.media).isEqualTo(tablet.idOf(1))
    assertThat(phone.albums.all.value.byId(album)!!.cover.media).isEqualTo(phone.idOf(1))

    // Il tablet toglie quel file: spegne le due voci vive che gli indicano lo stesso media, quindi sparisce anche dal telefono.
    assertThat(tablet.albums.remove(album, listOf(tablet.idOf(1)))).isEqualTo(EditOutcome.Done)
    exchange(tablet, phone)
    phone.albums.reconcileAll()
    assertThat(phone.rows(album).map { it.fp }.toSet()).isEqualTo(setOf(fileFp(2), fileFp(3)))
    assertThat(tablet.rows(album).map { it.fp }.toSet()).isEqualTo(setOf(fileFp(2), fileFp(3)))
    // La copertina era quel file: adesso e' automatica.
    assertThat(phone.albums.all.value.byId(album)!!.cover.media).isNotEqualTo(phone.idOf(1))
  }

  @Test
  fun unaModificaDopoLUnioneRiscriveSulSopravvissutoConLaStessaImpronta() = runBlocking<Unit> {
    val device = device("telefono", 1)
    val twins = device.room.twins(30)
    val (a, b) = twins
    val survivor = minOf(a, b)
    val loser = maxOf(a, b)
    val album = device.createAlbum("Gemelli")
    val other = device.room.record(31)
    // L'utente ha aggiunto il file con l'id che poi e' stato assorbito.
    device.albums.add(album, listOf(loser, other))
    device.room.catalog.merge(a, b)
    device.albums.onCatalogChanged(listOf(a, b))
    val before = device.store.state().keys.filter { it.kind == PersonalKind.ALBUM && PersonalFields.isAlbumItem(it.field) }
    assertThat(before.map { PersonalFields.albumItemMedia(it.field) }).contains(loser.value)

    // La prossima modifica dell'elemento lo riscrive sull'id che il catalogo ha adesso, con la stessa impronta.
    assertThat(device.albums.reorder(album, loser, other)).isEqualTo(EditOutcome.Done)

    val entry = device.store.state().values.single { it.key.field == PersonalFields.albumItem(survivor.value) && it.key.subject == album.value }
    assertThat(entry.value).isInstanceOf(PersonalValue.Number::class.java)
    assertThat(entry.fingerprint).isNotNull()
    assertThat(device.rows(album).map { it.mediaId }).containsExactly(survivor.value, other.value)
    assertThat(device.rows(album).first { it.mediaId == survivor.value }.position).isGreaterThan(device.rows(album).first { it.mediaId == other.value }.position)
  }

  // --- Una modifica che arriva da sola ---------------------------------------------------------------------------------------

  @Test
  fun unAlbumCreatoSuUnAltroDispositivoArrivaEdEIlConteggioSiAggiornaSenzaChiamarloNoi() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    for (d in listOf(phone, tablet)) d.learnAll(0 until 3)
    val album = phone.createAlbum("Dal telefono")
    phone.albums.add(album, listOf(phone.idOf(0), phone.idOf(2)))

    exchange(phone, tablet)

    eventually { tablet.albums.all.value.byId(album)?.count == 2 }
    assertThat(tablet.albums.all.value.byId(album)!!.title).isEqualTo("Dal telefono")
    // E se sul tablet arriva un media di cui l'album aveva l'impronta, torna.
    val later = device("tablet-nuovo", 3)
    later.learn(0)
    exchange(phone, later)
    eventually { later.albums.all.value.byId(album)?.count == 1 }
    later.learn(2)
    later.albums.onCatalogChanged(listOf(later.idOf(2)))
    eventually { later.albums.all.value.byId(album)?.count == 2 }
  }

  @Test
  fun unMediaCheSparisceDalCatalogoNonSiContaETornaConLaStessaImpronta() = runBlocking<Unit> {
    val device = device("telefono", 1)
    device.learnAll(0 until 3)
    val album = device.createAlbum("Sparisce")
    device.albums.add(album, (0 until 3).map { device.idOf(it) })
    assertThat(device.albums.all.value.byId(album)!!.count).isEqualTo(3)
    assertThat(device.albums.setCover(album, device.idOf(1))).isEqualTo(EditOutcome.Done)
    assertThat(device.albums.all.value.byId(album)!!.cover).isEqualTo(CoverRef.Chosen(device.idOf(1)))

    val db = device.room.db.openHelper.writableDatabase
    db.beginTransaction()
    try {
      db.execSQL("DELETE FROM media_version WHERE media_id = ?", arrayOf<Any?>(device.idOf(1).value))
      db.execSQL("DELETE FROM media WHERE media_id = ?", arrayOf<Any?>(device.idOf(1).value))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    device.albums.onCatalogChanged()
    assertThat(device.albums.all.value.byId(album)!!.count).isEqualTo(2)
    // La copertina scelta e' sparita con il suo media: l'album ne ha una automatica (E-H6, D21).
    assertThat(device.albums.all.value.byId(album)!!.cover).isInstanceOf(CoverRef.Auto::class.java)
    assertThat(device.albums.all.value.byId(album)!!.cover.media).isNotEqualTo(device.idOf(1))

    // Torna (la scansione lo ritrova, magari con un altro id): l'elemento era rimasto nello stato.
    device.room.insertRaw(MediaId("00000000-0000-4000-8000-0000000000e1"), 5_001L, "%064x".format(2), T0, 20_260_102)
    device.albums.onCatalogChanged(listOf(MediaId("00000000-0000-4000-8000-0000000000e1")))
    assertThat(device.albums.all.value.byId(album)!!.count).isEqualTo(3)
    assertThat(device.albums.projection.pending.value).isEqualTo(0)
    // E la scelta torna da sola: la voce `cover` e' ancora nello stato, con l'impronta.
    assertThat(device.albums.all.value.byId(album)!!.cover).isEqualTo(CoverRef.Chosen(MediaId("00000000-0000-4000-8000-0000000000e1")))
  }

  @Test
  fun unEsclusioneEUnAccettazioneSuUnIdVecchioNonRestanoDopoL_Unione() = runBlocking<Unit> {
    val device = device("telefono", 1)
    val (a, b) = device.room.twins(50)
    val (c, d) = device.room.twins(51)
    val id = (device.albums.create("Neve", AlbumKind.SMART, SmartRules(null, null, "neve")) as CreateOutcome.Created).id
    // Prima dell'unione: un accettato e un escluso, scritti con gli id che poi vengono assorbiti.
    device.albums.accept(id, listOf(maxOf(a, b)))
    device.albums.exclude(id, listOf(maxOf(c, d)))
    device.room.catalog.merge(a, b)
    device.room.catalog.merge(c, d)
    device.albums.onCatalogChanged(listOf(a, b, c, d))
    assertThat(device.rows(id).map { it.mediaId to it.origin }).containsExactly(minOf(a, b).value to "accepted")

    // Escluso con l'id nuovo: l'accettazione scritta con quello vecchio non lo tiene dentro.
    assertThat(device.albums.exclude(id, listOf(minOf(a, b)))).isEqualTo(EditOutcome.Done)
    assertThat(device.rows(id)).isEmpty()
    // Accettato con l'id nuovo: l'esclusione scritta con quello vecchio non lo tiene fuori.
    assertThat(device.albums.accept(id, listOf(minOf(c, d)))).isEqualTo(EditOutcome.Done)
    assertThat(device.rows(id).map { it.mediaId to it.origin }).containsExactly(minOf(c, d).value to "accepted")
    assertThat(device.room.db.albumChoiceDao().exclusions(id.value).map { it.mediaId }).containsExactly(minOf(a, b).value)
  }

  // --- Ordine ----------------------------------------------------------------------------------------------------------------

  @Test
  fun inserireInMezzoCambiaUnaVoceSolaERestaStabileSuTuttiIDispositivi() = runBlocking<Unit> {
    val phone = device("telefono", 1)
    val tablet = device("tablet", 2)
    for (d in listOf(phone, tablet)) d.learnAll(0 until 6)
    val album = phone.createAlbum("Ordine")
    phone.albums.add(album, (0 until 5).map { phone.idOf(it) })
    exchange(phone, tablet)
    tablet.albums.reconcileAll()
    suspend fun positions() = phone.store.state().values.filter { it.key.subject == album.value && PersonalFields.isAlbumItem(it.key.field) }
      .associate { it.key.field to (it.value as PersonalValue.Number).value }
    val before = positions()

    // Il file 5 (nuovo) va dopo il file 1: una voce sola, gli altri non si sono mossi.
    phone.albums.add(album, listOf(phone.idOf(5)))
    assertThat(phone.albums.reorder(album, phone.idOf(5), phone.idOf(1))).isEqualTo(EditOutcome.Done)
    val after = positions()
    val changed = after.filter { (field, value) -> before[field] != value }.keys
    assertThat(changed).containsExactly(PersonalFields.albumItem(phone.idOf(5).value))
    assertThat(after.getValue(PersonalFields.albumItem(phone.idOf(5).value))).isEqualTo(2_500L)

    exchange(phone, tablet)
    phone.albums.reconcileAll()
    tablet.albums.reconcileAll()
    val expected = listOf(0, 1, 5, 2, 3, 4).map { fileFp(it) }
    assertThat(phone.orderedFingerprints(album)).isEqualTo(expected)
    assertThat(tablet.orderedFingerprints(album)).isEqualTo(expected)
  }

  @Test
  fun quandoNonCePiuPostoSiRicompattaAlottiENonSiPerdeNeUnElementoNeLOrdine() = runBlocking<Unit> {
    val device = device("telefono", 1)
    val total = 12
    device.learnAll(0 until total)
    val album = device.createAlbum("Ricompatta")
    device.albums.add(album, listOf(device.idOf(0), device.idOf(11)))
    // Sempre subito dopo il primo: ogni volta meta' dello spazio, fino a che non ce n'e' piu'.
    var model = listOf(0, 11)
    for (n in 1..10) {
      device.albums.add(album, listOf(device.idOf(n)))
      assertThat(device.albums.reorder(album, device.idOf(n), device.idOf(0))).isEqualTo(EditOutcome.Done)
      model = listOf(0, n) + model.drop(1)
      assertThat(device.orderedFingerprints(album)).isEqualTo(model.map { fileFp(it) })
    }
    // Dopo la ricompattazione le posizioni sono di nuovo a intervalli.
    assertThat(device.rows(album).map { it.position }.sorted().zipWithNext { x, y -> y - x }.all { it > 0 }).isTrue()
    assertThat(device.rows(album)).hasSize(total)
  }

  // --- Regole degli smart ------------------------------------------------------------------------------------------------------

  @Test
  fun leRegoleDiUnoSmartSiRileggonoUgualiDalleTabelleEUnaTroppoLungaNonCreaNiente() = runBlocking<Unit> {
    val device = device("telefono", 1)
    device.learnAll(0 until 3)
    val rules = SmartRules(
      period = SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-09")))),
      place = PlaceRule(listOf(3143244), GeoPoint(59.9139, 10.7522), 25, "Oslo", PlaceScope.AROUND),
      text = "neve",
      examples = listOf(device.idOf(0), device.idOf(2)),
      sources = setOf(SmartSource.MY_PHONE, SmartSource.MY_PRIVATE),
    )
    val id = (device.albums.create("Oslo 2026", AlbumKind.SMART, rules) as CreateOutcome.Created).id
    val album = device.albums.all.value.byId(id) as Album.Smart
    assertThat(album.rules).isEqualTo(rules)
    assertThat(album.frozen).isFalse()
    assertThat(album.sort.code).isEqualTo("oldest")

    val tooLong = SmartRules(SmartPeriod.Days((0 until 300).map { LocalDate.parse("2020-01-01").plusDays(it * 2L) }.toSet()), null, null)
    val refused = device.albums.create("Troppi giorni", AlbumKind.SMART, tooLong)
    assertThat(refused).isInstanceOf(CreateOutcome.Invalid::class.java)
    assertThat(device.albums.all.value.albums).hasSize(1)
  }

  @Test
  fun cambiareLeRegoleLasciaLeEsclusioniELeAccettazioni_E_UnaRegolaTroppoLungaNonCambiaNiente() = runBlocking<Unit> {
    val device = device("telefono", 1)
    device.learnAll(0 until 4)
    val place = PlaceRule(listOf(3143244), GeoPoint(59.9139, 10.7522), 25, "Oslo", PlaceScope.AROUND)
    val first = SmartRules(null, place, "neve", examples = listOf(device.idOf(0), device.idOf(1)))
    val id = (device.albums.create("Oslo", AlbumKind.SMART, first) as CreateOutcome.Created).id
    device.albums.exclude(id, listOf(device.idOf(2)))
    device.albums.accept(id, listOf(device.idOf(3)))

    val second = SmartRules(
      SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-09")))),
      null,
      "sole",
      examples = listOf(device.idOf(1), device.idOf(3)),
    )
    assertThat(device.albums.setRules(id, second)).isEqualTo(EditOutcome.Done)

    val smart = device.albums.all.value.byId(id) as Album.Smart
    assertThat(smart.rules).isEqualTo(second)
    // La regola tolta lascia una lapide; le scelte dell'utente sono quelle di prima.
    assertThat(device.room.db.albumChoiceDao().exclusions(id.value).map { it.mediaId }).containsExactly(device.idOf(2).value)
    assertThat(device.rows(id).map { it.mediaId to it.origin }).containsExactly(device.idOf(3).value to "accepted")
    // Lo stesso testo di nuovo: nessuna voce in piu'.
    val before = device.store.state().size
    assertThat(device.albums.setRules(id, second)).isEqualTo(EditOutcome.Done)
    assertThat(device.store.state().size).isEqualTo(before)

    val tooLong = second.copy(period = SmartPeriod.Days((0 until 300).map { LocalDate.parse("2020-01-01").plusDays(it * 2L) }.toSet()))
    assertThat(device.albums.setRules(id, tooLong)).isInstanceOf(EditOutcome.Invalid::class.java)
    assertThat((device.albums.all.value.byId(id) as Album.Smart).rules).isEqualTo(second)
    val manual = device.createAlbum("A mano")
    assertThat(device.albums.setRules(manual, second)).isInstanceOf(EditOutcome.NotAllowed::class.java)
    assertThat(device.albums.setRules(AlbumId("00000000-0000-4000-8000-000000000777"), second)).isEqualTo(EditOutcome.NotFound)
  }

  // --- Dati ostili -------------------------------------------------------------------------------------------------------------

  @Test
  fun voci_OstiliDiUnAltroDispositivoNonRompronoNienteEUnIdNonValidoNonDiventaUnAlbum() = runBlocking<Unit> {
    val device = device("telefono", 1)
    val good = device.createAlbum("Buono")
    device.learn(0)
    device.albums.add(good, listOf(device.idOf(0)))
    val other = TrustIds.random()
    fun entry(album: String, field: String, value: PersonalValue, at: Long = T0 + 5_000) =
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, field), at, other, value)
    val hostile = listOf(
      entry("non-un-uuid", PersonalFields.ALBUM_DEFINITION, PersonalValue.Text("Id che non e' un album")),
      entry(good.value, "item:non-un-media", PersonalValue.Number(5)),
      entry(good.value, PersonalFields.albumItem(device.idOf(0).value.replace('6', '7')), PersonalValue.Number(Long.MAX_VALUE)),
      entry(good.value, PersonalFields.albumItem("00000000-0000-4000-8000-000000000bad"), PersonalValue.Text("non un numero")),
      entry(good.value, PersonalFields.ALBUM_KIND, PersonalValue.Text("quantico")),
      entry(good.value, PersonalFields.ALBUM_SORT, PersonalValue.Flag(true)),
      entry(good.value, PersonalFields.SMART_PLACE, PersonalValue.Text("around:1:2")),
      entry(good.value, PersonalFields.albumExclude("x"), PersonalValue.Flag(true)),
      entry(good.value, "campo:sconosciuto", PersonalValue.Number(1)),
    )
    device.store.applyRemote(hostile)
    device.albums.reconcileAll()

    // Un album di un tipo che questa versione non conosce non si mostra; quello di un id che non e' un album nemmeno; il resto non rompe niente.
    assertThat(device.albums.all.value.albums.map { it.id }).doesNotContain(good)
    assertThat(device.room.db.albumDao().all().map { it.albumId }).doesNotContain("non-un-uuid")
    // Tolta la voce del tipo sconosciuto, l'album e' di nuovo li' con il suo elemento.
    device.store.applyRemote(listOf(entry(good.value, PersonalFields.ALBUM_KIND, PersonalValue.Removed, at = T0 + 900_000)))
    device.albums.reconcileAll()
    assertThat(device.albums.all.value.byId(good)!!.count).isEqualTo(1)
  }
}
