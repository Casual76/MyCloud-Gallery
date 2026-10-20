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
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.RemoteIdentity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords
import dev.pampa.mycloudgallery.core.personal.store.CatalogPersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.store.SimilarCopiesChoices
import dev.pampa.mycloudgallery.core.trust.TrustLimits
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

  private suspend fun Phone.nasCopy(seed: Int): MediaId = nasCopy("Foto/c$seed.jpg", fingerprint(seed))

  private suspend fun Phone.nasCopy(path: String, fingerprint: Fingerprint): MediaId {
    val location = Location.Nas(me, "alessio", NasPath.of(path))
    val observation = Observation(location, FileStamp(fingerprint.size, t0), fingerprint, MediaKind.PHOTO, MediaSource.OTHER)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = t0).single().mediaId!!
  }

  /**
   * La stessa copia sul NAS sui due dispositivi, ognuno con la **sua** identita': nessuna adozione. E' il caso che D7 e D8 lasciano
   * aperto (le identita' dei file del NAS convergono al minore, un media con la copia sul telefono tiene la sua): la stessa foto con due
   * id diversi, che nessuno dei due dispositivi conosce dell'altro.
   */
  private suspend fun apart(a: Phone, b: Phone, seed: Int): Pair<MediaId, MediaId> {
    val onA = a.nasCopy(seed)
    val onB = b.nasCopy(seed)
    assertThat(onA).isNotEqualTo(onB)
    assertThat(b.catalog.resolve(onA)).isNull()
    assertThat(a.catalog.resolve(onB)).isNull()
    return onA to onB
  }

  /** Un'impronta con la stessa dimensione e lo stesso `qh1` di [seed] ma altri byte nel mezzo (L32): un altro media, la stessa impronta rapida. */
  private fun twinOf(seed: Int, n: Int): Fingerprint {
    val base = fingerprint(seed)
    return Fingerprint(base.size, base.quickHash, Sha256.of(Random(seed * 1_000 + n).nextBytes(64)))
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
    // La copia y c'e' sul telefono ma il tablet non l'ha ancora vista, ne' per id ne' per impronta (20/10: una copia che il tablet avesse
    // gia' con un altro id la ritroverebbe per impronta, `conIdDiversi...`): la voce nomina un media che li' non c'e'.
    val y = a.nasCopy(22)
    a.group(listOf(x, y))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    SimilarCopiesChoices(phone.store).saveSeparation(y, listOf(x))
    phone.syncOnce()
    tablet.syncOnce()
    // Sul tablet la voce c'e', ma la copia y non c'e' ancora: resta in attesa.
    assertThat(tablet.store.reproject()).isEqualTo(1)
    // La scansione del tablet trova la copia (con un id suo, D7) e le due copie finiscono nello stesso gruppo prima che il rilevatore sappia
    // della separazione: la voce in attesa si applica.
    val onB = b.nasCopy(22)
    assertThat(b.catalog.resolve(y)).isNull()
    b.group(listOf(x, onB))
    assertThat(tablet.store.reproject()).isEqualTo(0)
    assertThat(b.membersOf(x)).isEmpty()
    assertThat(b.membersOf(onB)).isEmpty()
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

  // --- "Separa" fra dispositivi con id diversi (20/10) ----------------------------------------------------------------------------------

  @Test
  fun conIdDiversiUnSeparaSulTelefonoScioglieIlGruppoAncheSulTablet() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val (xA, xB) = apart(a, b, 201)
    val (yA, yB) = apart(a, b, 202)
    a.group(listOf(xA, yA))
    b.group(listOf(xB, yB))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)

    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveSeparation(yA, listOf(xA))
    a.catalog.separate(yA)
    phone.syncOnce()
    val report = tablet.syncOnce()

    // La voce nomina le copie con gli id del telefono, che il tablet non conosce: le ritrova per impronta, tutte e due.
    assertThat(report.unresolved).isEqualTo(0)
    assertThat(b.membersOf(xB)).isEmpty()
    assertThat(b.membersOf(yB)).isEmpty()
    // Il rilevatore del tablet la vede con le identita' del tablet: il gruppo non si riforma.
    val pairs = SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.separatedPairs(listOf(xB, yB))
    assertThat(pairs).containsExactly(minOf(xB, yB) to maxOf(xB, yB))
  }

  @Test
  fun conIdDiversiSeparandoUnaCopiaDaTreEsceLaStessaCopiaAncheSulTablet() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val pairs = listOf(apart(a, b, 221), apart(a, b, 222), apart(a, b, 223))
    val onA = pairs.map { it.first }
    val onB = pairs.map { it.second }
    a.group(onA)
    b.group(onB)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    // Il telefono separa la seconda copia (due voci, una per ognuna delle altre). Sul tablet esce la stessa foto, non quella con l'id
    // che li' e' maggiore o minore: chi esce si decide dai legami, che sono gli stessi su ogni dispositivo.
    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveSeparation(onA[1], listOf(onA[0], onA[2]))
    a.catalog.separate(onA[1])
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(b.membersOf(onB[1])).isEmpty()
    assertThat(b.membersOf(onB[0])).containsExactly(onB[0], onB[2])
    assertThat(a.membersOf(onA[0])).containsExactly(onA[0], onA[2])
  }

  @Test
  fun conIdDiversiUnImprontaCheIndicaDueMediaNonSeparaNienteEResta_inAttesa() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val (xA, xB) = apart(a, b, 231)
    val yA = a.nasCopy(232)
    // Sul tablet ci sono due media con l'impronta rapida di y (L32: diversi solo nel mezzo; come due scatti di una raffica che il catalogo
    // tiene separati). Quale dei due ha separato l'utente? Non si sa: nessuno.
    val y1 = b.nasCopy("Foto/r1.jpg", twinOf(232, 1))
    val y2 = b.nasCopy("Foto/r2.jpg", twinOf(232, 2))
    assertThat(y1).isNotEqualTo(y2)
    a.group(listOf(xA, yA))
    b.group(listOf(xB, y1, y2))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveSeparation(yA, listOf(xA))
    phone.syncOnce()
    tablet.syncOnce()
    // Nessuna copia esce dal gruppo e la voce resta in attesa (si riprova), mai applicata a uno dei due a caso.
    assertThat(b.membersOf(xB)).containsExactly(xB, y1, y2)
    assertThat(tablet.store.reproject()).isEqualTo(1)
    assertThat(SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.separatedPairs(listOf(xB, y1, y2))).isEmpty()
  }

  @Test
  fun conIdDiversiLaLapideRendeDiNuovoUnibili_daQualunqueDeiDueDispositivi() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val (xA, xB) = apart(a, b, 251)
    val (yA, yB) = apart(a, b, 252)
    a.group(listOf(xA, yA))
    b.group(listOf(xB, yB))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    val onPhone = SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }
    val onTablet = SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }
    fun later() = world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)

    onPhone.saveSeparation(yA, listOf(xA))
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(onTablet.separatedPairs(listOf(xB, yB))).hasSize(1)

    // Il tablet le rende di nuovo unibili con i suoi id: la lapide va sulla voce del telefono (gli id del telefono), ritrovata per impronta.
    later()
    onTablet.removeSeparation(xB, yB)
    val phoneKey = SimilarCopiesRecords.separation(xA.value, yA.value)
    assertThat(tablet.store.effective().getValue(phoneKey).value).isEqualTo(PersonalValue.Removed)
    assertThat(onTablet.separatedPairs(listOf(xB, yB))).isEmpty()
    tablet.syncOnce()
    phone.syncOnce()
    assertThat(phone.store.effective().getValue(phoneKey).value).isEqualTo(PersonalValue.Removed)
    assertThat(onPhone.separatedPairs(listOf(xA, yA))).isEmpty()

    // Nell'altro verso: il telefono separa di nuovo e poi toglie lui la separazione; la lapide arriva al tablet con gli id del telefono.
    later()
    onPhone.saveSeparation(yA, listOf(xA))
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(onTablet.separatedPairs(listOf(xB, yB))).hasSize(1)
    later()
    onPhone.removeSeparation(xA, yA)
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(onTablet.separatedPairs(listOf(xB, yB))).isEmpty()
    // La lapide non riunisce niente da se': il gruppo lo rifa' il rilevatore al giro dopo (qui nessuno), e il tablet non separa altro.
    assertThat(b.membersOf(xB)).isEmpty()
  }

  @Test
  fun unaVoceNelFormatoDiPrimaSiLeggeComePrima_soloPerId() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    // Una voce scritta da una versione precedente dell'app (sul NAS di prova ce ne possono essere): `true`, senza l'impronta dell'altro media.
    val x = shared(a, b, 241)
    val y = shared(a, b, 242)
    a.group(listOf(x, y))
    b.group(listOf(x, y))
    val (uA, uB) = apart(a, b, 243)
    val (vA, vB) = apart(a, b, 244)
    a.group(listOf(uA, vA))
    b.group(listOf(uB, vB))
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    phone.store.set(SimilarCopiesRecords.separation(x.value, y.value), PersonalValue.Flag(true))
    phone.store.set(SimilarCopiesRecords.separation(uA.value, vA.value), PersonalValue.Flag(true))
    phone.syncOnce()
    tablet.syncOnce()
    // Con gli stessi id vale come prima.
    assertThat(b.membersOf(x)).isEmpty()
    assertThat(SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.separatedPairs(listOf(x, y))).hasSize(1)
    // Con id diversi l'altro media si cerca solo per id, come prima: la voce resta in attesa e il gruppo resta.
    assertThat(b.membersOf(uB)).containsExactly(uB, vB)
    assertThat(SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.separatedPairs(listOf(uB, vB))).isEmpty()
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

  // --- "Mostra questa come principale" fra dispositivi con id diversi (LA-2 della verifica funzionale della 5B) --------------------------

  @Test
  fun conIdDiversiLaCopertinaSceltaSulTelefonoArrivaSulTabletEIlRilevatoreLaLegge() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val (xA, xB) = apart(a, b, 301)
    val (yA, yB) = apart(a, b, 302)
    val (zA, zB) = apart(a, b, 303)
    a.group(listOf(xA, yA, zA), cover = xA)
    b.group(listOf(xB, yB, zB), cover = xB)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)

    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveCover(yA, listOf(xA, zA))
    a.catalog.setSimilarCover(a.catalog.similarGroupOf(xA)!!.group, yA)
    phone.syncOnce()
    val report = tablet.syncOnce()

    // La voce nomina la copia con l'id del telefono, che il tablet non conosce: la ritrova per impronta.
    assertThat(report.unresolved).isEqualTo(0)
    assertThat(b.catalog.similarGroupOf(xB)!!.cover).isEqualTo(yB)
    // E il rilevatore del tablet, che rifa' i gruppi, la legge con le identita' del tablet: prima restava null e la copertina tornava
    // quella della regola al giro dopo.
    assertThat(SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.chosenCover(listOf(xB, yB, zB))).isEqualTo(yB)
    assertThat(SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.chosenCover(listOf(xA, yA, zA))).isEqualTo(yA)
  }

  @Test
  fun conIdDiversiLaSceltaPiuRecenteVinceSuiDueDispositivi() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val (xA, xB) = apart(a, b, 311)
    val (yA, yB) = apart(a, b, 312)
    val (zA, zB) = apart(a, b, 313)
    a.group(listOf(xA, yA, zA), cover = xA)
    b.group(listOf(xB, yB, zB), cover = xB)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)

    // Il telefono sceglie y; il tablet, piu' tardi, sceglie z. Nessuno dei due ha ancora visto l'altro, e gli id sono diversi.
    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveCover(yA, listOf(xA, zA))
    a.catalog.setSimilarCover(a.catalog.similarGroupOf(xA)!!.group, yA)
    world.clock.advance(10_000)
    SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.saveCover(zB, listOf(xB, yB))
    b.catalog.setSimilarCover(b.catalog.similarGroupOf(xB)!!.group, zB)

    phone.syncOnce()
    tablet.syncOnce()
    phone.syncOnce()

    // Vince la piu' recente, su entrambi, anche per chi legge la scelta dallo stato (il rilevatore): il telefono prima ignorava la voce
    // del tablet (id sconosciuto) e vedeva solo la propria.
    assertThat(a.catalog.similarGroupOf(xA)!!.cover).isEqualTo(zA)
    assertThat(b.catalog.similarGroupOf(xB)!!.cover).isEqualTo(zB)
    assertThat(SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.chosenCover(listOf(xA, yA, zA))).isEqualTo(zA)
    assertThat(SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.chosenCover(listOf(xB, yB, zB))).isEqualTo(zB)
  }

  @Test
  fun conIdDiversiUnaNuovaSceltaSpegneQuellaDellAltroDispositivo() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val (xA, xB) = apart(a, b, 321)
    val (yA, yB) = apart(a, b, 322)
    a.group(listOf(xA, yA), cover = xA)
    b.group(listOf(xB, yB), cover = xB)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveCover(yA, listOf(xA))
    phone.syncOnce()
    tablet.syncOnce()
    val onTablet = SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }
    assertThat(onTablet.chosenCover(listOf(xB, yB))).isEqualTo(yB)

    // Il tablet cambia idea: sceglie x. La voce che il telefono aveva acceso su y (con l'id del telefono) deve spegnersi, altrimenti
    // resta una seconda scelta accesa che la prossima lettura potrebbe preferire.
    world.clock.advance(10_000)
    onTablet.saveCover(xB, listOf(yB))

    assertThat(onTablet.chosenCover(listOf(xB, yB))).isEqualTo(xB)
    val on = tablet.store.effective().filter { (key, entry) ->
      key.field == PersonalFields.SIMILAR_COVER && (entry.value as? PersonalValue.Flag)?.on == true
    }
    assertThat(on).hasSize(1)
  }

  @Test
  fun lOrdineDiArrivoNonCambiaLaCopertinaNemmenoConUnaVoceDiAltroDispositivo() = runBlocking<Unit> {
    val a = newPhone()
    val x = a.nasCopy(331)
    val y = a.nasCopy(332)
    val z = a.nasCopy(333)
    val group = a.group(listOf(x, y, z), cover = x)
    // La voce piu' recente e' di un altro dispositivo e nomina z con un id che qui non c'e': si ritrova per impronta.
    val foreignZ = PersonalEntry(
      SimilarCopiesRecords.cover(MediaId.random().value), 2_000, author, PersonalValue.Flag(true), a.projection.fingerprintOfMedia(z.value),
    )
    val ownY = PersonalEntry(SimilarCopiesRecords.cover(y.value), 1_000, author, PersonalValue.Flag(true))
    val state = mapOf(ownY.key to ownY, foreignZ.key to foreignZ)

    // Arriva prima la piu' vecchia, con quella dell'altro dispositivo gia' nello stato: prima non la vedeva e la copertina diventava y.
    assertThat(a.projection.apply(ownY, state)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.catalog.similarGroup(group)!!.cover).isEqualTo(z)
    assertThat(a.projection.apply(foreignZ, state)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.catalog.similarGroup(group)!!.cover).isEqualTo(z)
  }

  @Test
  fun conIdDiversiUnaCopertinaConUnaImprontaAmbiguaNonSceglieNienteECopertinaNonCambia() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val xA = a.nasCopy(341)
    val zA = a.nasCopy(342)
    // Sul tablet ci sono due media con l'impronta rapida di x (L32: diversi solo nel mezzo): quale dei due ha scelto l'utente? Nessuno.
    val x1 = b.nasCopy("Foto/t1.jpg", twinOf(341, 1))
    val x2 = b.nasCopy("Foto/t2.jpg", twinOf(341, 2))
    val zB = b.nasCopy(342)
    a.group(listOf(xA, zA), cover = zA)
    b.group(listOf(x1, x2, zB), cover = zB)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    SimilarCopiesChoices(phone.store) { a.catalog.resolve(it) }.saveCover(xA, listOf(zA))
    phone.syncOnce()
    tablet.syncOnce()

    // Un'impronta che indica due media non indica nessuno: la scelta resta in attesa, mai applicata a uno dei due a caso.
    assertThat(b.catalog.similarGroupOf(zB)!!.cover).isEqualTo(zB)
    assertThat(tablet.store.reproject()).isEqualTo(1)
    assertThat(SimilarCopiesChoices(tablet.store) { b.catalog.resolve(it) }.chosenCover(listOf(x1, x2, zB))).isNull()
  }
}
