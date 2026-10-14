package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFeatures
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexBuilder
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import java.util.UUID
import org.junit.Test

/**
 * La Libreria unificata (Fase 4, P7, D18): un riferimento ai byte per cella (telefono, altrimenti NAS), i flag di
 * presenza e i badge che ne derivano, la chiave di versione della miniatura. Sulla libreria sintetica **mista** dei
 * budget del §5.6 (60% solo-NAS).
 */
class NasIndexTest {

  private val library = SyntheticLibrary(seed = 3, count = 20_000, mix = NasMix.BUDGET)
  private val index: ColumnarTimelineSnapshot = library.index()

  @Test
  fun iBadgeDiPresenzaSonoAcceso() {
    assertThat(LibraryFeatures.presenceBadges).isTrue()
  }

  @Test
  fun laLibreriaMistaHaLeProporzioniDelBudget() {
    val counts = HashMap<PresenceBadge?, Int>()
    for (i in 0 until index.size) counts.merge(index.presenceAt(i), 1, Int::plus)
    val total = index.size.toDouble()
    val nasOnly = counts.getValue(PresenceBadge.NAS_ONLY)
    val both = counts.getValue(PresenceBadge.BOTH) + counts.getValue(PresenceBadge.SAFE) + counts.getValue(PresenceBadge.TO_VERIFY)
    // 60% solo-NAS; il 40% sul telefono, di cui un quarto anche sul NAS (10% del totale), una parte "al sicuro".
    assertThat(nasOnly / total).isWithin(0.015).of(0.60)
    assertThat(counts.getValue(PresenceBadge.PHONE_ONLY) / total).isWithin(0.015).of(0.30)
    assertThat(both / total).isWithin(0.015).of(0.10)
    assertThat(counts.getValue(PresenceBadge.SAFE)).isGreaterThan(0)
    assertThat(counts.getValue(PresenceBadge.TO_VERIFY)).isGreaterThan(0)
    // Ogni cella ha una presenza: mai "nessun badge" in una libreria mista.
    assertThat(counts[null] ?: 0).isEqualTo(0)
    assertThat(index.nasCount).isEqualTo(nasOnly + both)
  }

  @Test
  fun unSoloRiferimentoPerCella_ilTelefonoSeCe_altrimentiIlNas() {
    var nasOnly = 0
    for (i in 0 until index.size) {
      val phone = index.refAt(i)
      val nas = index.nasRefAt(i)
      val onPhone = index.hasFlag(i, CellFlags.ON_PHONE)
      if (onPhone) {
        // D18: per un media su telefono e NAS la griglia usa la miniatura del telefono.
        assertThat(phone).isEqualTo(library.phoneRefAt(i))
        assertThat(nas).isNull()
        assertThat(index.thumbRefAt(i)).isEqualTo(phone)
      } else {
        nasOnly++
        assertThat(phone).isNull()
        assertThat(nas).isEqualTo(library.nasRefAt(i))
        assertThat(nas!!.locationId).isEqualTo(1L + i)
        assertThat(index.thumbRefAt(i)).isEqualTo(nas)
      }
    }
    assertThat(nasOnly.toDouble() / index.size).isWithin(0.015).of(0.60)
    // Il fake (`InMemoryTimelineSnapshot`) da' gli stessi riferimenti al NAS dell'indice vero.
    for (i in intArrayOf(0, 1, 2, 777, 19_999)) assertThat(library.snapshot.nasRefAt(i)).isEqualTo(index.nasRefAt(i))
  }

  @Test
  fun senzaNasIlSoloTelefonoNonFaRumore() {
    // D15: finche' nessuna cella e' sul NAS, "solo telefono" sarebbe su ogni cella. L'indice non lo da'.
    val phoneOnly = SyntheticLibrary(seed = 3, count = 3_000, mix = NasMix(nasOnlyPercent = 0, bothPercentOfPhone = 0)).index()
    assertThat(phoneOnly.nasCount).isEqualTo(0)
    for (i in 0 until phoneOnly.size) {
      assertThat(phoneOnly.hasFlag(i, CellFlags.ON_PHONE)).isTrue()
      assertThat(phoneOnly.presenceAt(i)).isNull()
    }
    // Basta una cella sul NAS e il badge diventa un'informazione vera: tutte le altre sono "solo telefono".
    val b = TimelineIndexBuilder()
    b.add(1, 1, 300, 20240101, MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.ON_PHONE, 1000, 0, 0, null)
    b.add(1, 2, 200, 20240101, MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.ON_PHONE or CellFlags.ON_NAS, 1000, 0, 0, null)
    b.add(1, 3, 100, 20240101, MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.ON_NAS, 1000, 0, 0, null)
    val some = b.build(1)
    assertThat((0 until 3).map { some.presenceAt(it) }).containsExactly(PresenceBadge.PHONE_ONLY, PresenceBadge.BOTH, PresenceBadge.NAS_ONLY).inOrder()
    // I fake che non conoscono il NAS (Fase 3) danno il badge dei soli flag.
    val fake: TimelineSnapshot = SyntheticLibrary(seed = 3, count = 50).snapshot
    assertThat(fake.presenceAt(0)).isNull()
    assertThat(fake.nasRefAt(0)).isNull()
  }

  @Test
  fun alSicuroEDaVerificareHannoLaPrecedenzaSuiBadgeDiPresenza() {
    val flags = CellFlags.ON_PHONE or CellFlags.ON_NAS
    assertThat(PresenceBadge.of(flags or CellFlags.SAFE)).isEqualTo(PresenceBadge.SAFE)
    assertThat(PresenceBadge.of(flags or CellFlags.TO_VERIFY)).isEqualTo(PresenceBadge.TO_VERIFY)
    assertThat(PresenceBadge.of(flags)).isEqualTo(PresenceBadge.BOTH)
    assertThat(PresenceBadge.of(0)).isNull()
    for (i in 0 until index.size) assertThat(index.presenceAt(i)).isEqualTo(PresenceBadge.of(index.flagsAt(i)))
  }

  @Test
  fun laChiaveDiVersioneDelNasSegueIlContenutoNonLaPosizione() {
    fun keyFor(location: Long, contentKey: Long, aspect: Int = 1000, thumb: ByteArray? = null): Long {
      val b = TimelineIndexBuilder()
      b.add(
        1, 1, 100, 20240101, MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.ON_NAS, aspect, 0, 0, thumb,
        nasRef = MediaRef.Nas(location, contentKey, "image/jpeg"),
      )
      return b.build(1).versionKeyAt(0)
    }
    val base = keyFor(location = 10, contentKey = 0x1234)
    // Una rinomina fatta dal PC puo' cambiare la posizione: la miniatura non si rifa' (D4).
    assertThat(keyFor(location = 99, contentKey = 0x1234)).isEqualTo(base)
    // Un contenuto diverso, un segnaposto nuovo o un'altra proporzione: chiave nuova, la cella si aggiorna.
    assertThat(keyFor(location = 10, contentKey = 0x1235)).isNotEqualTo(base)
    assertThat(keyFor(location = 10, contentKey = 0x1234, aspect = 750)).isNotEqualTo(base)
    assertThat(keyFor(location = 10, contentKey = 0x1234, thumb = ByteArray(25) { it.toByte() })).isNotEqualTo(base)
    // Quella del telefono e' la formula della Fase 3: l'arrivo di una copia sul NAS non sposta la chiave.
    val phone = MediaRef.Phone("external_primary", 55, "image/jpeg")
    fun phoneKey(nas: MediaRef.Nas?): Long {
      val b = TimelineIndexBuilder()
      b.add(1, 1, 100, 20240101, MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.ON_PHONE, 1000, 0, 0, null, ref = phone, nasRef = nas)
      return b.build(1).versionKeyAt(0)
    }
    assertThat(phoneKey(MediaRef.Nas(7, 8, "image/jpeg"))).isEqualTo(phoneKey(null))
  }

  @Test
  fun conIlTelefonoElNasInsiemeVaIlTelefono_eSenzaIlTelefonoTornaIlNas() {
    val nas = MediaRef.Nas(locationId = 42, contentKey = -5L, mime = "image/heic")
    val phone = MediaRef.Phone("external_primary", 9, "image/jpeg")
    val both = UUID(1, 1).toString()
    val rows = listOf(
      indexRow(both, sortTime = 200, flags = CellFlags.ON_PHONE or CellFlags.ON_NAS),
      indexRow(UUID(1, 2).toString(), sortTime = 100, flags = CellFlags.ON_PHONE or CellFlags.ON_NAS),
    )
    val s = TimelineIndexReader.fromRows(
      rows, 3, emptyMap(), refs = mapOf(both to phone),
      nasRefs = mapOf(both to nas, rows[1].mediaId to nas),
    )
    // Il primo ha il telefono (vale lui), il secondo ha solo il NAS (E-P22: la copia del telefono non si legge adesso).
    assertThat(s.thumbRefAt(0)).isEqualTo(phone)
    assertThat(s.nasRefAt(0)).isNull()
    assertThat(s.thumbRefAt(1)).isEqualTo(nas)
    assertThat(s.refAt(1)).isNull()
    // withRefs rifa' i riferimenti: stesso criterio, e chi non e' in nessuna delle due mappe resta senza.
    val redone = TimelinePatcher.withRefs(s, refs = mapOf(0 to phone), nasRefs = mapOf(0 to nas, 1 to nas))
    assertThat(redone.thumbRefAt(0)).isEqualTo(phone)
    assertThat(redone.thumbRefAt(1)).isEqualTo(nas)
    assertThat(TimelinePatcher.withRefs(s, emptyMap()).thumbRefAt(1)).isNull()
  }
}
