package dev.pampa.mycloudgallery.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SOL-1 con tutte le porte vere: registro su disco, CatalogTextRowStore, Room e FTS4. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CatalogOcrRecoveryTest {
  @get:Rule val temp = TemporaryFolder()

  private fun body(text: String) = OcrBody(OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS,
    listOf(OcrLine(ShortBox(0, 0, 100, 100), 0, listOf(WordSpan(0, 100)), text)))

  @Test fun unInsertFallitoNonRendeIlTestoIntrovabileNeppureDopoIlRiavvio() = runBlocking {
    val db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Application>(), name = null)
    val catalog = MediaCatalog(db, AccountId.of("prova"))
    val store = CatalogTextRowStore { catalog }
    val key = ContentKey("1".padStart(64, '0'))
    val dir = temp.newFolder()
    val index = LocalAiIndex(dir, store)
    try {
      index.open()
      index.saveText(key, body("farmacia"))
      db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER rg_a_fault BEFORE INSERT ON ai_text BEGIN SELECT RAISE(ABORT, 'guasto'); END")
      try {
        assertThat(runCatching { index.saveText(key, body("ricevuta")) }.isFailure).isTrue()
        // La sostituzione SQL e' atomica: durante il guasto resta ricercabile il testo precedente.
        assertThat(store.matches(listOf(listOf("farmacia"))).all).containsExactly(key)
      } finally {
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER rg_a_fault")
      }
      index.close()
      val reopened = LocalAiIndex(dir, store)
      try {
        reopened.open()
        val durable = reopened.localText(key, null)!!.lines.single().text
        assertThat(store.matches(listOf(listOf(durable))).all).containsExactly(key)
        assertThat(catalog.aiIndex.text(key)!!.text).isEqualTo(durable)
      } finally {
        reopened.close()
      }
    } finally {
      index.close()
      db.close()
    }
  }
}
