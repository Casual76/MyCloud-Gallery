package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F20 (revisione 4B): in debug, e solo con il marcatore scritto da adb, il cestino del NAS tiene le voci qualche minuto invece di 30
 * giorni, cosi' la pulizia si prova sul campo in pochi minuti. In release il gancio non esiste (tools/check.ps1 lo cerca nel dex).
 */
@RunWith(AndroidJUnit4::class)
class DebugTrashRetentionTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val marker: File get() = File(checkNotNull(context.getExternalFilesDir(null)), DebugTrashRetention.MARKER)

  @After
  fun clean() {
    marker.delete()
  }

  @Test
  fun senzaMarcatoreLaDebugTieneITrentaGiorni() {
    marker.delete()
    assertThat(DeveloperTools.trashRetentionMillis(context)).isNull()
  }

  @Test
  fun conIlMarcatoreIlCestinoDuraQueiMinutiMaiMenoDiUnoNePiuDiTrentaGiorni() {
    marker.parentFile?.mkdirs()
    marker.writeText("5\n")
    assertThat(DeveloperTools.trashRetentionMillis(context)).isEqualTo(5 * 60_000L)
    marker.writeText("0")
    assertThat(DeveloperTools.trashRetentionMillis(context)).isEqualTo(60_000L)
    marker.writeText("99999999")
    assertThat(DeveloperTools.trashRetentionMillis(context)).isEqualTo(30L * 24 * 60 * 60 * 1000)
    marker.writeText("non un numero")
    assertThat(DeveloperTools.trashRetentionMillis(context)).isNull()
  }
}
