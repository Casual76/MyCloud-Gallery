package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.personal.settings.CollectionsSettingsCodec
import dev.pampa.mycloudgallery.core.personal.settings.SyncedCollectionsSettings
import java.io.File
import org.junit.Test

/**
 * I codici delle sezioni delle Raccolte (Fase 7, P11, D33) stanno in due posti che non si vedono: `CollectionSections` di feature-library
 * (chi disegna la scheda) e `SyncedCollectionsSettings` di core-personal (chi li salva e li sincronizza). Un codice che l'uno scrive e
 * l'altro scarta farebbe sparire una sezione o perdere un ordine senza un errore, quindi questo test legge il sorgente di
 * feature-library e confronta: ogni codice e' scrivibile, l'elenco di cio' che non si nasconde e i tetti sono gli stessi.
 */
class CollectionsCodesMatchTest {

  private val source: String by lazy {
    val file = File("../feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/collections/CollectionSections.kt")
    assertWithMessage("Non trovo ${file.absolutePath}: il test gira dalla cartella del modulo").that(file.isFile).isTrue()
    file.readText()
  }

  private fun constants(): Map<String, String> =
    Regex("""const val (\w+): String = "([^"]+)"""").findAll(source).associate { it.groupValues[1] to it.groupValues[2] }

  private fun ints(): Map<String, Int> =
    Regex("""const val (\w+): Int = (\d+)""").findAll(source).associate { it.groupValues[1] to it.groupValues[2].toInt() }

  private fun listOfVal(name: String): List<String> {
    val body = Regex("""val $name: (?:List|Set)<String> = (?:listOf|setOf)\(([^)]*)\)""").find(source)?.groupValues?.get(1)
    assertWithMessage("Non trovo $name in CollectionSections.kt").that(body).isNotNull()
    return body!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }
  }

  @Test
  fun ogniCodiceDiSezioneEScrivibileNelleImpostazioni() {
    val codes = constants().filterKeys { it != "MAX_SECTIONS" }.values
    assertThat(codes).hasSize(11)
    for (code in codes) assertWithMessage(code).that(CollectionsSettingsCodec.isValidCode(code)).isTrue()
  }

  @Test
  fun l_OrdinePredefinitoElencaLeUndiciSezioniDelPianoEBastaNelleVociSincronizzate() {
    val byName = constants()
    val order = listOfVal("defaultOrder").map { name -> byName.getValue(name) }
    // Fase 9 (D30): "places" subito dopo "memories".
    assertThat(order).containsExactly(
      "albums", "smart", "trips", "people", "memories", "places", "nas", "phone", "safe", "utility", "hidden",
    ).inOrder()
    assertThat(CollectionsSettingsCodec.clean(order)).isEqualTo(order)
  }

  @Test
  fun leSezioniCheNonSiNascondonoEITettiSonoGliStessi() {
    val byName = constants()
    val never = listOfVal("neverHidden").map { byName.getValue(it) }.toSet()
    assertThat(never).isEqualTo(SyncedCollectionsSettings.DEFAULT_NEVER_HIDDEN)
    assertThat(ints().getValue("MAX_SECTIONS")).isEqualTo(SyncedCollectionsSettings.MAX_SECTIONS)
    assertThat(ints().getValue("MAX_CODE_LENGTH")).isEqualTo(SyncedCollectionsSettings.MAX_CODE_LENGTH)
  }
}
