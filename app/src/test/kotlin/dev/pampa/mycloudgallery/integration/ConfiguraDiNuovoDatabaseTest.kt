package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.di.GalleryModule
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppLocalDataEraser
import dev.pampa.mycloudgallery.graph.AppPreviousAccountData
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.root.ProcessRestart
import java.io.File
import java.util.Optional
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Il percorso di produzione apre Room anche prima della famiglia pronta: il reset deve chiuderlo prima di cancellare. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConfiguraDiNuovoDatabaseTest {
  @get:Rule val temp = TemporaryFolder()
  private val app: Application = ApplicationProvider.getApplicationContext()
  private var device: TestDevice? = null
  private var gallery: GalleryGraph? = null
  private var otherDatabase: GalleryDatabase? = null

  @After
  fun tearDown() {
    try {
      device?.close()
    } finally {
      gallery?.database?.close()
      otherDatabase?.close()
    }
  }

  @Test
  fun ilBackupApreIlCatalogoPrimaDellaFamigliaEIlResetLoChiudePrimaDiCancellare() = runBlocking<Unit> {
    val time = TestTime()
    var restarts = 0
    val phone = TestDevice(
      app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time)),
      restart = ProcessRestart { restarts++ },
    ).also { device = it }
    val graph = phone.graph
    graph.start()
    graph.awaitSetup { it == SetupState.NotConfigured }
    // Lo stesso montaggio di MyCloudGalleryApp: NAS e backup veri, nessun accesso diretto al database dal test.
    val g = GalleryModule.gallery(phone.context, graph, graph.setup, Optional.empty()).also { gallery = it }
    until("il ricalcolo iniziale del backup apre Room") { g.database.isOpen }
    assertThat(graph.galleryMayRun()).isFalse()
    assertThat(g.started.value).isFalse()
    assertThat(g.coreIfReady()).isNull()
    g.database.openHelper.writableDatabase.execSQL("INSERT INTO search_history(text, chips) VALUES ('vecchio account', '')")
    val other = GalleryDatabase.build(phone.context, "altri-lavori.db").also { otherDatabase = it }
    other.openHelper.writableDatabase.execSQL("INSERT INTO search_history(text, chips) VALUES ('altro database', '')")

    var deletions = 0
    val erasingContext = object : ContextWrapper(phone.context) {
      override fun deleteDatabase(name: String): Boolean {
        if (name == GalleryDatabase.NAME) {
          // Su Windows un file aperto non si cancella: si prova l'ordine, senza dipendere dal sintomo Android.
          assertWithMessage("Room aperto al momento della cancellazione").that(g.database.isOpen).isFalse()
          deletions++
        }
        return super.deleteDatabase(name)
      }
    }
    val previous = AppPreviousAccountData(AppLocalDataEraser(erasingContext, graph), graph)

    assertThat(previous.erase()).isTrue()

    assertThat(deletions).isEqualTo(1)
    assertThat(g.database.isOpen).isFalse()
    assertThat(graph.galleryMayRun()).isFalse()
    assertThat(other.isOpen).isTrue()
    other.openHelper.readableDatabase.query("SELECT text FROM search_history").use {
      assertThat(it.moveToFirst()).isTrue()
      assertThat(it.getString(0)).isEqualTo("altro database")
    }
    until("il processo che possedeva Room viene riavviato") { restarts == 1 }
    // Il catalogo del processo successivo deve poter creare tutto lo schema e scrivere sul file nuovo.
    val fresh = GalleryDatabase.build(phone.context)
    try {
      val sql = fresh.openHelper.writableDatabase
      sql.execSQL("INSERT INTO search_history(text, chips) VALUES ('nuovo account', '')")
      sql.query("SELECT text FROM search_history").use {
        assertThat(it.moveToFirst()).isTrue()
        assertThat(it.getString(0)).isEqualTo("nuovo account")
        assertThat(it.moveToNext()).isFalse()
      }
      assertThat(g.database.isOpen).isFalse()
    } finally {
      fresh.close()
    }
  }

  @Test
  fun ancheUnCatalogoNonAncoraApertoSiRitiraPrimaDelCambioAccount() = runBlocking<Unit> {
    val time = TestTime()
    var restarts = 0
    val phone = TestDevice(
      app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time)),
      flags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = false),
      restart = ProcessRestart { restarts++ },
    ).also { device = it }
    val graph = phone.graph
    graph.start()
    graph.awaitSetup { it == SetupState.NotConfigured }
    val g = GalleryModule.gallery(phone.context, graph, graph.setup, Optional.empty()).also { gallery = it }
    assertThat(g.database.isOpen).isFalse()
    assertThat(graph.galleryMayRun()).isFalse()

    assertThat(AppPreviousAccountData(AppLocalDataEraser(phone.context, graph), graph).erase()).isTrue()

    until("anche il grafo ritirato prima della prima query richiede un processo nuovo") { restarts == 1 }
    assertThat(g.database.isOpen).isFalse()
    assertThat(g.started.value).isFalse()
  }

  @Test
  fun seLaChiusuraDellaGalleriaMaiPartitaFallisceIlCatalogoRestaDaCancellare() = runBlocking<Unit> {
    val time = TestTime()
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { device = it }
    val graph = phone.graph
    graph.start()
    graph.awaitSetup { it == SetupState.NotConfigured }
    val g = GalleryModule.gallery(phone.context, graph, graph.setup, Optional.empty()).also { gallery = it }
    until("il backup apre Room") { g.database.isOpen }
    graph.idleGalleryStopper = GalleryStopper { throw IllegalStateException("chiusura non riuscita") }
    var deletions = 0
    val context = object : ContextWrapper(phone.context) {
      override fun deleteDatabase(name: String): Boolean {
        deletions++
        return super.deleteDatabase(name)
      }
    }

    assertThat(AppPreviousAccountData(AppLocalDataEraser(context, graph), graph).erase()).isFalse()

    assertThat(deletions).isEqualTo(0)
    assertThat(g.database.isOpen).isTrue()
    assertThat(phone.context.getDatabasePath(GalleryDatabase.NAME).exists()).isTrue()
    assertThat(graph.galleryMayRun()).isFalse()
  }
}
