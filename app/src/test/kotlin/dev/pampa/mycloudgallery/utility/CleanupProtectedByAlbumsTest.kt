package dev.pampa.mycloudgallery.utility

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.ProtectedFiles
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Doppioni (identici e simili, anche «Ovunque») e i file di un album condiviso in cui l'utente non puo' eliminare (Fase 7, correzioni F, risposta dell'utente del
 * 27/10 su R3 e L152). L'utente ha voluto **tenere l'azione** e, al tocco, sapere che cosa non e' riuscito e perche'. Qui si prova l'adattatore di `:app`
 * ([OperationsCleanupNas]) sopra il NAS finto di `core-nasops` con la stessa porta `ProtectedFiles` del vero:
 *  - **la protezione regge**: una copia di un album condiviso non va mai nel cestino, qualunque sia la strada (l'elenco dei Doppioni e' una richiesta di
 *    `Trash` come un'altra, e il piano la lascia fuori: il pacchetto B ha messo il controllo nel punto per cui passa ogni scrittura);
 *  - **il messaggio arriva**: l'adattatore restituisce quali copie il piano ha lasciato fuori e quante sono partite, cosi' la pagina dice «avviata per 3, non
 *    toccate 2» anche per una selezione mista, e «non e' riuscito, perche' album condiviso» quando e' tutto protetto.
 * Il percorso con il grafo vero (album, replica, livelli) lo prova `RevisioneBCondivisioneAppTest.r3...`.
 */
class CleanupProtectedByAlbumsTest {

  private val kit = NasOpsTestKit(
    protectedFiles = ProtectedFiles { _, items -> items.filter { it.path.value.startsWith("Album condivisi/") }.map { it.path }.toSet() },
  )
  private val port get() = OperationsCleanupNas(kit.operations) { kit.clocks.monotonicMs() }

  private fun copyAt(share: String, path: String, bytes: ByteArray, area: CopyArea): NasTrashRef {
    val stamp = kit.putFile(share, path, bytes)
    return NasTrashRef(share, path, stamp.size, stamp.modifiedAtMillis, stamp.fileId, area)
  }

  private fun free(name: String, n: Int) = copyAt(NasOpsTestKit.PRIVATE_SHARE, "Backup/Pixel 9/2026/10/$name", ByteArray(500 + n) { n.toByte() }, CopyArea.MY_PRIVATE)

  private fun inAlbum(name: String, n: Int) = copyAt(NasOpsTestKit.COMMON_SHARE, "Album condivisi/Guardano/$name", ByteArray(700 + n) { n.toByte() }, CopyArea.COMMON)

  private fun inTrash(): Int {
    runBlocking { kit.trash.refresh() }
    return (kit.trash.items.value as NasTrashState.Ready).items.size
  }

  @Test
  fun unaSelezioneMista_LeCopieLiberePartono_QuelleDiUnAlbumCondivisoRestanoEDiconoQuante() = runBlocking {
    val copies = listOf(free("IMG_1.jpg", 1), free("IMG_2.jpg", 2), free("IMG_3.jpg", 3), inAlbum("IMG_4.jpg", 4), inAlbum("IMG_5.jpg", 5))

    val result = port.trashReporting(copies, TestTaps.single())

    // «3 partite, 2 no perche'...»: l'esito, quante partono e quali sono rimaste (le due dell'album).
    assertThat(result.outcome).isEqualTo(NasTrashOutcome.STARTED)
    assertThat(result.started).isEqualTo(3)
    assertThat(result.protectedLeftOut.map { it.path }).containsExactly("Album condivisi/Guardano/IMG_4.jpg", "Album condivisi/Guardano/IMG_5.jpg")
    for (free in copies.take(3)) assertThat(kit.exists(free.share, free.path)).isFalse()
    for (kept in copies.drop(3)) assertThat(kit.exists(kept.share, kept.path)).isTrue()
    assertThat(inTrash()).isEqualTo(3)
  }

  @Test
  fun seTutteLeCopieSonoDiUnAlbumCondiviso_NienteParte_ENonSiTocca() = runBlocking {
    val copies = listOf(inAlbum("IMG_6.jpg", 6), inAlbum("IMG_7.jpg", 7))

    val result = port.trashReporting(copies, TestTaps.single())

    assertThat(result.outcome).isEqualTo(NasTrashOutcome.REJECTED)
    assertThat(result.started).isEqualTo(0)
    assertThat(result.protectedLeftOut).hasSize(2)
    for (kept in copies) assertThat(kit.exists(kept.share, kept.path)).isTrue()
    assertThat(inTrash()).isEqualTo(0)
    assertThat(kit.operations.operations.value).isEmpty()
  }

  @Test
  fun laVecchiaTrashHaLoStessoEffettoELoStessoEsito() = runBlocking {
    val copies = listOf(free("IMG_8.jpg", 8), inAlbum("IMG_9.jpg", 9))

    // `trash` (senza il resoconto) ha lo stesso effetto e lo stesso esito: la protezione non dipende da chi chiama.
    val outcome = port.trash(copies, TestTaps.single())

    assertThat(outcome).isEqualTo(NasTrashOutcome.STARTED)
    assertThat(kit.exists(copies[0].share, copies[0].path)).isFalse()
    assertThat(kit.exists(copies[1].share, copies[1].path)).isTrue()
  }

  @Test
  fun senzaFileProtettiNienteELasciatoFuori() = runBlocking {
    val copies = listOf(free("IMG_10.jpg", 10), free("IMG_11.jpg", 11))

    val result = port.trashReporting(copies, TestTaps.single())

    assertThat(result.outcome).isEqualTo(NasTrashOutcome.STARTED)
    assertThat(result.started).isEqualTo(2)
    assertThat(result.protectedLeftOut).isEmpty()
  }

  @Test
  fun seLaPortaDegliAlbumNonRisponde_TuttoEProtetto_SiFallisceChiuso() = runBlocking {
    val failing = NasOpsTestKit(protectedFiles = ProtectedFiles { _, _ -> error("replica non raggiungibile") })
    val stamp = failing.putFile(NasOpsTestKit.PRIVATE_SHARE, "Backup/IMG_12.jpg", ByteArray(600) { 12 })
    val copy = NasTrashRef(NasOpsTestKit.PRIVATE_SHARE, "Backup/IMG_12.jpg", stamp.size, stamp.modifiedAtMillis, stamp.fileId, CopyArea.MY_PRIVATE)

    val result = OperationsCleanupNas(failing.operations) { failing.clocks.monotonicMs() }.trashReporting(listOf(copy), TestTaps.single())

    // Un errore di chi risponde vale «protetto» (R3): non parte niente, e la pagina dice il perche'.
    assertThat(result.outcome).isEqualTo(NasTrashOutcome.REJECTED)
    assertThat(result.protectedLeftOut).hasSize(1)
    assertThat(failing.exists(copy.share, copy.path)).isTrue()
  }
}
