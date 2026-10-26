package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cio' che gli album chiedono al catalogo oltre ai candidati (Fase 7, P1): i fatti dei media per il piano della condivisione (D12),
 * l'attestazione degli elementi condivisi nell'area comune (D9), i doppioni identici per parte (D25) e le letture di visibilita'.
 */
@RunWith(AndroidJUnit4::class)
class AlbumFactsTest : CatalogTestBase() {

  private val albums get() = catalog.albums

  private fun stampOf(fingerprint: Fingerprint) = FileStamp(fingerprint.size, T0)

  private fun keyOf(fingerprint: Fingerprint) = ContentKey.of(fingerprint.quickHash, fingerprint.size)

  // --- fatti ---------------------------------------------------------------------------------------------------------------

  @Test
  fun iFattiDicononDoveStaOgniMedia() = runBlocking<Unit> {
    val onPhone = catalog.record(phoneObservation("IMG_1.jpg", 101, smallFull(1), T0 - 1)).mediaId!!
    val privateOnly = catalog.record(nasObservation("Backup/S25/2026/03/b.jpg", smallQuick(2))).mediaId!!
    val everywhere = catalog.record(phoneObservation("IMG_3.jpg", 103, smallFull(3), T0 - 3)).mediaId!!
    val fp3 = smallFull(3)
    catalog.attachCopy(everywhere, catalog.versionOf(everywhere), nasLocation("Backup/S25/2026/03/c.jpg"), stampOf(fp3), null)
    catalog.attachCopy(everywhere, catalog.versionOf(everywhere), nasLocation("Album condivisi/Oslo/c.jpg", "NAS"), stampOf(fp3), null)
    val commonOnly = catalog.record(nasObservation("Album condivisi/Oslo/d.jpg", smallQuick(4), share = "NAS")).mediaId!!
    val elsewhere = catalog.record(nasObservation("Cartella/e.jpg", smallQuick(5), share = "ignota")).mediaId!!
    val hiddenOne = catalog.record(phoneObservation("IMG_6.jpg", 106, smallFull(6), T0 - 6)).mediaId!!
    catalog.setPersonalFlags(hiddenOne, PersonalFlags(hidden = true))
    val ghost = MediaId.random()

    val facts = albums.facts(listOf(onPhone, privateOnly, everywhere, commonOnly, elsewhere, hiddenOne, ghost))

    assertThat(facts).doesNotContainKey(ghost)
    assertThat(facts.getValue(onPhone).place).isEqualTo(CatalogPlace.PHONE_ONLY)
    assertThat(facts.getValue(onPhone).nas).isNull()
    assertThat(facts.getValue(onPhone).onPhone).isTrue()
    assertThat(facts.getValue(onPhone).mine).isTrue()
    assertThat(facts.getValue(onPhone).size).isEqualTo(smallFull(1).size)
    assertThat(facts.getValue(onPhone).contentKey).isEqualTo(keyOf(smallFull(1)))
    assertThat(facts.getValue(onPhone).version).isEqualTo(catalog.versionOf(onPhone))
    assertThat(facts.getValue(privateOnly).place).isEqualTo(CatalogPlace.MY_PRIVATE)
    assertThat(facts.getValue(privateOnly).nas).isEqualTo(CatalogNasFile("alessio", "Backup/S25/2026/03/b.jpg"))
    assertThat(facts.getValue(privateOnly).onPhone).isFalse()
    // Con una copia nell'area comune il media "e' gia' li'": si fa riferimento a quella (E-SH11), anche se ne ha una privata.
    assertThat(facts.getValue(everywhere).place).isEqualTo(CatalogPlace.COMMON)
    assertThat(facts.getValue(everywhere).nas).isEqualTo(CatalogNasFile("NAS", "Album condivisi/Oslo/c.jpg"))
    assertThat(facts.getValue(everywhere).onPhone).isTrue()
    assertThat(facts.getValue(commonOnly).place).isEqualTo(CatalogPlace.COMMON)
    assertThat(facts.getValue(commonOnly).mine).isFalse()
    assertThat(facts.getValue(elsewhere).place).isEqualTo(CatalogPlace.OTHERS_PRIVATE)
    // Anche i nascosti: li filtra il piano (Q3), non questa lettura.
    assertThat(facts.getValue(hiddenOne).hidden).isTrue()
    assertThat(facts.getValue(onPhone).hidden).isFalse()
    assertThat(facts.getValue(onPhone).toString()).doesNotContain("IMG_1")
  }

  @Test
  fun unIdAssorbitoDaUnUnioneSiRisolveEPortaIFattiDelSopravvissuto() = runBlocking<Unit> {
    val full = smallFull(7)
    val a = catalog.record(phoneObservation("IMG_7a.jpg", 107, full, T0 - 7)).mediaId!!
    val b = catalog.record(phoneObservation("IMG_7b.jpg", 207, full.copy(quickHash = QuickHash("f".repeat(64))), T0 - 7)).mediaId!!
    val merged = catalog.merge(a, b) as MediaCatalog.MergeOutcome.Merged

    val facts = albums.facts(listOf(merged.loser, merged.survivor))

    assertThat(facts.keys).containsExactly(merged.loser, merged.survivor)
    assertThat(facts.getValue(merged.loser).requested).isEqualTo(merged.loser)
    assertThat(facts.getValue(merged.loser).media).isEqualTo(merged.survivor)
    assertThat(facts.getValue(merged.survivor).media).isEqualTo(merged.survivor)
  }

  // --- attestazione --------------------------------------------------------------------------------------------------------

  @Test
  fun unElementoSiAttestaSoloSeLaCopiaStaNellAreaComuneConLaStessaChiave() = runBlocking<Unit> {
    val common = catalog.record(nasObservation("Album condivisi/Oslo/a.jpg", smallFull(1), share = "NAS")).mediaId!!
    val privateOnly = catalog.record(nasObservation("Backup/S25/b.jpg", smallFull(2))).mediaId!!
    val phoneOnly = catalog.record(phoneObservation("IMG_3.jpg", 103, smallFull(3), T0 - 3)).mediaId!!
    val gone = catalog.record(nasObservation("Album condivisi/Oslo/g.jpg", smallFull(4), share = "NAS")).mediaId!!
    catalog.markNasVanished(listOf(nasLocation("Album condivisi/Oslo/g.jpg", "NAS").key))
    val hiddenCommon = catalog.record(nasObservation("Album condivisi/Oslo/h.jpg", smallFull(5), share = "NAS")).mediaId!!
    catalog.setPersonalFlags(hiddenCommon, PersonalFlags(hidden = true))

    fun request(seed: Int, hash: Boolean = false) = CommonAttestRequest(keyOf(smallFull(seed)), if (hash) smallFull(seed).fullHash else null)
    val found = albums.attestInCommon(
      listOf(request(1), request(2), request(3), request(4), request(5), request(6), request(1, hash = true)),
    )

    // Solo l'area comune e solo file presenti; il nascosto si attesta lo stesso (e' un fatto del catalogo, lo filtra il resto).
    assertThat(found).containsExactly(keyOf(smallFull(1)), common, keyOf(smallFull(5)), hiddenCommon)
    assertThat(found).doesNotContainKey(keyOf(smallFull(2)))
    assertThat(found).doesNotContainKey(keyOf(smallFull(3)))
    assertThat(found).doesNotContainKey(keyOf(smallFull(4)))
    assertThat(privateOnly).isNotEqualTo(common)
    assertThat(phoneOnly).isNotEqualTo(gone)
    assertThat(albums.attestInCommon(emptyList())).isEmpty()
  }

  @Test
  fun loHashCompletoCheNonTornaImpedisceLAttestazione() = runBlocking<Unit> {
    val full = smallFull(8)
    val media = catalog.record(nasObservation("Album condivisi/Oslo/a.jpg", full, share = "NAS")).mediaId!!
    val key = keyOf(full)
    val other = Sha256("b".repeat(64))

    assertThat(albums.attestInCommon(listOf(CommonAttestRequest(key, full.fullHash)))).containsExactly(key, media)
    assertThat(albums.attestInCommon(listOf(CommonAttestRequest(key, null)))).containsExactly(key, media)
    assertThat(albums.attestInCommon(listOf(CommonAttestRequest(key, other)))).isEmpty()
    // Un media nel catalogo senza hash completo non puo' smentire quello della voce (basta la chiave).
    val quick = smallQuick(9)
    val unverified = catalog.record(nasObservation("Album condivisi/Oslo/b.jpg", quick, share = "NAS")).mediaId!!
    assertThat(albums.attestInCommon(listOf(CommonAttestRequest(keyOf(quick), other)))).containsExactly(keyOf(quick), unverified)
  }

  @Test
  fun conDueMediaConLaStessaChiaveVinceLIdPiuBasso() = runBlocking<Unit> {
    val a = smallFull(10)
    val b = Fingerprint(a.size, a.quickHash, smallFull(11).fullHash)
    val first = catalog.record(nasObservation("Album condivisi/a.jpg", a, share = "NAS")).mediaId!!
    val second = catalog.record(nasObservation("Album condivisi/b.jpg", b, share = "NAS")).mediaId!!
    check(first != second) { "Dovevano restare due media." }

    val found = albums.attestInCommon(listOf(CommonAttestRequest(keyOf(a), null)))

    assertThat(found).containsExactly(keyOf(a), minOf(first, second))
  }

  // --- doppioni --------------------------------------------------------------------------------------------------------------

  @Test
  fun iDoppioniIdenticiSonoLeCopiePresentiDellaStessaVersioneDallaStessaParte() = runBlocking<Unit> {
    val fpA = smallFull(20)
    val phoneTwice = catalog.record(phoneObservation("IMG_20.jpg", 120, fpA, T0 - 1)).mediaId!!
    catalog.record(phoneObservation("IMG_20 copia.jpg", 220, fpA, T0 - 1, path = "Download/"))
    val fpB = smallFull(21)
    val nasTwice = catalog.record(phoneObservation("IMG_21.jpg", 121, fpB, T0 - 2)).mediaId!!
    catalog.attachCopy(nasTwice, catalog.versionOf(nasTwice), nasLocation("Backup/S25/2026/03/b.jpg"), stampOf(fpB), T0)
    catalog.attachCopy(nasTwice, catalog.versionOf(nasTwice), nasLocation("Album condivisi/Oslo/b.jpg", "NAS"), stampOf(fpB), null)
    val single = catalog.record(phoneObservation("IMG_22.jpg", 122, smallFull(22), T0 - 3)).mediaId!!
    val hiddenTwice = catalog.record(phoneObservation("IMG_23.jpg", 123, smallFull(23), T0 - 4)).mediaId!!
    catalog.record(phoneObservation("IMG_23 copia.jpg", 223, smallFull(23), T0 - 4, path = "Download/"))
    catalog.setPersonalFlags(hiddenTwice, PersonalFlags(hidden = true))
    val oneVanished = catalog.record(phoneObservation("IMG_24.jpg", 124, smallFull(24), T0 - 5)).mediaId!!
    catalog.record(phoneObservation("IMG_24 copia.jpg", 224, smallFull(24), T0 - 5, path = "Download/"))
    catalog.markPhoneLocationsVanished(listOf(catalog.media(oneVanished)!!.locations.first { it.isPhone }.id))
    check(catalog.media(phoneTwice)!!.locations.size == 2) { "Il secondo file dello stesso contenuto doveva diventare una copia." }

    val all = albums.duplicates(limit = 50)

    assertThat(all.next).isNull()
    assertThat(all.groups.map { it.media to it.part }).containsExactly(phoneTwice to DuplicatePart.PHONE, nasTwice to DuplicatePart.NAS)
    val nas = all.groups.single { it.part == DuplicatePart.NAS }
    assertThat(nas.copies.map { it.place }).containsExactly(CopyPlace.MY_PRIVATE, CopyPlace.COMMON).inOrder()
    assertThat(nas.copies.map { it.share }).containsExactly("alessio", "NAS").inOrder()
    assertThat(nas.copies.first().verifiedAt).isNotNull()
    assertThat(nas.copies.last().verifiedAt).isNull()
    assertThat(nas.fullHash).isEqualTo(fpB.fullHash)
    assertThat(nas.copies.all { it.state == LocationState.PRESENT }).isTrue()
    val phone = all.groups.single { it.part == DuplicatePart.PHONE }
    assertThat(phone.copies.map { it.place }).containsExactly(CopyPlace.PHONE, CopyPlace.PHONE)
    assertThat(phone.copies.map { it.path }).containsExactly("DCIM/Camera/IMG_20.jpg", "Download/IMG_20 copia.jpg")
    assertThat(single).isNotEqualTo(hiddenTwice)
    // Una pagina alla volta: nessun gruppo perso o ripetuto.
    val first = albums.duplicates(limit = 1)
    assertThat(first.groups).hasSize(1)
    assertThat(first.next).isNotNull()
    val second = albums.duplicates(after = first.next, limit = 1)
    assertThat(second.groups).hasSize(1)
    assertThat(second.groups.single().version).isNotEqualTo(first.groups.single().version)
    assertThat(albums.duplicates(after = second.next, limit = 1).groups).isEmpty()
  }

  // --- visibilita' e giorni --------------------------------------------------------------------------------------------

  @Test
  fun visibleAmongRispondeConLaStessaFonteDeiNascostiAncheSuiIdAssorbiti() = runBlocking<Unit> {
    val visible = catalog.record(phoneObservation("IMG_1.jpg", 101, smallFull(1), T0 - 1)).mediaId!!
    val hidden = catalog.record(phoneObservation("IMG_2.jpg", 102, smallFull(2), T0 - 2)).mediaId!!
    val gone = catalog.record(phoneObservation("IMG_3.jpg", 103, smallFull(3), T0 - 3)).mediaId!!
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    catalog.markPhoneLocationsVanished(catalog.media(gone)!!.locations.map { it.id })
    val full = smallFull(4)
    val x = catalog.record(phoneObservation("IMG_4a.jpg", 104, full, T0 - 4)).mediaId!!
    val y = catalog.record(phoneObservation("IMG_4b.jpg", 204, full.copy(quickHash = QuickHash("f".repeat(64))), T0 - 4)).mediaId!!
    val merged = catalog.merge(x, y) as MediaCatalog.MergeOutcome.Merged

    val seen = albums.visibleAmong(listOf(visible, hidden, gone, merged.loser, merged.survivor, MediaId.random()))

    assertThat(seen).containsExactly(visible, merged.loser, merged.survivor)
    // L'alias di un media nascosto non e' visibile.
    catalog.setPersonalFlags(merged.survivor, PersonalFlags(hidden = true))
    assertThat(albums.visibleAmong(listOf(merged.loser, merged.survivor))).isEmpty()
    assertThat(albums.daysOf(listOf(visible, hidden, MediaId.random())).keys).containsExactly(visible, hidden)
  }
}
