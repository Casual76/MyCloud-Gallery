package dev.pampa.mycloudgallery.core.search.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrReconciliationTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun unaRigaSqlPersaSiRicostruisceDalRegistroAncheDopoDueRiaperture() = runTest {
    val rows = InMemoryTextRows()
    val dir = temp.newFolder()
    LocalAiIndex(dir, rows).also { index ->
      index.open()
      index.saveText(key(1), ocrBody("scontrino farmacia"))
      index.close()
    }
    // Incidente della versione precedente: DELETE confermato, INSERT non riuscito, registro ancora integro.
    rows.remove(listOf(key(1)))
    repeat(2) {
      val reopened = LocalAiIndex(dir, rows)
      reopened.open()
      assertThat(reopened.savedFor(key(1), 1, null)).contains(AiClass.OCR)
      assertThat(rows.matches(listOf(listOf("farmacia"))).all).containsExactly(key(1))
      assertThat(reopened.localText(key(1), null)!!.lines.single().text).isEqualTo("scontrino farmacia")
      reopened.close()
    }
  }

  @Test fun unaSostituzioneSqlInterrottaSiRiconciliaColRegistroConfermato() = runTest {
    for (afterWrite in listOf(false, true)) {
      val rows = InMemoryTextRows()
      var fail = false
      val faulting = object : TextRowStore by rows {
        override suspend fun replace(records: List<AiTextRecord>) {
          if (fail && !afterWrite) throw IOException("Guasto prima della scrittura.")
          rows.replace(records)
          if (fail) throw IOException("Guasto dopo la scrittura.")
        }

        override suspend fun put(record: AiTextRecord): Boolean {
          if (fail && !afterWrite) throw IOException("Guasto prima della scrittura.")
          val result = rows.put(record)
          if (fail) throw IOException("Guasto dopo la scrittura.")
          return result
        }
      }
      val dir = temp.newFolder()
      val index = LocalAiIndex(dir, faulting)
      index.open()
      index.saveText(key(1), ocrBody("testo precedente"))
      fail = true
      assertThat(runCatching { index.saveText(key(1), ocrBody("testo sostituito")) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
      val expected = index.localText(key(1), null)!!.lines.single().text
      index.close()
      fail = false
      val reopened = LocalAiIndex(dir, faulting)
      reopened.open()
      assertThat(rows.rows.getValue(key(1)).text).isEqualTo(expected)
      assertThat(rows.matches(listOf(listOf(expected))).all).containsExactly(key(1))
      reopened.close()
    }
  }

  @Test fun primaEDopoIlCommitDelRegistroSqlSegueSempreLultimaCorniceIntera() = runTest {
    for (commitComplete in listOf(false, true)) {
      val rows = InMemoryTextRows()
      val dir = temp.newFolder()
      val index = LocalAiIndex(dir, rows)
      index.open()
      index.saveText(key(1), ocrBody("prima"))
      val log = File(dir, "ledger.log")
      val previous = log.length()
      index.saveText(key(1), ocrBody("dopo"))
      index.close()
      if (!commitComplete) {
        // Il processo ha lasciato una cornice incompleta: il replay deve tornare al corpo precedente.
        RandomAccessFile(log, "rw").use { it.setLength(previous + 9) }
      } else {
        // Commit durevole, SQL non ancora aggiornato.
        rows.remove(listOf(key(1)))
      }
      val reopened = LocalAiIndex(dir, rows)
      reopened.open()
      val expected = if (commitComplete) "dopo" else "prima"
      assertThat(rows.rows.getValue(key(1)).text).isEqualTo(expected)
      assertThat(reopened.localText(key(1), null)!!.lines.single().text).isEqualTo(expected)
      reopened.close()
    }
  }

  @Test fun unErroreDiRiconciliazioneNonCancellaIlRegistroDurevole() = runTest {
    val rows = InMemoryTextRows()
    val dir = temp.newFolder()
    val index = LocalAiIndex(dir, rows)
    index.open()
    index.saveText(key(1), ocrBody("testo da conservare"))
    index.close()
    rows.remove(listOf(key(1)))
    val log = File(dir, "ledger.log")
    val bytes = log.readBytes()
    val faulting = object : TextRowStore by rows {
      override suspend fun put(record: AiTextRecord): Boolean = throw IOException("SQL non disponibile.")
      override suspend fun replace(records: List<AiTextRecord>) = throw IOException("SQL non disponibile.")
    }
    val unavailable = LocalAiIndex(dir, faulting)
    try {
      assertThat(runCatching { unavailable.open() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    } finally {
      unavailable.close()
    }
    assertThat(log.readBytes().toList()).isEqualTo(bytes.toList())
    val recovered = LocalAiIndex(dir, rows)
    recovered.open()
    assertThat(rows.matches(listOf(listOf("conservare"))).all).containsExactly(key(1))
    recovered.close()
  }

  @Test fun savedForRiprendeUnInserimentoInterrottoSenzaRichiedereUnRiavvio() = runTest {
    val rows = InMemoryTextRows()
    var fail = true
    val faulting = object : TextRowStore by rows {
      override suspend fun replace(records: List<AiTextRecord>) {
        if (fail) throw IOException("SQL non disponibile.")
        rows.replace(records)
      }
    }
    val index = LocalAiIndex(temp.newFolder(), faulting)
    try {
      index.open()
      assertThat(runCatching { index.saveText(key(1), ocrBody("nuova ricevuta")) }.isFailure).isTrue()
      // Non dice "salvato" finche' SQL non e' di nuovo disponibile.
      assertThat(runCatching { index.savedFor(key(1), 1, null) }.isFailure).isTrue()
      fail = false
      assertThat(index.savedFor(key(1), 1, null)).contains(AiClass.OCR)
      assertThat(rows.matches(listOf(listOf("ricevuta"))).all).containsExactly(key(1))
      // Una consegna identica resta idempotente dopo la riconciliazione.
      index.saveText(key(1), ocrBody("nuova ricevuta"))
      assertThat(rows.rows).hasSize(1)
    } finally {
      index.close()
    }
  }

  @Test fun unaRimozioneInterrottaSiRipetePrimaDiCompattareENonRisorgeAllaRiapertura() = runTest {
    val rows = InMemoryTextRows()
    var fail = false
    val faulting = object : TextRowStore by rows {
      override suspend fun remove(keys: Collection<ContentKey>) {
        if (fail) throw IOException("Guasto nella rimozione.")
        rows.remove(keys)
      }
    }
    val dir = temp.newFolder()
    val index = LocalAiIndex(dir, faulting)
    index.open()
    index.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "altro", ocrBody("da ritirare"))))
    fail = true
    assertThat(runCatching { index.withdraw("comune", listOf(key(1) to AiClass.OCR)) }.isFailure).isTrue()
    assertThat(runCatching { index.maintain(force = true) }.isFailure).isTrue()
    index.close()
    fail = false
    val reopened = LocalAiIndex(dir, faulting)
    reopened.open()
    assertThat(rows.rows).isEmpty()
    assertThat(reopened.sizes.second).isEqualTo(0)
    reopened.maintain(force = true)
    reopened.close()
    val compacted = LocalAiIndex(dir, faulting)
    compacted.open()
    assertThat(rows.rows).isEmpty()
    compacted.close()
  }
}
