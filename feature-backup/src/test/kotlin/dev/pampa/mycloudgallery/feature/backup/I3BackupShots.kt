package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.backup.free.FreeSheet
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceActions
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceSamples
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceScreen
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceUi
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsScreen
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsActions
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsUi
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.SourceNewestMedia
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCovers
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I3 (fase I, «l'app parla troppo»): la pagina Backup e le Impostazioni del backup **prima e dopo** il ridisegno, per l'utente. Lo stato «in
 * prova» con sei cartelle (com'e' sul suo telefono: la schermata 2 di `fase-I-utente-11-11.md`), il primo backup in corso, tutto al sicuro, un
 * problema, la pausa, la configurazione che manca e le impostazioni (la schermata 3). Chiaro e scuro, il telefono dell'utente (360 x 780 dp, piu'
 * la pagina intera a 360 x 2000), il tablet orizzontale, il testo a 1,3x.
 *
 * [STAGE] e' `prima` sul codice di partenza (f7d91f7f) e `dopo` sul codice di I3. Accanto a ogni PNG, in `build/i3-parole/`, quante parole si
 * vedono (per `docs/I3-prima-dopo.md`).
 */
abstract class I3BackupShotsBase(private val page: I3BackupPage, private val dark: Boolean, private val device: String) : BackupComposeBase() {

  @Test
  fun capture() {
    show(dark = dark, fontScale = if (device.endsWith("testo-grande")) LARGE_TEXT else null) {
      // Le copertine passano dalla stessa strada della produzione (`BackupCovers`: la Libreria visibile e la cache delle miniature), su una Libreria
      // sintetica: ogni cartella ha le sue foto piu' recenti. Al «prima» non c'erano.
      val covers = androidx.compose.runtime.remember { if (STAGE != "prima") I3Covers.covers() else null }
      CompositionLocalProvider(LocalBackupCovers provides covers) { page.content() }
    }
    val look = if (dark) "scuro" else "chiaro"
    val name = "backup-${page.slug}-$STAGE-$look-$device"
    composeRule.onRoot().captureRoboImage("$DIR/$name.png")
    I3Words.write(name, composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/I3"
    // Giro 1 della fase I: `giro1` (i `dopo` di I3 sono il «prima» del giro).
    const val STAGE = "giro1"

    fun data(pages: List<I3BackupPage>): List<Array<Any>> = pages.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
  }
}

/** Le parole visibili di una schermata (i testi dei nodi, non le descrizioni per TalkBack), in `build/i3-parole/<nome>.txt`. */
internal object I3Words {
  fun count(node: SemanticsNode): Int {
    val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().sumOf { text -> words(text.text) }
    return own + node.children.sumOf { count(it) }
  }

  private fun words(text: String): Int = text.split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }

  fun write(name: String, root: SemanticsNode) {
    val dir = File("build/i3-parole").apply { mkdirs() }
    File(dir, "$name.txt").writeText(count(root).toString())
  }
}

/** Le copertine finte: una Libreria sintetica e, per ogni sorgente, le foto a partire da un punto diverso dell'indice (l'ultima cartella non ne ha). */
internal object I3Covers {
  fun covers(): BackupCovers {
    val library = SyntheticLibrary(seed = 11L, count = 400)
    val items = library.viewerItems()
    val newest = SourceNewestMedia { sources, perSource ->
      val view = items.view.value
      sources.dropLast(1).mapIndexed { i, source ->
        source.id to (0 until perSource).map { k -> view.snapshot.idAt(view.snapshotIndex(i * 37 + k * 3)) }
      }.toMap()
    }
    return BackupCovers(BackupCoverPorts(newest = newest, library = items, loader = FakeThumbnailLoader()))
  }

  /**
   * Giro 1 (CU-6): il piano di Libera spazio dei dati di prova (1.240 elementi, gli esclusi di una Libreria vera), con le prime dodici foto prese dalla
   * stessa Libreria sintetica delle copertine: cosi' le anteprime passano dalla strada vera (la Libreria visibile e la cache delle miniature).
   */
  fun freePlan(excluded: Map<FreeSpaceExclusion, Int> = FreeSpaceSamples.excludedSample, items: Int = 1_240): FreeSpacePlan {
    val view = SyntheticLibrary(seed = 11L, count = 400).view()
    val base = FreeSpaceSamples.plan(items = items, excluded = excluded)
    val mixed = base.items.mapIndexed { i, item ->
      if (i >= ShownItems) {
        item
      } else {
        FreeSpaceItem(
          mediaId = view.snapshot.idAt(view.snapshotIndex(i * 5)),
          versionId = item.versionId,
          phone = item.phone,
          mime = item.mime,
          size = item.size,
          safeCopy = item.safeCopy,
          isVideo = item.isVideo,
          localDay = item.localDay,
        )
      }
    }
    return FreeSpacePlan(filter = base.filter, permanent = base.permanent, items = mixed, excluded = base.excluded, needsGuardian = base.needsGuardian)
  }

  private const val ShownItems = 12
}

/** Una pagina da fotografare: [slug] e' il nome nel file. */
class I3BackupPage(val slug: String, val content: @Composable () -> Unit) {
  override fun toString(): String = slug

  companion object {
    private fun folder(path: String) = BackupSource.Folder("external_primary", path)

    /** Le sei cartelle del telefono dell'utente nella schermata 2: tutte da salvare, nessuna al sicuro (il backup e' in prova). */
    val sixHalted: List<SourceStatus> = listOf(
      SourceStatus(BackupSource.Camera, active = true, media = 982, safe = 0, queued = 982, problems = emptyMap()),
      SourceStatus(BackupSource.Screenshots, active = true, media = 915, safe = 0, queued = 915, problems = emptyMap()),
      SourceStatus(folder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"), active = true, media = 2_105, safe = 0, queued = 2_105, problems = emptyMap()),
      SourceStatus(folder("DCIM/Vecchie/"), active = true, media = 1_088, safe = 0, queued = 1_088, problems = emptyMap()),
      SourceStatus(folder("Download/Quick Share/"), active = true, media = 654, safe = 0, queued = 654, problems = emptyMap()),
      SourceStatus(folder("Pictures/Telegram/"), active = true, media = 224, safe = 0, queued = 224, problems = emptyMap()),
    )

    /** Le stesse durante il primo backup: la fotocamera finita, due a meta', le altre in coda, una spenta. */
    val sixRunning: List<SourceStatus> = listOf(
      SourceStatus(BackupSource.Camera, active = true, media = 982, safe = 982, queued = 0, problems = emptyMap()),
      SourceStatus(BackupSource.Screenshots, active = true, media = 915, safe = 610, queued = 305, problems = emptyMap()),
      SourceStatus(folder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"), active = true, media = 2_105, safe = 240, queued = 1_865, problems = emptyMap()),
      SourceStatus(folder("DCIM/Vecchie/"), active = true, media = 1_088, safe = 0, queued = 1_088, problems = emptyMap()),
      SourceStatus(folder("Download/Quick Share/"), active = false, media = 654, safe = 0, queued = 0, problems = emptyMap()),
      SourceStatus(folder("Pictures/Telegram/"), active = true, media = 224, safe = 220, queued = 1, problems = mapOf(BackupProblemCode.UNREADABLE to 3)),
    )

    val sixSafe: List<SourceStatus> = sixHalted.map { it.copy(safe = it.media, queued = 0) }

    private fun state(status: BackupStatus, sources: List<SourceStatus>, folder: Boolean = true) =
      BackupPageUi(status, sources, if (folder) BackupSamples.deviceFolder else null, sheetOpen = false)

    @Composable
    private fun Page(state: BackupPageUi) = BackupPageScreen(state = state, actions = BackupPageActions(), onBack = {})

    val inProva = I3BackupPage("in-prova") { Page(state(BackupStatus.TestOnly, sixHalted)) }

    val inCorso = I3BackupPage("primo-backup-in-corso") {
      Page(state(BackupStatus.Running(BackupKind.PHOTOS, 2_052, 6_968, 9_100_000_000, 16_800_000_000, 2_400_000, BackupTrigger.FIRST_BACKUP), sixRunning))
    }

    val alSicuro = I3BackupPage("al-sicuro") { Page(state(BackupStatus.UpToDate(safeCount = 5_968, atNasMillis = 1_792_119_120_000), sixSafe)) }

    val problema = I3BackupPage("problema") { Page(state(BackupStatus.Problem(BackupProblemCode.QUOTA, 0), BackupSamples.sources)) }

    val pausa = I3BackupPage("in-pausa") { Page(state(BackupStatus.Stopped(StopBy.USER), sixRunning)) }

    val nonConfigurato = I3BackupPage("da-preparare") {
      Page(state(BackupStatus.NotConfigured(setOf(BackupSetupStep.MEDIA_PERMISSION, BackupSetupStep.DEVICE_FOLDER)), emptyList(), folder = false))
    }

    val impostazioni = I3BackupPage("impostazioni") {
      BackupSettingsScreen(
        state = SettingsUi(
          folders = FoldersState.Ready(BackupSamples.phoneFolders),
          chosen = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, BackupSamples.phoneFolders.last().source)),
          deviceFolder = BackupSamples.deviceFolder,
          protectNewPhotos = true,
          rulesAvailable = true,
          editable = true,
        ),
        actions = SettingsActions(),
        onBack = {},
      )
    }

    /** Giro 1 (CU-7): il backup che aspetta la carica ha la clessidra, non il segno della pausa. */
    val attesa = I3BackupPage("in-attesa") { Page(state(BackupStatus.Waiting(items = 3_120, bytes = 9_100_000_000, reason = WaitReason.CHARGING), sixHalted)) }

    @Composable
    private fun Free(plan: FreeSpacePlan) = FreeSpaceScreen(
      state = FreeSpaceUi.Summary(plan, FreeSpaceKind.ALL),
      sheet = FreeSheet.None,
      taps = null,
      actions = FreeSpaceActions(),
      nowMs = 0L,
      onBack = {},
    )

    /** Giro 1 (CU-6): il riepilogo di Libera spazio (prima: `docs/screenshots/5-P12/libera-spazio-riepilogo-*`). */
    val liberaSpazio = I3BackupPage("libera-spazio") { Free(I3Covers.freePlan()) }

    /** Lo stesso con il backup vero non ancora acceso (la recinzione): «Backup in pausa», mai «cartella di prova». */
    val liberaSpazioInPausa = I3BackupPage("libera-spazio-in-pausa") {
      Free(I3Covers.freePlan(excluded = mapOf(FreeSpaceExclusion.OUTSIDE_TEST_FENCE to 5_208, FreeSpaceExclusion.RECENT to 214), items = 40))
    }

    val all: List<I3BackupPage> = listOf(inProva, inCorso, alSicuro, problema, pausa, nonConfigurato, impostazioni, attesa, liberaSpazio, liberaSpazioInPausa)
    val long: List<I3BackupPage> = listOf(inProva, inCorso, problema, impostazioni, liberaSpazio, liberaSpazioInPausa)
    val wide: List<I3BackupPage> = listOf(inProva, impostazioni)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class I3BackupPhoneShots(page: I3BackupPage, dark: Boolean) : I3BackupShotsBase(page, dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(I3BackupPage.all)
  }
}

/** La pagina intera: quante righe e quanto testo ci sono lo si vede solo tutta insieme. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h2000dp-xhdpi")
class I3BackupTallShots(page: I3BackupPage, dark: Boolean) : I3BackupShotsBase(page, dark, "telefono-intera") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(I3BackupPage.long)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class I3BackupTabletShots(page: I3BackupPage, dark: Boolean) : I3BackupShotsBase(page, dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(I3BackupPage.wide)
  }
}

/** Il testo a 1,3x sul telefono dell'utente. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class I3BackupLargeTextShots(page: I3BackupPage, dark: Boolean) : I3BackupShotsBase(page, dark, "telefono-testo-grande") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> =
      listOf(arrayOf<Any>(I3BackupPage.inProva, false), arrayOf<Any>(I3BackupPage.impostazioni, true), arrayOf<Any>(I3BackupPage.liberaSpazio, false))
  }
}
