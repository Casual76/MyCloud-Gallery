package dev.pampa.mycloudgallery.di

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReport
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3, R6 e I3: la famiglia "pronta" e la sincronizzazione ferma delle varianti di misura
 * arrivano da un binding Hilt esplicito, mai da `ServiceLoader`; senza quel binding (debug, release) l'app e' quella vera;
 * il nome del database segue il fatto che le hooks ci siano, non il tipo di build.
 */
class MeasurementHooksTest {

  private fun family(state: SetupState) = object : FamilySetupRepository {
    override val state: StateFlow<SetupState> = MutableStateFlow(state)
  }

  private val real = family(SetupState.NotConfigured)
  private val debugShortcut = family(SetupState.Ready(admin = false))

  private class Hooks(override val family: FamilySetupRepository, override val databaseName: String = "gallery-bench.db") : MeasurementHooks {
    override val phoneSync: PhoneLibrarySync = object : PhoneLibrarySync {
      override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle(0L))

      override suspend fun syncNow(reason: SyncReason): SyncReport = SyncReport()

      override fun attach(scope: CoroutineScope) = Unit

      override fun detach() = Unit
    }
  }

  @Test
  fun senzaHooksLaFamigliaEQuellaVera() {
    assertThat(chooseFamilySetup(hooks = null, debugOverride = null, real = real)).isSameInstanceAs(real)
  }

  @Test
  fun laScorciatoiaDiDebugVinceSoloSullaFamigliaVera() {
    assertThat(chooseFamilySetup(hooks = null, debugOverride = debugShortcut, real = real)).isSameInstanceAs(debugShortcut)
  }

  @Test
  fun leHooksDiMisuraVincono() {
    val bench = family(SetupState.Ready(admin = false))
    val hooks = Hooks(bench)

    assertThat(chooseFamilySetup(hooks, debugOverride = debugShortcut, real = real)).isSameInstanceAs(bench)
    assertThat(chooseFamilySetup(hooks, debugOverride = null, real = real)).isSameInstanceAs(bench)
  }

  @Test
  fun ilDatabaseDelDatasetSinteticoSiSceglieDalleHooksNonDalTipoDiBuild() {
    // Senza hooks: il catalogo vero. Con le hooks (benchmarkRelease e nonMinifiedRelease): il suo file, mai gallery.db (I3).
    assertThat(GalleryModule.databaseName(null)).isEqualTo(GalleryDatabase.NAME)
    assertThat(GalleryModule.databaseName(Hooks(real, databaseName = "gallery-bench.db"))).isEqualTo("gallery-bench.db")
    assertThat(GalleryModule.databaseName(Hooks(real))).isNotEqualTo(GalleryDatabase.NAME)
  }

  /**
   * R6: niente `ServiceLoader` nei sorgenti principali ne' file `META-INF/services` fra le risorse: la release non ha un
   * canale dichiarativo per sostituire la famiglia o la sincronizzazione. Il controllo sull'APK vero e' in
   * `tools/check.ps1`.
   */
  @Test
  fun inSrcMainNonCeServiceLoaderNeFileDiServizio() {
    val main = File("src/main")
    assertThat(main.isDirectory).isTrue()
    assertThat(File(main, "resources/META-INF/services").exists()).isFalse()
    val offenders = main.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filter { file -> codeOnly(file.readText()).contains("ServiceLoader") }
      .map { it.relativeTo(main).path.replace(File.separatorChar, '/') }
      .toList()
    assertThat(offenders).isEmpty()
  }

  /** Il codice senza commenti (KDoc compresi), che possono spiegare perche' `ServiceLoader` non c'e'. */
  private fun codeOnly(source: String): String =
    source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//.*"""), "")
}
