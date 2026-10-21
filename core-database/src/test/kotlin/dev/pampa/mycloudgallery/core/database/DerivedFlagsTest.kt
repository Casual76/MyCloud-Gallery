package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `setDerivedFlags(ids, DOCUMENT)` (Fase 6, P1; D34): lavora a lotti, **un cambiamento dell'indice della timeline per lotto**, e il
 * bit sopravvive ai ricalcoli dei derivati. Database vero in memoria (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class DerivedFlagsTest : CatalogTestBase() {

  private suspend fun addPhones(n: Int): List<MediaId> =
    (1..n).map { catalog.record(phoneObservation("IMG_$it.jpg", 100L + it, smallFull(it), T0 - it)).mediaId!! }

  private fun flags(id: MediaId): Int = runBlocking { catalog.indexRows(listOf(id)).single().cellFlags }

  private fun isDocument(id: MediaId): Boolean = CellFlags.has(flags(id), CellFlags.DOCUMENT)

  @Test
  fun ilBitSiAccendeSoloSuiMediaIndicatiEL_indiceDellaTimelineLoVede() = runBlocking<Unit> {
    val ids = addPhones(4)
    assertThat(catalog.setDerivedFlags(listOf(ids[0], ids[2]), CellFlags.DOCUMENT)).isEqualTo(2)
    assertThat(ids.map(::isDocument)).containsExactly(true, false, true, false).inOrder()
    // Le altre bit della cella non cambiano.
    assertThat(flags(ids[0]) and CellFlags.ON_PHONE).isEqualTo(CellFlags.ON_PHONE)
    // Ripetere non cambia niente: nessun cambiamento in piu'.
    val seq = catalog.changeSeq()
    assertThat(catalog.setDerivedFlags(listOf(ids[0], ids[2]), CellFlags.DOCUMENT)).isEqualTo(0)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
    // Si spegne con `on = false`.
    assertThat(catalog.setDerivedFlags(listOf(ids[0]), CellFlags.DOCUMENT, on = false)).isEqualTo(1)
    assertThat(isDocument(ids[0])).isFalse()
    assertThat(isDocument(ids[2])).isTrue()
  }

  @Test
  fun unCambiamentoDellIndicePerLottoNonUnoPerMedia() = runBlocking<Unit> {
    val ids = addPhones(25)
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    // 25 media a lotti di 10: 3 lotti, 3 cambiamenti (10 + 10 + 5), 3 numeri di sequenza.
    assertThat(catalog.setDerivedFlags(ids, CellFlags.DOCUMENT, batch = 10)).isEqualTo(25)
    assertThat(catalog.changeSeq()).isEqualTo(seq + 3)
    val first = changes.next()
    val second = changes.next()
    val third = changes.next()
    assertThat(listOf(first, second, third).map { it.ids.size }).containsExactly(10, 10, 5).inOrder()
    assertThat(listOf(first.seq, second.seq, third.seq)).containsExactly(seq + 1, seq + 2, seq + 3).inOrder()
    // Il bit non cambia l'ordine ne' la presenza: il cambiamento non e' strutturale.
    assertThat(listOf(first, second, third).none { it.structural }).isTrue()
    assertThat(first.ids + second.ids + third.ids).containsExactlyElementsIn(ids)
    assertThat(changes.none()).isTrue()

    // Un lotto che non cambia niente (tutti gia' accesi) non annuncia niente.
    assertThat(catalog.setDerivedFlags(ids, CellFlags.DOCUMENT, batch = 10)).isEqualTo(0)
    assertThat(changes.none()).isTrue()
    assertThat(catalog.changeSeq()).isEqualTo(seq + 3)
    changes.job.cancel()
  }

  @Test
  fun ilLottoDi500EIlPredefinito() = runBlocking<Unit> {
    assertThat(MediaCatalog.DERIVED_FLAGS_BATCH).isEqualTo(500)
    val ids = addPhones(3)
    val seq = catalog.changeSeq()
    catalog.setDerivedFlags(ids, CellFlags.DOCUMENT)
    // Meno di 500: un lotto, un cambiamento.
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
  }

  @Test
  fun ilBitSopravviveAiRicalcoliDeiDerivati() = runBlocking<Unit> {
    val ids = addPhones(2)
    catalog.setDerivedFlags(listOf(ids[0]), CellFlags.DOCUMENT)
    // Una scrittura che ricalcola i derivati (preferito, nascosto e ricomparso, nuova copia sul NAS): il bit resta.
    catalog.setPersonalFlags(ids[0], PersonalFlags(favorite = true))
    catalog.attachCopy(ids[0], catalog.versionOf(ids[0]), nasLocation("Backup/S25/IMG_1.jpg"), Db.stamp(smallFull(1)), null)
    catalog.setPersonalFlags(ids[0], PersonalFlags(favorite = true, hidden = true))
    catalog.setPersonalFlags(ids[0], PersonalFlags(favorite = true, hidden = false))
    val flags = flags(ids[0])
    assertThat(CellFlags.has(flags, CellFlags.DOCUMENT)).isTrue()
    assertThat(CellFlags.has(flags, CellFlags.FAVORITE)).isTrue()
    assertThat(CellFlags.has(flags, CellFlags.ON_NAS)).isTrue()
    // E il bit non si accende da solo sugli altri.
    assertThat(isDocument(ids[1])).isFalse()
  }

  @Test
  fun unMediaUnitoSiRisolveEUnoSparitoSiSalta() = runBlocking<Unit> {
    val ids = addPhones(1)
    // Un'identita' assorbita da un'unione: il suo id porta al sopravvissuto (media_alias).
    val absorbed = MediaId.random()
    db.openHelper.writableDatabase.execSQL("INSERT INTO media_alias (alias_id, media_id, merged_at) VALUES ('${absorbed.value}', '${ids[0].value}', 0)")
    assertThat(catalog.setDerivedFlags(listOf(absorbed, MediaId.random()), CellFlags.DOCUMENT)).isEqualTo(1)
    assertThat(isDocument(ids[0])).isTrue()
  }

  @Test
  fun soloIBitDerivatiDallIndiceAiSiScrivonoConQuestaOperazione() = runBlocking<Unit> {
    val ids = addPhones(1)
    listOf(0, CellFlags.FAVORITE, CellFlags.ON_PHONE, CellFlags.GROUP_MEMBER, CellFlags.DOCUMENT or CellFlags.FAVORITE).forEach { bad ->
      assertThat(runCatching { catalog.setDerivedFlags(ids, bad) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
    listOf(0, -1, MediaCatalog.MAX_DERIVED_FLAGS_BATCH + 1).forEach { bad ->
      assertThat(runCatching { catalog.setDerivedFlags(ids, CellFlags.DOCUMENT, batch = bad) }.exceptionOrNull())
        .isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThat(catalog.setDerivedFlags(emptyList(), CellFlags.DOCUMENT)).isEqualTo(0)
    assertThat(MediaCatalog.AI_DERIVED_MASK).isEqualTo(CellFlags.DOCUMENT)
  }
}
