package dev.pampa.mycloudgallery.core.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.SimilarGroupOutcome
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.RemoteIdentity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords
import dev.pampa.mycloudgallery.core.personal.store.CatalogPersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.store.SimilarCopiesChoices
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La proiezione delle scelte sulle copie simili (Fase 5A, P15; D36, E-L13): "Separa" e "Mostra questa come principale" fatti su un
 * dispositivo arrivano sugli altri dispositivi dell'utente e si applicano al loro catalogo. Cataloghi veri (Room in memoria), NAS
 * finto con registro firmato e teste vere (`PersonalWorld`): lo store, il lettore e il motore di sincronizzazione sono quelli di produzione.
 *
 * I due dispositivi vedono le stesse copie sul NAS (le scansionano e adottano le identita' dell'indice, D7: convergono al minore), che e'
 * il caso reale delle copie simili: la copia del cloud e l'originale caricato dal backup stanno entrambi sul NAS.
 */
@RunWith(AndroidJUnit4::class)
class SimilarCopiesProjectionTest {

  private class Phone(val db: GalleryDatabase, val catalog: MediaCatalog) {
    val projection = CatalogPersonalProjection(catalog, db)
  }

  private val opened = ArrayList<GalleryDatabase>()
  private val world = PersonalWorld()
  private val me = AccountId.of("alessio")
  private val t0 = 1_760_000_000_000L
  private val author = dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.random()

  private fun newPhone(): Phone {
    val db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    opened += db
    val catalog = MediaCatalog(db, me, zone = { ZoneId.of("Europe/Rome") }) { t0 }
    runBlocking { catalog.registerShare(NasShare(me, "alessio", NasArea.PRIVATE)) }
    return Phone(db, catalog)
  }

  @After
  fun close() {
    opened.forEach { it.close() }
  }

  private fun fingerprint(seed: Int) = Fingerprint.ofContent(Random(seed).nextBytes(2_000))

  private suspend fun Phone.nasCopy(seed: Int): MediaId {
    val location = Location.Nas(me, "alessio", NasPath.of("Foto/c$seed.jpg"))
    val observation = Observation(location, FileStamp(2_000, t0), fingerprint(seed), MediaKind.PHOTO, MediaSource.OTHER)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = t0).single().mediaId!!
  }

  /** La stessa copia sul NAS sui due dispositivi, con la stessa identita' (come dopo l'adozione dell'indice, D7). */
  private suspend fun shared(a: Phone, b: Phone, seed: Int): MediaId {
    val onA = a.nasCopy(seed)
    val onB = b.nasCopy(seed)
    val location = Location.Nas(me, "alessio", NasPath.of("Foto/c$seed.jpg")).key
    val versionA = a.catalog.media(onA)!!.currentVersionId
    val versionB = b.catalog.media(onB)!!.currentVersionId
    a.catalog.adoptIdentity(location, RemoteIdentity(onB, versionB, fingerprint(seed)))
    b.catalog.adoptIdentity(location, RemoteIdentity(onA, versionA, fingerprint(seed)))
    val resolvedA = a.catalog.resolve(onA)!!
    assertThat(b.catalog.resolve(onB)).isEqualTo(resolvedA)
    return resolvedA
  }

  private suspend fun Phone.group(members: List<MediaId>, cover: MediaId = members.first()) =
    (catalog.groupSimilar(members, cover) as SimilarGroupOutcome.Grouped).group

  private suspend fun Phone.membersOf(id: MediaId): Set<MediaId> = catalog.similarGroupOf(id)?.members?.map { it.mediaId }?.toSet().orEmpty()

  // --- "Separa" ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unSeparaSulTelefonoArrivaSulTabletEScioglieIlGruppo() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val x = shared(a, b, 1)
    val y = shared(a, b, 2)
    a.group(listOf(x, y))
    b.group(listOf(x, y))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)

    // Sul telefono: il rilevatore (SimilarCopies.separate) scrive la voce e toglie la copia dal gruppo.
    SimilarCopiesChoices(phone.store).saveSeparation(y, listOf(x))
    a.catalog.separate(y)
    assertThat(a.membersOf(x)).isEmpty()
    phone.syncOnce()
    assertThat(b.membersOf(x)).containsExactly(x, y) // il tablet non lo sa ancora

    tablet.syncOnce()
    assertThat(b.membersOf(x)).isEmpty()
    assertThat(b.membersOf(y)).isEmpty()
    // La separazione e' una voce del tablet, e la vede il rilevatore: il gruppo non si riforma.
    val pairs = SimilarCopiesChoices(tablet.store).separatedPairs(listOf(x, y))
    assertThat(pairs).containsExactly(minOf(x, y) to maxOf(x, y))
  }

  @Test
  fun separandoUnaCopiaDaTreRestanoInsiemeLeAltreDueAncheSulTablet() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val ids = listOf(shared(a, b, 11), shared(a, b, 12), shared(a, b, 13))
    a.group(ids)
    b.group(ids)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    // L'utente separa la copia di mezzo: una voce per ognuna delle altre due.
    val away = ids.sorted()[1]
    val rest = ids.filter { it != away }
    SimilarCopiesChoices(phone.store).saveSeparation(away, rest)
    a.catalog.separate(away)
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(b.membersOf(rest.first())).containsExactlyElementsIn(rest)
    assertThat(b.membersOf(away)).isEmpty()
    assertThat(a.membersOf(rest.first())).containsExactlyElementsIn(rest)
  }

  @Test
  fun unaVoceSolaInUnGruppoDiTreNonDecideChiEsceEQuandoArrivaLaSecondaSiSa() = runBlocking<Unit> {
    val a = newPhone()
    val x = a.nasCopy(91)
    val y = a.nasCopy(92)
    val z = a.nasCopy(93)
    a.group(listOf(x, y, z), cover = x)
    val ids = listOf(x, y, z).sorted()
    val away = ids[1]
    val others = ids.filter { it != away }
    val first = PersonalEntry(SimilarCopiesRecords.separation(away.value, others[0].value), 1_000, author, PersonalValue.Flag(true))
    val second = PersonalEntry(SimilarCopiesRecords.separation(away.value, others[1].value), 1_000, author, PersonalValue.Flag(true))
    // La prima voce da sola: due media con un solo legame, non si sa chi e' stato separato. Il gruppo resta.
    assertThat(a.projection.apply(first, mapOf(first.key to first))).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.membersOf(x)).containsExactly(x, y, z)
    // Con la seconda e' chiaro: esce chi ha due legami.
    assertThat(a.projection.apply(second, mapOf(first.key to first, second.key to second))).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.membersOf(others[0])).containsExactlyElementsIn(others)
    assertThat(a.membersOf(away)).isEmpty()
  }

  @Test
  fun unaSeparazioneArrivataPrimaCheIlTabletConoscaLaCopiaSiApplicaQuandoArriva() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val x = shared(a, b, 21)
    // La copia y sul telefono ha l'identita' piu' piccola di quella del tablet (quando la scoprira' adottera' la sua, D7) e piu' grande di
    // x: la voce sta sul media minore (x), che il tablet conosce, e nomina y, che ancora non conosce.
    var seed = 22
    var y: MediaId
    var onB: MediaId
    while (true) {
      y = a.nasCopy(seed)
      onB = b.nasCopy(seed)
      if (x.value < y.value && y.value < onB.value) break
      seed++
    }
    a.group(listOf(x, y))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    SimilarCopiesChoices(phone.store).saveSeparation(y, listOf(x))
    phone.syncOnce()
    tablet.syncOnce()
    // Sul tablet la voce c'e', ma la copia y non e' ancora la stessa del telefono: resta in attesa.
    assertThat(tablet.store.reproject()).isEqualTo(1)
    // Il tablet adotta l'identita' del telefono e ha gia' le due copie nello stesso gruppo (prima che il rilevatore sappia della separazione).
    val location = Location.Nas(me, "alessio", NasPath.of("Foto/c$seed.jpg")).key
    b.catalog.adoptIdentity(location, RemoteIdentity(y, a.catalog.media(y)!!.currentVersionId, fingerprint(seed)))
    assertThat(b.catalog.resolve(onB)).isEqualTo(y)
    b.group(listOf(x, y))
    assertThat(tablet.store.reproject()).isEqualTo(0)
    assertThat(b.membersOf(x)).isEmpty()
  }

  @Test
  fun unaSceltaScrittaConUnIdPoiSostituitoVaAncoraPerIlSopravvissuto() = runBlocking<Unit> {
    val a = newPhone()
    val first = a.nasCopy(101)
    val second = a.nasCopy(102)
    val third = a.nasCopy(103)
    val phone = world.device(projection = a.projection)
    val choices = SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }
    choices.saveSeparation(first, listOf(second, third))
    choices.saveCover(first, listOf(second, third))
    // L'indice porta un'identita' piu' piccola per la prima copia: la vecchia diventa un alias (D7, E-I18).
    val small = MediaId("00000000-0000-4000-8000-000000000001")
    val location = Location.Nas(me, "alessio", NasPath.of("Foto/c101.jpg")).key
    val outcome = a.catalog.adoptIdentity(location, RemoteIdentity(small, dev.pampa.mycloudgallery.core.model.VersionId("00000000-0000-4000-8000-0000000000a1"), fingerprint(101)))
    assertThat(outcome).isNotNull()
    assertThat(a.catalog.resolve(first)).isEqualTo(small)
    // Le voci scritte con il vecchio id valgono per il sopravvissuto: la separazione e la scelta non si perdono.
    assertThat(choices.separatedPairs(listOf(small, second, third))).containsExactly(small to second, small to third)
    assertThat(choices.chosenCover(listOf(small, second, third))).isEqualTo(small)
    // Senza il risolutore (chi non ha il catalogo) le voci valgono per l'id con cui sono scritte.
    assertThat(SimilarCopiesChoices(phone.store).separatedPairs(listOf(small, second))).isEmpty()
    // E la proiezione le trova: nel gruppo con la vecchia separazione esce la copia che ne ha due.
    a.group(listOf(small, second, third))
    val entry = phone.store.get(SimilarCopiesRecords.separation(first.value, second.value))!!
    assertThat(a.projection.apply(entry, phone.store.effective())).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.membersOf(second)).containsExactly(second, third)
    assertThat(a.membersOf(small)).isEmpty()
  }

  @Test
  fun unaSeparazioneFattaSenzaOraDelNasValeSubitoENonSiPubblicaFinoAllaDatazione() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val x = shared(a, b, 31)
    val y = shared(a, b, 32)
    a.group(listOf(x, y))
    b.group(listOf(x, y))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    phone.anchored = false // niente ora del NAS: la voce resta "da datare"
    val choices = SimilarCopiesChoices(phone.store)
    choices.saveSeparation(y, listOf(x))
    a.catalog.separate(y)
    assertThat(phone.store.undated()).hasSize(1)
    // Lo stato unito non la ha ancora, ma chi decide i gruppi si': il gruppo non si riforma nemmeno al buio.
    assertThat(phone.store.entries.value).isEmpty()
    assertThat(choices.separatedPairs(listOf(x, y))).hasSize(1)
    tablet.syncOnce()
    assertThat(b.membersOf(x)).containsExactly(x, y)
    // Torna il NAS: si data, si pubblica, arriva.
    phone.anchored = true
    phone.store.stamp(phone.nasNow())
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(b.membersOf(x)).isEmpty()
  }

  @Test
  fun unaLapideRendeDiNuovoUnibileEIlNascostoNonCambiaMai() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val x = shared(a, b, 41)
    val y = shared(a, b, 42)
    b.catalog.setPersonalFlags(x, PersonalFlags(hidden = true))
    b.group(listOf(x, y))
    val key = SimilarCopiesRecords.separation(x.value, y.value)
    val tablet = world.device(projection = b.projection)
    // Una lapide non fa niente al catalogo.
    assertThat(b.projection.apply(PersonalEntry(key, 5, author, PersonalValue.Removed))).isEqualTo(ProjectionResult.APPLIED)
    assertThat(b.membersOf(x)).containsExactly(x, y)
    assertThat(SimilarCopiesChoices(tablet.store).separatedPairs(listOf(x, y))).isEmpty()
    // La separazione scioglie il gruppo, ma non toglie il nascosto: la stessa foto non ricompare.
    assertThat(b.projection.apply(PersonalEntry(key, 6, author, PersonalValue.Flag(true)))).isEqualTo(ProjectionResult.APPLIED)
    assertThat(b.membersOf(x)).isEmpty()
    assertThat(b.db.personalDao().get(x.value)?.hidden).isTrue()
    assertThat(b.db.personalDao().get(y.value)?.hidden).isTrue()
    // Applicarla di nuovo non fa niente (idempotente).
    assertThat(b.projection.apply(PersonalEntry(key, 6, author, PersonalValue.Flag(true)))).isEqualTo(ProjectionResult.APPLIED)
  }

  // --- "Mostra questa come principale" -----------------------------------------------------------------------------------------------

  @Test
  fun laCopertinaScelta_Arriva_E_LaPiuRecenteVinceSuDueDispositivi() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val x = shared(a, b, 51)
    val y = shared(a, b, 52)
    val z = shared(a, b, 53)
    for (phone in listOf(a, b)) phone.group(listOf(x, y, z), cover = x)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)

    // Il telefono sceglie y; il tablet, piu' tardi, sceglie z. Nessuno dei due ha ancora visto l'altro.
    SimilarCopiesChoices(phone.store).saveCover(y, listOf(x, z))
    a.catalog.setSimilarCover(a.catalog.similarGroupOf(x)!!.group, y)
    world.clock.advance(10_000)
    SimilarCopiesChoices(tablet.store).saveCover(z, listOf(x, y))
    b.catalog.setSimilarCover(b.catalog.similarGroupOf(x)!!.group, z)
    assertThat(a.catalog.similarGroupOf(x)!!.cover).isEqualTo(y)
    assertThat(b.catalog.similarGroupOf(x)!!.cover).isEqualTo(z)

    phone.syncOnce()
    tablet.syncOnce()
    phone.syncOnce()
    // Vince la scelta piu' recente, su entrambi.
    assertThat(a.catalog.similarGroupOf(x)!!.cover).isEqualTo(z)
    assertThat(b.catalog.similarGroupOf(x)!!.cover).isEqualTo(z)
    assertThat(SimilarCopiesChoices(phone.store).chosenCover(listOf(x, y, z))).isEqualTo(z)
    assertThat(SimilarCopiesChoices(tablet.store).chosenCover(listOf(x, y, z))).isEqualTo(z)
  }

  @Test
  fun lOrdineDiArrivoDelleVociDiCopertinaNonCambiaIlRisultato() = runBlocking<Unit> {
    val a = newPhone()
    val x = a.nasCopy(61)
    val y = a.nasCopy(62)
    val z = a.nasCopy(63)
    val group = a.group(listOf(x, y, z), cover = x)
    val older = PersonalEntry(SimilarCopiesRecords.cover(y.value), 1_000, author, PersonalValue.Flag(true))
    val newer = PersonalEntry(SimilarCopiesRecords.cover(z.value), 2_000, author, PersonalValue.Flag(true))
    val state = mapOf(older.key to older, newer.key to newer)
    // Prima la piu' recente, poi la vecchia (arriva dopo ma non vince): la copertina resta z.
    assertThat(a.projection.apply(newer, state)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.catalog.similarGroup(group)!!.cover).isEqualTo(z)
    assertThat(a.projection.apply(older, state)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.catalog.similarGroup(group)!!.cover).isEqualTo(z)
    // Una voce spenta non cambia niente.
    val off = PersonalEntry(SimilarCopiesRecords.cover(y.value), 3_000, author, PersonalValue.Flag(false))
    assertThat(a.projection.apply(off, state + (off.key to off))).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.catalog.similarGroup(group)!!.cover).isEqualTo(z)
  }

  @Test
  fun unaSceltaPerUnMediaFuoriDaUnGruppoNonFaNienteEUnMediaSconosciutoRestaInAttesa() = runBlocking<Unit> {
    val a = newPhone()
    val lone = a.nasCopy(71)
    val on = PersonalEntry(SimilarCopiesRecords.cover(lone.value), 1_000, author, PersonalValue.Flag(true))
    assertThat(a.projection.apply(on)).isEqualTo(ProjectionResult.APPLIED)
    val unknown = PersonalEntry(SimilarCopiesRecords.cover(MediaId.random().value), 1_000, author, PersonalValue.Flag(true))
    assertThat(a.projection.apply(unknown)).isEqualTo(ProjectionResult.UNRESOLVED)
    val unknownApart = PersonalEntry(SimilarCopiesRecords.separation(lone.value, MediaId.random().value), 1_000, author, PersonalValue.Flag(true))
    assertThat(a.projection.apply(unknownApart)).isEqualTo(ProjectionResult.UNRESOLVED)
    // Un soggetto che non e' un'identita' di media (testo ostile) non ha niente da proiettare.
    val hostile = PersonalEntry(
      dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(
        dev.pampa.mycloudgallery.core.personal.contract.PersonalKind.MEDIA_FLAG, "non-un-uuid", PersonalFields.SIMILAR_COVER,
      ),
      1_000, author, PersonalValue.Flag(true),
    )
    assertThat(a.projection.apply(hostile)).isEqualTo(ProjectionResult.NOT_PROJECTED)
    val hostileApart = PersonalEntry(
      dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(
        dev.pampa.mycloudgallery.core.personal.contract.PersonalKind.MEDIA_FLAG, lone.value, PersonalFields.similarApart("spazzatura"),
      ),
      1_000, author, PersonalValue.Flag(true),
    )
    assertThat(a.projection.apply(hostileApart)).isEqualTo(ProjectionResult.NOT_PROJECTED)
    // Le voci dei preferiti e dei nascosti restano proiettate come prima.
    val fav = PersonalEntry(
      dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(
        dev.pampa.mycloudgallery.core.personal.contract.PersonalKind.MEDIA_FLAG, lone.value, PersonalFields.FAVORITE,
      ),
      1_000, author, PersonalValue.Flag(true),
    )
    assertThat(a.projection.apply(fav)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.db.personalDao().get(lone.value)?.favorite).isTrue()
  }

  @Test
  fun leScelteSiScrivonoEPiuRecentiVinconoAncheSeLoStatoNonLeHaAncora() = runBlocking<Unit> {
    val a = newPhone()
    val x = a.nasCopy(81)
    val y = a.nasCopy(82)
    val phone = world.device(projection = a.projection)
    val choices = SimilarCopiesChoices(phone.store)
    assertThat(choices.chosenCover(listOf(x, y))).isNull()
    choices.saveCover(x, listOf(y))
    assertThat(choices.chosenCover(listOf(x, y))).isEqualTo(x)
    // Cambia idea: la vecchia si spegne (una scelta uguale non sarebbe un cambiamento), la nuova vince.
    world.clock.advance(1_000)
    choices.saveCover(y, listOf(x))
    assertThat(choices.chosenCover(listOf(x, y))).isEqualTo(y)
    // E torna alla prima: serve che la prima sia stata spenta, altrimenti non si riscrive.
    world.clock.advance(1_000)
    choices.saveCover(x, listOf(y))
    assertThat(choices.chosenCover(listOf(x, y))).isEqualTo(x)
    assertThat(choices.chosenCover(emptyList())).isNull()
    assertThat(choices.separatedPairs(listOf(x))).isEmpty()
  }
}
