package dev.pampa.mycloudgallery.core.nasmedia

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasmedia.scan.FolderOrder
import dev.pampa.mycloudgallery.core.nasmedia.scan.ScanFolder
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import org.junit.Test

/** L'ordine delle sottocartelle (proposta 11): le piu' recenti per prime. Puro, senza NAS. */
class FolderOrderTest {

  private val now = 1_791_936_000_000L // 2026-10-14
  private val day = 86_400_000L

  private fun folder(name: String, modifiedAt: Long) = ScanFolder(name, SharePath.of(name), null, modifiedAt)

  private fun order(vararg folders: ScanFolder, ceiling: Long = now) = FolderOrder.order(folders.toList(), ceiling).map { it.name }

  @Test
  fun conLeDateAffidabiliVannoPerDataDecrescente() {
    assertThat(order(folder("A", now - 3 * day), folder("B", now - 1 * day), folder("C", now - 2 * day)))
      .containsExactly("B", "C", "A").inOrder()
  }

  @Test
  fun aParitaDiDataVaPerNomeDecrescenteConAnniEMesiNumerici() {
    val same = now - 5 * day
    assertThat(order(folder("Alfa", same), folder("Beta", same), folder("Beta2", same), folder("Zeta", now - 9 * day)))
      .containsExactly("Beta2", "Beta", "Alfa", "Zeta").inOrder()
    assertThat(order(folder("2009", same), folder("2026", same), folder("2010", same), folder("Altro", now - 40 * day)))
      .containsExactly("2026", "2010", "2009", "Altro").inOrder()
  }

  @Test
  fun seLeDateNonDiconoNienteVaSoloPerNome() {
    val names = listOf("2009", "2026", "2010")
    // Tutte uguali.
    assertThat(order(*names.map { folder(it, now - day) }.toTypedArray())).containsExactly("2026", "2010", "2009").inOrder()
    // Tutte perse (zero) o copiate da un vecchio disco (1979).
    assertThat(order(*names.map { folder(it, 0) }.toTypedArray())).containsExactly("2026", "2010", "2009").inOrder()
    assertThat(order(*names.map { folder(it, 296_870_400_000L) }.toTypedArray())).containsExactly("2026", "2010", "2009").inOrder()
    // Una sola cartella ha una data vera: non basta per ordinare.
    assertThat(order(folder("2009", now - day), folder("2026", 0), folder("2010", 0))).containsExactly("2026", "2010", "2009").inOrder()
  }

  @Test
  fun lePocheCartelleSenzaUnaDataVeraVannoInCodaDopoQuelleDatate() {
    // `Vecchie` ha un 1979 copiato da un vecchio disco: non butta via le date delle altre, ma e' la meno affidabile.
    assertThat(order(folder("Vecchie", 296_870_400_000L), folder("Camera", now - 3 * day), folder("Download", now - 1 * day), folder("Zeta", 0)))
      .containsExactly("Download", "Camera", "Zeta", "Vecchie").inOrder()
  }

  @Test
  fun unaDataNelFuturoDelNasContaComeAdesso() {
    assertThat(order(folder("2009", now - day), folder("2026", now + 30 * day), folder("2010", now - 2 * day)))
      .containsExactly("2026", "2009", "2010").inOrder()
  }

  @Test
  fun annoEMeseSonoInOrdineNumericoDecrescenteAnchePerNumeriDiLunghezzaDiversa() {
    val same = now - day
    assertThat(order(*listOf("01", "12", "09", "10", "02").map { folder(it, same) }.toTypedArray()))
      .containsExactly("12", "10", "09", "02", "01").inOrder()
    assertThat(order(folder("07", same), folder("2024", same), folder("99", same))).containsExactly("2024", "99", "07").inOrder()
  }

  @Test
  fun gliAnniEIMesiVengonoPrimaDegliAltriNomi() {
    val same = now - day
    assertThat(order(folder("Altro", same), folder("2024", same), folder("Zeta", same), folder("07", same)))
      .containsExactly("2024", "07", "Zeta", "Altro").inOrder()
    assertThat(order(folder("2024-05", same), folder("2023", same), folder("2024", same))).containsExactly("2024", "2023", "2024-05").inOrder()
  }

  @Test
  fun unaCartellaToccataDopoLInizioDelGiroNonSaltaInTestaAMetaStrada() {
    val epoch = now - 1_000
    // Entrambe modificate dopo l'inizio del giro: alla data limite, uguali: decide il nome. La terza, vecchia, resta dietro.
    assertThat(order(folder("A", epoch + 5_000), folder("B", epoch + 1_000), folder("C", now - 30 * day), ceiling = epoch))
      .containsExactly("B", "A", "C").inOrder()
  }

  @Test
  fun lOrdineEStabileEUgualeComunqueArrivinoLeCartelle() {
    val folders = (1..40).map { folder("f$it", now - (it % 7) * day) } + listOf(folder("2025", now - 8 * day), folder("2026", now - 8 * day))
    val reference = FolderOrder.order(folders, now).map { it.name }
    repeat(20) { seed ->
      assertThat(FolderOrder.order(folders.shuffled(Random(seed)), now).map { it.name }).containsExactlyElementsIn(reference).inOrder()
    }
  }

  @Test
  fun unaSolaCartellaOGliInsiemiVuotiNonSiToccano() {
    assertThat(FolderOrder.order(emptyList(), now)).isEmpty()
    assertThat(order(folder("X", 0))).containsExactly("X")
  }
}
