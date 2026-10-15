package dev.pampa.mycloudgallery.core.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
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
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.store.CatalogPersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La proiezione sul catalogo vero (Room in memoria, Robolectric): un preferito o un nascosto che arriva dagli altri dispositivi dello
 * stesso account diventa lo stato di `media_personal`, quindi e' cio' che la Libreria legge (E-H1: un nascosto sincronizzato non
 * compare in Libreria, filtri, Raccolte). I due dispositivi hanno cataloghi propri, con identita' dei media DIVERSE per lo stesso
 * file: si ritrovano per impronta (dimensione e `qh1`).
 */
@RunWith(AndroidJUnit4::class)
class CatalogProjectionTest {

  private class Phone(val db: GalleryDatabase, val catalog: MediaCatalog, val projection: CatalogPersonalProjection)

  private val opened = ArrayList<GalleryDatabase>()
  private val world = PersonalWorld()
  private var seq = 0L
  private val t0 = 1_760_000_000_000L

  private fun newPhone(): Phone {
    val db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    opened += db
    val catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { t0 }
    return Phone(db, catalog, CatalogPersonalProjection(catalog, db))
  }

  @After
  fun close() {
    opened.forEach { it.close() }
  }

  private fun content(seed: Int) = Random(seed).nextBytes(300_000)

  private fun observation(fingerprint: Fingerprint): Observation {
    seq++
    val location = Location.Phone("external_primary", "DCIM/Camera/", "IMG_$seq.jpg", 100L + seq, "store-v1")
    return Observation(location, FileStamp(fingerprint.size, t0), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, null)
  }

  private suspend fun Phone.record(fingerprint: Fingerprint): MediaId = catalog.record(observation(fingerprint)).mediaId!!

  private suspend fun Phone.visible(): List<String> = db.timelineDao().observeFirstPage(1_000).first().map { it.mediaId }

  private suspend fun Phone.flags(id: MediaId): PersonalFlags =
    db.personalDao().get(id.value)?.let { PersonalFlags(it.favorite, it.hidden) } ?: PersonalFlags()

  // --- E-H1 -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unNascostoSincronizzatoNonCompareMaiInLibreria() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val file = Fingerprint.ofContent(content(1))
    val other = Fingerprint.ofContent(content(2))
    val onA = a.record(file)
    val onB = b.record(file)
    b.record(other)
    a.record(other)
    // Identita' diverse per lo stesso file (nessuno le ha ancora unite): e' il caso che l'impronta deve risolvere.
    assertThat(onA).isNotEqualTo(onB)

    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    assertThat(b.visible()).hasSize(2)

    // Sul telefono l'utente nasconde la foto (dalla Libreria o dallo store: qui dallo store).
    phone.hide(onA.value)
    assertThat(a.visible()).doesNotContain(onA.value)
    phone.syncOnce()
    tablet.syncOnce()

    // Sul tablet la stessa foto e' nascosta: non e' in timeline, non nel conteggio, non fra gli indicizzati; e' fra i nascosti.
    assertThat(b.flags(onB).hidden).isTrue()
    assertThat(b.visible()).doesNotContain(onB.value)
    assertThat(b.db.timelineDao().observeVisibleCount().first()).isEqualTo(1)
    assertThat(b.db.timelineIndexDao().indexRows(listOf(onB.value))).isEmpty()
    assertThat(b.db.timelineIndexDao().hiddenRows().map { it.mediaId }).containsExactly(onB.value)
  }

  @Test
  fun unNascostoChePerQuestoDispositivoEsisteSoloSulNasNonCompareInLibreria() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    for (phone in listOf(a, b)) phone.catalog.registerShare(NasShare(AccountId.of("alessio"), "alessio", NasArea.PRIVATE))
    val file = Fingerprint.ofContent(content(21))
    val keep = Fingerprint.ofContent(content(22))
    suspend fun Phone.recordOnNas(fingerprint: Fingerprint, name: String): MediaId {
      val location = Location.Nas(AccountId.of("alessio"), "alessio", NasPath.of("Foto/$name"))
      return catalog.record(Observation(location, FileStamp(fingerprint.size, t0), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, null)).mediaId!!
    }
    val onA = a.recordOnNas(file, "oslo.jpg")
    a.recordOnNas(keep, "altra.jpg")
    val onB = b.recordOnNas(file, "oslo.jpg")
    b.recordOnNas(keep, "altra.jpg")
    assertThat(b.db.timelineIndexDao().indexRows(listOf(onB.value))).isNotEmpty()

    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    phone.hide(onA.value)
    phone.syncOnce()
    tablet.syncOnce()

    assertThat(b.flags(onB).hidden).isTrue()
    assertThat(b.visible()).doesNotContain(onB.value)
    assertThat(b.db.timelineIndexDao().indexRows(listOf(onB.value))).isEmpty()
    assertThat(b.db.timelineIndexDao().hiddenRows().map { it.mediaId }).containsExactly(onB.value)
    assertThat(b.db.timelineDao().observeVisibleCount().first()).isEqualTo(1)
  }

  @Test
  fun unNascostoTolto_SuUnAltroDispositivo_TornaVisibileOvunque() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val file = Fingerprint.ofContent(content(3))
    val onA = a.record(file)
    val onB = b.record(file)
    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    phone.hide(onA.value)
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(b.visible()).isEmpty()

    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    tablet.hide(onB.value, on = false)
    assertThat(b.visible()).containsExactly(onB.value)
    tablet.syncOnce()
    phone.syncOnce()
    assertThat(a.flags(onA).hidden).isFalse()
    assertThat(a.visible()).containsExactly(onA.value)
  }

  @Test
  fun unMediaAncoraSconosciutoRestaInAttesaEDopoLaScansioneSiNasconde() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val file = Fingerprint.ofContent(content(4))
    val onA = a.record(file)
    val phone = world.device(projection = a.projection)
    val fresh = world.device(projection = b.projection)
    phone.hide(onA.value)
    phone.favorite(onA.value)
    phone.syncOnce()

    // Il dispositivo nuovo non ha ancora scansionato niente: le voci sono nello stato ma non hanno un media.
    val first = fresh.syncOnce()
    assertThat(first.applied).isEqualTo(2)
    assertThat(first.unresolved).isEqualTo(2)

    // Arriva il media (la replica o la scansione lo portano nel catalogo): al giro dopo la voce si applica.
    val onB = b.record(file)
    assertThat(fresh.store.reproject()).isEqualTo(0)
    assertThat(b.flags(onB)).isEqualTo(PersonalFlags(favorite = true, hidden = true))
    assertThat(b.visible()).isEmpty()
  }

  @Test
  fun dueMediaConLaStessaImprontaRapidaNonSiConfondonoMai() = runBlocking<Unit> {
    val b = newPhone()
    val quick = QuickHash("ab".repeat(32))
    val one = b.record(Fingerprint(5_000, quick, Sha256("01".repeat(32))))
    val two = b.catalog.record(observation(Fingerprint(5_000, quick, Sha256("02".repeat(32)))), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    assertThat(one).isNotEqualTo(two)

    val entry = PersonalEntry(
      flagKey(MediaId.random().value, PersonalFields.HIDDEN),
      NAS_NOW,
      world.device().id,
      PersonalValue.Flag(true),
      PersonalFingerprint(5_000, quick),
    )
    // Con l'impronta ambigua nessuno dei due si nasconde: meglio una foto in piu' visibile che quella sbagliata nascosta.
    assertThat(b.projection.apply(entry)).isEqualTo(ProjectionResult.UNRESOLVED)
    assertThat(b.flags(one).hidden).isFalse()
    assertThat(b.flags(two).hidden).isFalse()
  }

  @Test
  fun unSoggettoCheNonEUnIdDiMediaNonProiettaNiente() = runBlocking<Unit> {
    val b = newPhone()
    val entry = PersonalEntry(flagKey("non-un-uuid", PersonalFields.HIDDEN), NAS_NOW, world.device().id, PersonalValue.Flag(true))
    assertThat(b.projection.apply(entry)).isEqualTo(ProjectionResult.NOT_PROJECTED)
    // Un campo che non e' preferito o nascosto, o un tipo senza proiezione, nemmeno.
    val id = b.record(Fingerprint.ofContent(content(5)))
    val odd = PersonalEntry(flagKey(id.value, "colore"), NAS_NOW, world.device().id, PersonalValue.Flag(true))
    assertThat(b.projection.apply(odd)).isEqualTo(ProjectionResult.NOT_PROJECTED)
    assertThat(b.flags(id)).isEqualTo(PersonalFlags())
  }

  // --- Modifiche fatte senza passare dallo store -------------------------------------------------------------------------------------

  @Test
  fun iPreferitiGiaNelCatalogoELeModificheDellaLibreriaArrivanoAgliAltriDispositivi() = runBlocking<Unit> {
    val a = newPhone()
    val b = newPhone()
    val one = Fingerprint.ofContent(content(6))
    val two = Fingerprint.ofContent(content(7))
    val a1 = a.record(one)
    val a2 = a.record(two)
    val b1 = b.record(one)
    val b2 = b.record(two)
    // Preferiti accesi prima che la sincronizzazione esistesse, e nascosti dalla Libreria (che scrive il catalogo senza lo store).
    a.catalog.setPersonalFlags(a1, PersonalFlags(favorite = true))
    a.catalog.setPersonalFlags(a2, PersonalFlags(hidden = true))

    val phone = world.device(projection = a.projection)
    val tablet = world.device(projection = b.projection)
    val report = phone.syncOnce()
    assertThat(report.published).isEqualTo(2)
    tablet.syncOnce()
    assertThat(b.flags(b1)).isEqualTo(PersonalFlags(favorite = true))
    assertThat(b.flags(b2)).isEqualTo(PersonalFlags(hidden = true))
    assertThat(b.visible()).containsExactly(b1.value)

    // La Libreria del telefono toglie il preferito e mostra la foto nascosta, ancora senza lo store: al giro dopo lo sanno tutti.
    a.catalog.setPersonalFlags(a1, PersonalFlags())
    a.catalog.setPersonalFlags(a2, PersonalFlags())
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    phone.syncOnce()
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    tablet.syncOnce()
    assertThat(b.flags(b1)).isEqualTo(PersonalFlags())
    assertThat(b.flags(b2)).isEqualTo(PersonalFlags())
    assertThat(b.visible()).containsExactly(b1.value, b2.value)
  }

  @Test
  fun nessunaRiscritturaInutileDelCatalogoSeIlValoreEGiaQuello() = runBlocking<Unit> {
    val a = newPhone()
    val id = a.record(Fingerprint.ofContent(content(8)))
    a.catalog.setPersonalFlags(id, PersonalFlags(hidden = true))
    val before = a.db.personalDao().get(id.value)!!
    val entry = PersonalEntry(flagKey(id.value, PersonalFields.HIDDEN), NAS_NOW, world.device().id, PersonalValue.Flag(true))
    assertThat(a.projection.apply(entry)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.db.personalDao().get(id.value)).isEqualTo(before)
  }

  @Test
  fun unaLapideSuUnFlagLoSpegne() = runBlocking<Unit> {
    val a = newPhone()
    val id = a.record(Fingerprint.ofContent(content(9)))
    a.catalog.setPersonalFlags(id, PersonalFlags(favorite = true, hidden = true))
    val tombstone = PersonalEntry(flagKey(id.value, PersonalFields.HIDDEN), NAS_NOW, world.device().id, PersonalValue.Removed)
    assertThat(a.projection.apply(tombstone)).isEqualTo(ProjectionResult.APPLIED)
    assertThat(a.flags(id)).isEqualTo(PersonalFlags(favorite = true, hidden = false))
  }
}
