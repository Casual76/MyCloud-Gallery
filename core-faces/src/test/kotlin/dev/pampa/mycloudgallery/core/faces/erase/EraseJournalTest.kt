package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

/**
 * Il diario `faces-erase` (Fase 8, P8; §2.8): ogni campo va e torna; un diario rovinato, di un'altra versione, con un campo in piu' o in meno non si
 * indovina (illeggibile, e la cancellazione si ricostruisce dal segno locale); il passo si legge dai segni senza buchi; il file si scrive in modo
 * atomico e si toglie.
 */
class EraseJournalTest {

  private val dir: File = Files.createTempDirectory("diario-cancellazione").toFile()
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 3 })
  private val g0 = FaceGeneration.fromBytes(ByteArray(16) { 4 })

  @After
  fun clean() {
    dir.deleteRecursively()
  }

  private val full = EraseJournal(
    generation = g, earlier = listOf(g0), choice = PeopleAlbumsChoice.DROP_RULE, digest = -1234567890123L, localOnly = false, stopped = true,
    albums = ErasePart.SKIPPED, history = ErasePart.DONE, setting = true, local = true, announced = true, nasDone = true, verified = true,
    confirmed = true, done = true, recycleBin = true, offAtNas = 1_800_000_000_000L, doneAtNas = 1_800_000_100_000L,
  )

  @Test
  fun ogniCampoVaETorna() {
    for (journal in listOf(full, EraseJournal(g, choice = PeopleAlbumsChoice.KEEP_PHOTOS, digest = 0, localOnly = true))) {
      assertThat(EraseJournalCodec.decode(EraseJournalCodec.encode(journal))).isEqualTo(journal)
    }
  }

  @Test
  fun unDiarioStranoNonSiIndovina() {
    val good = EraseJournalCodec.encode(full)
    val broken = listOf(
      "",
      good.replace("mcg-faces-erase 1", "mcg-faces-erase 2"),
      good.replace("done=1", "done=si"),
      good.replace("albums=skipped", "albums=forse"),
      good.replace("choice=drop_rule", "choice=altro"),
      good.replace("generation=${g.base32}", "generation=x"),
      good.lines().filterNot { it.startsWith("verified=") }.joinToString("\n"),
      good + "campo=nuovo\n",
      good + "done=1\n",
      good.replace("digest=", "digest=a"),
    )
    for (text in broken) assertThat(EraseJournalCodec.decode(text)).isNull()
  }

  @Test
  fun ilPassoESenzaBuchi() {
    val start = EraseJournal(g, choice = PeopleAlbumsChoice.KEEP_PHOTOS, digest = 1, localOnly = false)
    assertThat(start.step).isEqualTo(EraseStep.STARTED)
    assertThat(start.copy(stopped = true, albums = ErasePart.DONE).step).isEqualTo(EraseStep.ALBUMS)
    // Il NAS fatto senza la parte locale non conta: il passo resta l'ultimo senza buchi.
    assertThat(start.copy(stopped = true, nasDone = true).step).isEqualTo(EraseStep.STOPPED)
    val local = start.copy(stopped = true, albums = ErasePart.DONE, history = ErasePart.SKIPPED, setting = true, local = true)
    assertThat(local.step).isEqualTo(EraseStep.LOCAL_DONE)
    assertThat(local.copy(localOnly = true, done = true).step).isEqualTo(EraseStep.DONE)
    assertThat(full.step).isEqualTo(EraseStep.DONE)
    assertThat(full.erased).containsExactly(g, g0).inOrder()
    assertThat(full.toString()).doesNotContain(g.base32)
  }

  @Test
  fun ilFileSiScriveSiRileggeESiToglie() {
    val store = FileEraseJournalStore(File(dir, FileEraseJournalStore.FILE))
    assertThat(store.load()).isEqualTo(EraseJournalRead.Absent)
    store.save(full)
    assertThat((store.load() as EraseJournalRead.Present).journal).isEqualTo(full)
    assertThat(File(dir, FileEraseJournalStore.FILE + ".tmp").exists()).isFalse()
    File(dir, FileEraseJournalStore.FILE).writeText("rovinato")
    assertThat(store.load()).isEqualTo(EraseJournalRead.Unreadable)
    assertThat(store.clear()).isTrue()
    assertThat(store.load()).isEqualTo(EraseJournalRead.Absent)
  }
}
