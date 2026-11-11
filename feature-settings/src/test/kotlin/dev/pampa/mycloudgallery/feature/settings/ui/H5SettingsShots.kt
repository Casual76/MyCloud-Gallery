package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryState
import dev.pampa.mycloudgallery.feature.settings.map.MapEntrySummary
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryState
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntrySummary
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryState
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntrySummary
import dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary
import dev.pampa.mycloudgallery.feature.settings.security.BackupNoticeScreen
import dev.pampa.mycloudgallery.feature.settings.security.SecurityActions
import dev.pampa.mycloudgallery.feature.settings.security.SecurityScreen
import dev.pampa.mycloudgallery.feature.settings.security.SecurityUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H5 (fase H, 11/11): le pagine delle Impostazioni **prima e dopo** il taglio dei testi, per l'utente. La pagina principale com'e' in
 * produzione (tutte le voci collegate, come le scrive `settingsGraph`), la Rete, la Sicurezza e l'avviso sul backup del NAS; chiaro e scuro,
 * il telefono dell'utente (360 x 780 dp, piu' la pagina principale intera a 360 x 1800) e il tablet orizzontale (1280 x 800).
 *
 * [STAGE] e' `prima` per la corsa sul codice di partenza (261a3553) e `dopo` per quella sul codice di H5: i PNG vanno in
 * `docs/screenshots/beta-grafica/H5/`.
 *
 * `gradlew :feature-settings:recordRoborazziDebug --tests dev.pampa.mycloudgallery.feature.settings.ui.H5SettingsPhoneShots` (e Tall, Tablet).
 */
internal abstract class H5SettingsShotsBase(private val page: H5Page, private val dark: Boolean, private val device: String) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        SettingsHost { page.content() }
      }
    }
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    composeRule.onRoot().captureRoboImage("$DIR/impostazioni-${page.slug}-$STAGE-$look-$device.png")
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H5"
    const val STAGE = "dopo"

    fun data(pages: List<H5Page>): List<Array<Any>> = pages.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
  }
}

/** Una pagina da fotografare: [slug] e' il nome nel file. */
internal class H5Page(val slug: String, val content: @Composable () -> Unit) {
  override fun toString(): String = slug

  companion object {
    /** Tutte le voci collegate, come le scrive `settingsGraph` in `:app`. */
    private val links = SettingsLinks(
      openBackup = {}, openSharing = {}, openSearch = {}, openHidden = {}, openPeople = {}, openMemories = {}, openMap = {}, openWidget = {},
    )

    val home = H5Page("principale") {
      SettingsScreen(
        SettingsUiState(TrustStatus.Approved(admin = true), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList()),
        onBack = {}, openDiagnostics = {}, resumeStep = {},
        links = links,
        backupStatus = BackupStatus.UpToDate(safeCount = 12_480, atNasMillis = null),
        motionAnimation = true,
        searchSummary = SearchEntrySummary(indexer = true, textReadingHere = true, modelMissing = false),
        peopleSummary = PeopleEntrySummary(PeopleEntryState.ON),
        memoriesSummary = MemoriesEntrySummary(MemoriesEntryState.FEW_PER_WEEK),
        mapSummary = MapEntrySummary(MapEntryState.ON),
      )
    }

    val security = H5Page("sicurezza") {
      SecurityScreen(
        SecurityUiState(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_1_MINUTE), ScreenLockStatus.SECURE),
        SecurityActions.None,
      )
    }

    val backupNotice = H5Page("copia-esterna") { BackupNoticeScreen(onBack = {}, onReviewChecklist = {}) }

    val network = H5Page("rete") { ShotCatalog.all.first { it.name == "rete-casa" }.content() }

    val all: List<H5Page> = listOf(home, network, security, backupNotice)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
internal class H5SettingsPhoneShots(page: H5Page, dark: Boolean) : H5SettingsShotsBase(page, dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(H5Page.all)
  }
}

/** La pagina principale intera: quanto testo c'e' lo si vede solo scorrendo. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h1800dp-xhdpi")
internal class H5SettingsTallShots(page: H5Page, dark: Boolean) : H5SettingsShotsBase(page, dark, "telefono-intera") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(listOf(H5Page.home, H5Page.network))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-hdpi")
internal class H5SettingsTabletShots(page: H5Page, dark: Boolean) : H5SettingsShotsBase(page, dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(listOf(H5Page.home, H5Page.network))
  }
}
