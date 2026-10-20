package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.backup.NoFreeSpaceRuns
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.backup.noop.MonotonicOnlyBackupClock
import dev.pampa.mycloudgallery.core.backup.noop.NoCorrections
import dev.pampa.mycloudgallery.core.backup.noop.NoFreeSpace
import dev.pampa.mycloudgallery.core.backup.noop.NoPendingTasks
import dev.pampa.mycloudgallery.core.backup.noop.NoVersionKeeper
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceRuns
import dev.pampa.mycloudgallery.feature.backup.free.NoPhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.free.PhoneOnlyAssessor
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.received.NoReceivedQueue
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedQueue
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTaskActions
import dev.pampa.mycloudgallery.feature.backup.versions.NoVersionActions
import dev.pampa.mycloudgallery.feature.backup.versions.VersionActions
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingSettings
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Le porte della **5B** (P12) legate alle implementazioni vere (P14), in debug e in release: un `@Provides` per porta accende l'`Optional` di
 * `FiveBOptionalBindingsModule` (feature-backup). Le porte leggono le **facciate** del grafo del backup (`FiveBGraph`), che vanno ai servizi
 * veri appena il backup parte: nessun ViewModel resta su un null-object (lezione della verifica funzionale). Senza backup (varianti di
 * misura, nessun NAS nella Libreria) valgono i null-object, che falliscono chiusi.
 */
@Module
@InstallIn(SingletonComponent::class)
object FiveBModule {

  @Provides fun freeSpace(gallery: GalleryGraph): FreeSpace = gallery.backup?.fiveB?.freeSpace ?: NoFreeSpace

  @Provides fun versionKeeper(gallery: GalleryGraph): VersionKeeper = gallery.backup?.fiveB?.versionKeeper ?: NoVersionKeeper

  @Provides fun corrections(gallery: GalleryGraph): Corrections = gallery.backup?.fiveB?.corrections ?: NoCorrections

  @Provides fun pendingTasks(gallery: GalleryGraph): PendingTasks = gallery.backup?.fiveB?.pendingTasks ?: NoPendingTasks

  /** IN-7: "Ricevuti in coda" (i file che un'altra app ha condiviso e che non sono ancora sul NAS). Senza backup non c'e' niente da mostrare. */
  @Provides fun receivedQueue(gallery: GalleryGraph): ReceivedQueue = gallery.backup?.fiveB?.receivedQueue ?: NoReceivedQueue

  /**
   * Correzioni 5B (LS-2): il giro di "Libera spazio" fuori dalla pagina (lavoro avviato dall'utente, notifica "Pronto", ripresa dal diario). Senza
   * backup non e' mai pronto.
   */
  @Provides fun freeSpaceRuns(gallery: GalleryGraph): FreeSpaceRuns = gallery.backup?.fiveB?.freeRuns ?: NoFreeSpaceRuns

  /** L'orologio con cui le schermate datano le conferme: lo stesso con cui i servizi le controllano. */
  @Provides fun backupClock(gallery: GalleryGraph): BackupClock = gallery.backup?.clock ?: MonotonicOnlyBackupClock

  @Provides fun phoneSystemDialogs(ui: Optional<FiveBUi>): PhoneSystemDialogs = ui.orElse(null)?.systemDialogs ?: NoPhoneSystemDialogs

  @Provides
  fun phoneOnlyAssessor(ui: Optional<FiveBUi>): PhoneOnlyAssessor =
    ui.orElse(null)?.phoneOnlyAssessor ?: PhoneOnlyAssessor { ids -> PhoneOnlyAssessment(ids.associateWith { PhoneOnlyAdvice.UNAVAILABLE }) }

  @Provides fun pendingTaskActions(ui: Optional<FiveBUi>): PendingTaskActions = ui.orElse(null)?.pendingTaskActions ?: PendingTaskActions { }

  @Provides fun versionActions(ui: Optional<FiveBUi>): VersionActions = ui.orElse(null)?.versionActions ?: NoVersionActions

  /** Le schermate della 5B e le voci del viewer: una per processo, solo con il backup. */
  @Provides
  @Singleton
  fun fiveBUi(
    @ApplicationContext context: Context,
    gallery: GalleryGraph,
    graph: AppGraph,
    integrations: IntegrationsGraph,
  ): Optional<FiveBUi> {
    val backup = gallery.backup ?: return Optional.empty()
    return Optional.of(
      FiveBUi(
        context = context.applicationContext,
        backup = backup,
        catalog = { gallery.coreIfReady()?.catalog },
        operations = { gallery.nas?.organizer?.operations },
        monotonicMs = gallery.parts.monotonicMs,
        integrations = integrations,
        scope = graph.scope,
      ),
    )
  }

  /**
   * Le integrazioni (selettore, documenti, ricevuti, modifica, condivisioni): una per processo, installata dall'applicazione. Correzioni 5B (R4):
   * "Rimuovi la posizione" arriva qui, non alle sole voci del backup: vale per ogni "Condividi", anche senza backup e dalla Libreria.
   */
  @Provides
  @Singleton
  fun integrations(
    @ApplicationContext context: Context,
    gallery: GalleryGraph,
    graph: AppGraph,
    lock: AppLockController,
    @LockSettings lockSettings: Flow<@JvmSuppressWildcards AppLockSettings>,
    setup: FamilySetupRepository,
    sharing: SharingSettings,
  ): IntegrationsGraph = IntegrationsGraph(
    context = context.applicationContext,
    gallery = gallery,
    scope = graph.scope,
    lock = lock,
    lockSettings = lockSettings,
    familyReady = { setup.state.value is SetupState.Ready },
    shareProbe = graph.shareProbe,
    removeLocation = sharing.removeLocation,
    // IN-3: a processo freddo lo stato si sta ancora leggendo; il cancello d'ingresso aspetta invece di dire "non pronta".
    familyLoading = { setup.state.value is SetupState.Loading },
    familyChanges = setup.state.map { },
  )

  @Provides
  @IntoSet
  fun integrationsDiagnostics(integrations: IntegrationsGraph): DiagnosticsContributor = integrations.diagnostics
}
