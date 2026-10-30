package dev.pampa.mycloudgallery.notify

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Le notifiche dei ricordi non allargano niente (Fase 9, P10; docs/fase9-progetto.md D20, D35, D18; `verifyReleaseManifest`): **nessun allarme esatto, nessun permesso nuovo,
 * nessuna richiesta automatica del permesso**, e nessun testo dell'utente nel log. Sono controlli sui sorgenti dei file di P10 e sul manifest dell'app (i sorgenti si leggono
 * come testo, commenti esclusi); il controllo vero del manifest fuso di release resta `verifyReleaseManifest`.
 */
class MemoryNotifySourcesTest {

  private val main = File("src/main")

  /** I file di produzione di P10 in `:app` e in `core-memories` (`notify/`). */
  private val files: List<File> = listOf(
    "kotlin/dev/pampa/mycloudgallery/notify/MemoryNotifier.kt",
    "kotlin/dev/pampa/mycloudgallery/notify/NotifyPermissionFlow.kt",
    "kotlin/dev/pampa/mycloudgallery/work/MemoriesWork.kt",
    "kotlin/dev/pampa/mycloudgallery/MainActivity.kt",
  ).map { File(main, it) } + File("../core-memories/src/main/kotlin/dev/pampa/mycloudgallery/core/memories/notify").walkTopDown().filter { it.extension == "kt" }.toList()

  private fun code(file: File): String = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "")

  @Test
  fun iFileEsistono() {
    assertThat(files.size).isAtLeast(10)
    for (file in files) assertWithMessage(file.path).that(file.isFile).isTrue()
  }

  /** Nessun allarme (esatto o no), nessun ricevitore: il lavoro unico di WorkManager e' l'unico meccanismo (D20). */
  @Test
  fun nessunAllarmeNessunRicevitore() {
    val forbidden = listOf("AlarmManager", "setExact", "setAlarmClock", "setExactAndAllowWhileIdle", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "BroadcastReceiver", "registerReceiver")
    for (file in files) {
      val text = code(file)
      for (word in forbidden) assertWithMessage("${file.name} nomina $word").that(text).doesNotContain(word)
    }
  }

  /**
   * Nessuna richiesta automatica del permesso (E-NT1, D18): il dialogo di sistema non si lancia da nessun file di P10. Lo fa soltanto la funzione che l'azione di "Consenti"
   * riceve da fuori (`askSystem`), e quella la lega P13 con l'activity: qui non c'e' nessuna chiamata al sistema per chiedere.
   */
  @Test
  fun nessunaRichiestaDelPermessoDaiFileDiP10() {
    val forbidden = listOf("requestPermissions", "RequestPermission", "RequestMultiplePermissions", "shouldShowRequestPermissionRationale", "startActivity(", "startActivityForResult")
    for (file in files) {
      val text = code(file)
      for (word in forbidden) assertWithMessage("${file.name} contiene $word").that(text).doesNotContain(word)
    }
  }

  /** Il manifest non ha permessi per gli allarmi esatti (nemmeno nuovi permessi per le notifiche: `POST_NOTIFICATIONS` c'e' dal passo 9 della Fase 2). */
  @Test
  fun ilManifestNonHaPermessiNuoviPerLeNotifiche() {
    val manifests = listOf(File(main, "AndroidManifest.xml")) + File("..").listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("core-memories") }.map { File(it, "src/main/AndroidManifest.xml") }
    for (manifest in manifests.filter { it.isFile }) {
      val text = manifest.readText()
      for (word in listOf("SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "USE_FULL_SCREEN_INTENT", "RECEIVE_BOOT_COMPLETED", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")) {
        assertWithMessage("${manifest.path} dichiara $word").that(text).doesNotContain(word)
      }
    }
    assertThat(File(main, "AndroidManifest.xml").readText()).contains("android.permission.POST_NOTIFICATIONS")
    // Il tocco apre la MainActivity che c'e': nessun componente nuovo (la notifica e' un PendingIntent esplicito, non un filtro).
    assertThat(File(main, "AndroidManifest.xml").readText()).doesNotContain("OPEN_MEMORIES")
  }

  /** Nel log mai un titolo, un luogo, una data, un id o un nome: i log dei file di P10 dicono solo il tipo di un esito o un codice. */
  @Test
  fun nelLogSoloCodici() {
    val calls = Regex("GalleryLog\\.[a-z]\\([^)]*\\)\\s*\\{([^}]*)\\}")
    for (file in files) {
      for (match in calls.findAll(code(file))) {
        val message = match.groupValues[1]
        for (word in listOf("title", "label", "place", "memory.", "cover", "members", "person", ".value", "notice", "content")) {
          assertWithMessage("${file.name}: log '$message' nomina $word").that(message.lowercase()).doesNotContain(word)
        }
      }
    }
  }
}
