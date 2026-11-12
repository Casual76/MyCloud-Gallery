package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCloudButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromePopoverAnchor
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCloudState
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterPill
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoGlyph
import dev.pampa.mycloudgallery.core.designsystem.design.glyph
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.icon.glyph
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.progressOrNull
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

// La nuvola di stato della Libreria (B2 della beta, lista dell'utente del 31/10): le tre voci che stavano in fondo alla griglia (la
// rete, il NAS, il backup) diventano **una nuvola** nella capsula dei comandi in alto a destra, con tanti stati quanti ne servono
// (in corso, tutto salvato, in attesa, senza rete, un problema), e un pop-up a tendina che dice il problema con l'azione per
// risolverlo o, se va tutto bene, lo spazio del NAS, l'ultima sincronizzazione e "Libera spazio".
//
// Qui: i dati che arrivano da :app (la rete, lo spazio), la mappa **pura** dagli stati veri alla nuvola ([libraryCloud], provata
// caso per caso in `LibraryCloudTest`), il tasto e il pannello. Le frasi dei problemi e dei lavori sono quelle del piede
// (`nasFooterNote`, `backupFooterNote`), le uscite le stesse di prima (la rete la sua azione, il NAS la Diagnostica, il backup la
// sua pagina).

/** Quanto pesa la rete sulla nuvola. La decide `:app` dall'indicatore globale (`GlobalStatus`), che la Libreria non vede. */
enum class LibraryNetworkSeverity {
  /** A casa o in VPN, il NAS c'e' (o riposa): la rete non ha niente da dire. */
  Fine,

  /** Si sta svegliando il NAS. */
  Working,

  /** Nessuna rete. */
  Offline,

  /** Da guardare, senza colpa: rete non riconosciuta, VPN spenta, NAS non raggiungibile. */
  Attention,

  /** Serve l'utente: la password del NAS, la rete locale negata. */
  Alarm,
}

/** Che cosa fa il tasto sotto un problema della rete: le uscite della capsula della rete di prima. */
enum class LibraryNetworkAction {
  /** I dettagli in Diagnostica. */
  DIAGNOSTICS,

  /** "VPN spenta": apre WireGuard. */
  WIREGUARD,

  /** "Serve la password del NAS": l'account nelle Impostazioni. */
  PASSWORD,
}

/**
 * Lo stato della rete come lo racconta la nuvola: la frase dell'indicatore globale divisa al punto ([label] e [detail]), il glifo,
 * il peso e l'azione del tasto. Lo compone `:app`; [description] e' la frase intera per TalkBack.
 */
@Immutable
data class LibraryNetworkStatus(
  val severity: LibraryNetworkSeverity,
  val label: String,
  val detail: String? = null,
  val glyph: ImageVector? = null,
  val action: LibraryNetworkAction = LibraryNetworkAction.DIAGNOSTICS,
  val description: String? = null,
)

/**
 * Lo spazio del NAS per il pop-up della nuvola: la stessa misura della pagina "Spazio NAS" (`NasSpace` di core-nasops, che la Libreria
 * non vede), letta da `:app` solo quando l'utente apre il pop-up.
 */
sealed interface LibraryNasSpace {
  /** Lo spazio usato e il totale del disco, in byte (unita' decimali, come il NAS). */
  data class Ready(val usedBytes: Long, val totalBytes: Long) : LibraryNasSpace

  /** Il NAS non risponde o l'area non c'e': lo si dice. */
  data object Unavailable : LibraryNasSpace
}

/** Una voce della nuvola: da dove viene e che stato della nuvola porta. */
@Immutable
internal sealed interface CloudItem {
  val state: GalleryCloudState

  /** La pausa delle recinzioni (il backup vero non ancora acceso, l'indice solo sul telefono): non l'ha chiesta l'utente e non toglie lo spazio. */
  val isFence: Boolean
    get() = (this is Backup && status == LibraryBackupStatus.TestOnly) || (this is Nas && status == LibraryNasStatus.LocalOnly)

  data class Network(val status: LibraryNetworkStatus, override val state: GalleryCloudState) : CloudItem

  data class Nas(val status: LibraryNasStatus, override val state: GalleryCloudState) : CloudItem

  data class Backup(val status: LibraryBackupStatus, override val state: GalleryCloudState) : CloudItem

  /** Pacchetto X: l'analisi delle foto (una riga di lavoro nel pannello; non alza da sola il glifo, salvo un problema). */
  data class Analysis(val status: LibraryAnalysisStatus, override val state: GalleryCloudState) : CloudItem
}

/**
 * Che cosa dice la nuvola: lo stato (il glifo), i problemi (dal piu' grave), la pausa del backup (OA-10: non e' un problema, ma ha il
 * suo tasto verso la pagina dove si riprende), i lavori in corso o in attesa, e l'ultima sincronizzazione nota (ora del NAS).
 * [showsSpace]: senza niente da sistemare il pop-up mostra lo spazio, l'ultima sincronizzazione e "Libera spazio" (la richiesta
 * dell'utente: "se ci sta un problema il problema, o altrimenti...").
 */
@Immutable
internal data class LibraryCloud(
  val state: GalleryCloudState,
  val problems: List<CloudItem>,
  val paused: List<CloudItem>,
  val activity: List<CloudItem>,
  val savedByBackup: Boolean,
  val lastSyncNasMillis: Long?,
) {
  /** Le voci con il loro tasto: i problemi e, sotto, la pausa. */
  val withAction: List<CloudItem> get() = problems + paused

  /**
   * Lo spazio, l'ultima sincronizzazione e "Libera spazio": senza problemi e senza la pausa **dell'utente** (OA-10). Il backup vero non ancora acceso
   * (`TestOnly`, I4) e' una pausa che l'utente non ha chiesto e che non ferma la lettura del NAS: lo spazio resta.
   */
  val showsSpace: Boolean get() = problems.isEmpty() && paused.all { it.isFence }

  /**
   * I4 (fase I, «uno stato si dice una volta»): la pausa del backup quando e' l'**unica** cosa che la nuvola ha da dire. Allora la dice solo il titolo
   * («In pausa», le parole della pagina Backup), con la «i» che apre il perche'; sotto resta il tasto. Null in ogni altro caso: la pausa ha la sua capsula.
   */
  val pauseInTitle: CloudItem?
    get() = paused.singleOrNull()?.takeIf { state == GalleryCloudState.Paused && problems.isEmpty() }
}

/**
 * La mappa dagli stati veri alla nuvola. Null = niente da dire (nessun NAS, nessun backup, la rete a posto): la nuvola non c'e'.
 *
 * - **la rete**: a posto non dice niente; il NAS che si sveglia e' un lavoro; senza rete la nuvola e' barrata; il resto e' un problema
 *   ([LibraryNetworkStatus.severity]);
 * - **il NAS**: lettura e allineamento sono lavori, "aggiornato" e' salvato, i lavori fermi sono un allarme, le aree non raggiungibili
 *   un'attenzione, la pubblicazione limitata dalle recinzioni una pausa (CU-1: la stessa del backup, detta una volta);
 * - **il backup**: confronto e giri sono lavori, la coda e' un'attesa, il backup vero non ancora acceso e' una pausa (I4), "tutto al sicuro" e' salvato, un problema prende il
 *   tono della sua riga in [BackupFooterProblems] (allarme, attenzione, o attesa se riparte da solo). **Fermo** (OA-10) non e' mai
 *   "salvato": la pausa dell'utente ha il suo stato, il lavoro fermato da Android e' un'attesa (riprende da solo).
 *
 * Lo stato e' il piu' grave delle voci, in quest'ordine: allarme, senza rete, attenzione, in pausa, in corso, in attesa, salvato. La
 * pausa sta sopra i lavori in corso: finche' dura le foto nuove non si salvano, e solo l'utente la toglie.
 */
internal fun libraryCloud(network: LibraryNetworkStatus?, nas: LibraryNasStatus, backup: LibraryBackupStatus, analysis: LibraryAnalysisStatus = LibraryAnalysisStatus.Idle): LibraryCloud? {
  val backupItem = backupItem(backup)
  // CU-1 (fase I): con le recinzioni accese il NAS pubblica solo in locale **e** il backup vero non e' acceso: e' una pausa sola, detta una volta. Se il
  // backup e' gia' in pausa (la recinzione, o la pausa dell'utente) il NAS non aggiunge una seconda voce.
  val nasItem = nasItem(nas)?.takeUnless { nas == LibraryNasStatus.LocalOnly && backupItem?.state == GalleryCloudState.Paused }
  val items = listOfNotNull(network?.let(::networkItem), nasItem, backupItem, analysisItem(analysis))
  if (items.isEmpty()) return null
  // Pacchetto X: il glifo resta la salute di rete, NAS e backup; l'analisi lo alza solo per un problema (attenzione), o se e' l'unica voce.
  val healthItems = items.filter { it !is CloudItem.Analysis || it.state == GalleryCloudState.Attention }.ifEmpty { items }
  val state = healthItems.maxBy { severityRank(it.state) }.state
  val problems = items.filter { it.state.isProblem }.sortedByDescending { severityRank(it.state) }
  val activity = items.filter { it.state == GalleryCloudState.Syncing } + items.filter { it.state == GalleryCloudState.Waiting }
  return LibraryCloud(
    state = state,
    problems = problems,
    paused = items.filter { it.state == GalleryCloudState.Paused },
    activity = activity,
    savedByBackup = backup is LibraryBackupStatus.AllSafe,
    lastSyncNasMillis = lastSyncOf(nas, backup),
  )
}

/** L'ultima sincronizzazione che gli stati dicono adesso: la piu' recente fra "NAS aggiornato" e "tutto al sicuro". */
internal fun lastSyncOf(nas: LibraryNasStatus, backup: LibraryBackupStatus): Long? =
  listOfNotNull((nas as? LibraryNasStatus.UpToDate)?.atNasMillis, (backup as? LibraryBackupStatus.AllSafe)?.atNasMillis).maxOrNull()

/** Pacchetto X: l'analisi entra nel pannello solo quando ha qualcosa da dire (in corso, in coda, in pausa, un problema). */
private fun analysisItem(status: LibraryAnalysisStatus): CloudItem? = when (status) {
  is LibraryAnalysisStatus.Running -> CloudItem.Analysis(status, GalleryCloudState.Syncing)
  is LibraryAnalysisStatus.Queued, is LibraryAnalysisStatus.Paused -> CloudItem.Analysis(status, GalleryCloudState.Waiting)
  is LibraryAnalysisStatus.Problem -> CloudItem.Analysis(status, GalleryCloudState.Attention)
  else -> null
}

private fun networkItem(status: LibraryNetworkStatus): CloudItem? = when (status.severity) {
  LibraryNetworkSeverity.Fine -> null
  LibraryNetworkSeverity.Working -> CloudItem.Network(status, GalleryCloudState.Syncing)
  LibraryNetworkSeverity.Offline -> CloudItem.Network(status, GalleryCloudState.Offline)
  LibraryNetworkSeverity.Attention -> CloudItem.Network(status, GalleryCloudState.Attention)
  LibraryNetworkSeverity.Alarm -> CloudItem.Network(status, GalleryCloudState.Alarm)
}

private fun nasItem(status: LibraryNasStatus): CloudItem? = when (status) {
  LibraryNasStatus.Idle -> null
  is LibraryNasStatus.FirstScan, is LibraryNasStatus.Aligning -> CloudItem.Nas(status, GalleryCloudState.Syncing)
  is LibraryNasStatus.UpToDate -> CloudItem.Nas(status, GalleryCloudState.Saved)
  is LibraryNasStatus.Frozen -> CloudItem.Nas(status, GalleryCloudState.Alarm)
  is LibraryNasStatus.AreasUnavailable -> CloudItem.Nas(status, GalleryCloudState.Attention)
  // CU-1: la pubblicazione limitata e' la stessa recinzione del backup vero non ancora acceso: «In pausa», come lui.
  LibraryNasStatus.LocalOnly -> CloudItem.Nas(status, GalleryCloudState.Paused)
}

private fun backupItem(status: LibraryBackupStatus): CloudItem? = when (status) {
  LibraryBackupStatus.Idle -> null
  // I4: il backup vero non ancora acceso si dice come nella pagina Backup, «In pausa» (la recinzione non cambia: cambia come si mostra).
  LibraryBackupStatus.TestOnly -> CloudItem.Backup(status, GalleryCloudState.Paused)
  is LibraryBackupStatus.Queued -> CloudItem.Backup(status, GalleryCloudState.Waiting)
  // Fase I (12/11): la ricerca sul NAS ferma per una regola (il telefono caldo…) e' un'attesa, non un lavoro che gira.
  is LibraryBackupStatus.Comparing -> CloudItem.Backup(status, if (status.wait != null) GalleryCloudState.Waiting else GalleryCloudState.Syncing)
  is LibraryBackupStatus.FirstBackup, is LibraryBackupStatus.Running -> CloudItem.Backup(status, GalleryCloudState.Syncing)
  is LibraryBackupStatus.AllSafe -> CloudItem.Backup(status, GalleryCloudState.Saved)
  // OA-10: un backup fermo non sparisce dalla nuvola (prima, con "NAS aggiornato", restava la spunta di "tutto salvato").
  is LibraryBackupStatus.Paused -> CloudItem.Backup(status, if (status.byUser) GalleryCloudState.Paused else GalleryCloudState.Waiting)
  is LibraryBackupStatus.Problem -> CloudItem.Backup(
    status,
    when (BackupFooterProblems.of(status.code).tone) {
      GalleryFooterTone.Alarm -> GalleryCloudState.Alarm
      GalleryFooterTone.Waiting -> GalleryCloudState.Waiting
      GalleryFooterTone.Calm, GalleryFooterTone.Working, GalleryFooterTone.Attention -> GalleryCloudState.Attention
    },
  )
}

/** Quanto pesa uno stato della nuvola: vince il piu' alto. */
private fun severityRank(state: GalleryCloudState): Int = when (state) {
  GalleryCloudState.Saved -> 0
  GalleryCloudState.Waiting -> 1
  GalleryCloudState.Syncing -> 2
  GalleryCloudState.Paused -> 3
  GalleryCloudState.Attention -> 4
  GalleryCloudState.Offline -> 5
  GalleryCloudState.Alarm -> 6
}

/** Le uscite della nuvola: la rete (la sua azione), il NAS (la Diagnostica), il backup (la sua pagina), "Libera spazio". */
@Immutable
internal class LibraryCloudActions(
  val onNetwork: () -> Unit,
  val onNas: () -> Unit,
  val onBackup: () -> Unit,
  /** Pacchetto X: il tocco sulla riga dell'analisi (la pagina "Analisi delle foto"). */
  val onAnalysis: () -> Unit = {},
)

/**
 * La nuvola nella capsula dei comandi: il tasto ([GalleryChromeCloudButton]) e il pop-up a tendina che apre ([LibraryCloudPanel], sul
 * materiale dei pannelli della chrome). Legge gli stati come `State`, cosi' un avanzamento che cambia (un backup che conta i file)
 * ricompone il pannello aperto e la descrizione per TalkBack solo quando cambia il problema, non la capsula intera.
 */
@Composable
internal fun LibraryCloudButton(
  state: GalleryCloudState,
  network: LibraryNetworkStatus?,
  nas: State<LibraryNasStatus>,
  backup: State<LibraryBackupStatus>,
  analysis: State<LibraryAnalysisStatus> = remember { mutableStateOf(LibraryAnalysisStatus.Idle) },
  lastSync: State<Long?>,
  commands: LibraryCommands,
  actions: LibraryCloudActions,
  modifier: Modifier = Modifier,
) {
  // Cio' che TalkBack dice dopo lo stato: il problema piu' grave o, in pausa, che cosa e' in pausa.
  val problem by remember(network) { derivedStateOf { libraryCloud(network, nas.value, backup.value, analysis.value)?.withAction?.firstOrNull() } }
  val title = cloudTitle(state, savedByBackup = backup.value is LibraryBackupStatus.AllSafe)
  val headline = problem?.let { item -> noteOf(item).let { note -> if (note.label.equals(title, ignoreCase = true)) note.detail else note.label } }
  val description = if (headline == null) {
    stringResource(R.string.library_cloud_button, title)
  } else {
    stringResource(R.string.library_cloud_button_problem, title, headline)
  }
  GalleryChromePopoverAnchor(
    paneTitle = stringResource(R.string.library_cloud_pane),
    modifier = modifier,
    trigger = { open -> GalleryChromeCloudButton(state = state, contentDescription = description, onClick = open) },
  ) { close ->
    val cloud = remember(network) { derivedStateOf { libraryCloud(network, nas.value, backup.value, analysis.value) } }.value
    if (cloud != null) {
      LibraryCloudPanel(cloud = cloud, lastSyncNasMillis = lastSync.value, commands = commands, actions = actions, close = close)
    }
  }
}

/**
 * L'ultima sincronizzazione **ricordata**: durante un giro di backup gli stati non la dicono piu' (il backup e' "in corso"), ma la
 * Libreria l'ha vista un momento prima. La piu' recente vista da quando la Libreria e' aperta.
 */
@Composable
internal fun rememberLastSync(nas: State<LibraryNasStatus>, backup: State<LibraryBackupStatus>): State<Long?> {
  val last = remember { mutableStateOf<Long?>(null) }
  LaunchedEffect(nas, backup) {
    snapshotFlow { lastSyncOf(nas.value, backup.value) }.collect { now ->
      val seen = last.value
      if (now != null && (seen == null || now > seen)) last.value = now
    }
  }
  return last
}

/**
 * Il pannello del pop-up della nuvola (I4, fase I: «l'app parla troppo»). In cima lo stato, detto **una volta**. Poi:
 *  - i **problemi** e la **pausa** del backup: la capsula tonale con il segno e due parole; la frase che spiega sta **dietro il tocco**
 *    (la capsula porta la «i» e apre la frase sotto; TalkBack la legge sempre, nella descrizione della capsula). Sotto, il tasto con
 *    l'icona che porta dove si risolve. La frase non ripete il tasto (GF-12);
 *  - i **lavori** in corso o in attesa: un **anello che avanza** con la percentuale dentro e accanto il numero (1.240 / 6.658) e il tempo
 *    che manca con il segno dell'orologio; senza un totale il cerchio che gira. La riga si tocca e porta alla sua pagina (la freccia);
 *  - **senza niente da sistemare**: lo spazio del NAS come anello con il disco dentro e il numero, l'ultima sincronizzazione con il segno
 *    della cronologia e l'ora, "Libera spazio" con la sua scopa. Il filo divisorio sopra c'e' solo se sotto c'e' qualcosa (GF-12).
 * Ogni tasto chiude il pop-up prima di aprire la sua pagina. Il testo e' `onSurface` pieno: il velo del pop-up e' calcolato per lui.
 */
@Composable
internal fun LibraryCloudPanel(
  cloud: LibraryCloud,
  lastSyncNasMillis: Long?,
  commands: LibraryCommands,
  actions: LibraryCloudActions,
  close: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ink = MaterialTheme.colorScheme.onSurface
  Column(
    modifier = modifier
      .widthIn(min = PanelMinWidth, max = PanelMaxWidth)
      .padding(horizontal = PanelPaddingH, vertical = PanelPaddingV),
    verticalArrangement = Arrangement.spacedBy(PanelGap),
  ) {
    val inTitle = cloud.pauseInTitle
    if (inTitle == null) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitleGap)) {
        Icon(imageVector = cloud.state.glyph(), contentDescription = null, tint = ink, modifier = Modifier.size(TitleGlyph))
        Text(
          text = cloudTitle(cloud.state, cloud.savedByBackup),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = ink,
          modifier = Modifier.semantics { heading() },
        )
      }
    } else {
      PauseTitle(state = cloud.state, title = cloudTitle(cloud.state, cloud.savedByBackup), detail = noteOf(inTitle).detail)
    }
    for (item in cloud.withAction) {
      val action = actionLabel(item)
      val note = noteOf(item)
      if (item != inTitle) ProblemNote(note = if (note.detail.sameWordsAs(action)) note.copy(detail = null) else note)
      FluidButton(
        text = action,
        onClick = {
          close()
          actionOf(item, actions)()
        },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
        leading = { Icon(imageVector = actionGlyph(item), contentDescription = null, modifier = Modifier.size(ButtonGlyph)) },
      )
    }
    for (item in cloud.activity) {
      ActivityRow(note = noteOf(item), gauge = gaugeOf(item), mark = markOf(item), onClick = {
        close()
        actionOf(item, actions)()
      })
    }
    if (cloud.showsSpace) {
      val space = rememberNasSpace(commands)
      val anythingBelow = space != SpaceUi.Absent || lastSyncNasMillis != null || commands.canFreeSpace
      if (cloud.activity.isNotEmpty() && anythingBelow) FluidHairline(modifier = Modifier.testTag(CloudDividerTag))
      SpaceRow(space)
      if (lastSyncNasMillis != null) LastSyncRow(lastSyncNasMillis)
      if (commands.canFreeSpace) {
        FluidButton(
          text = stringResource(R.string.library_cloud_free_space),
          onClick = {
            close()
            // Tutto il sicuro (nessuna selezione): la pagina di feature-backup verifica e chiede la conferma, qui niente si toglie.
            commands.freeSpace(emptySet())
          },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
          leading = { Icon(imageVector = Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(ButtonGlyph)) },
        )
      }
    }
  }
}

/**
 * I4: il titolo del pannello quando la pausa del backup e' l'unica cosa da dire: il segno della pausa, «In pausa» e la «i»; un tocco apre il perche' sotto
 * (e lo richiude). TalkBack sente il titolo come intestazione e il perche' nella sua descrizione, senza tocco.
 */
@Composable
private fun PauseTitle(state: GalleryCloudState, title: String, detail: String?) {
  val ink = MaterialTheme.colorScheme.onSurface
  var open by rememberSaveable(title) { mutableStateOf(false) }
  val spoken = listOfNotNull(title, detail).joinToString(". ")
  Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
        .semantics(mergeDescendants = true) {
          heading()
          contentDescription = spoken
        }
        .then(if (detail != null) Modifier.fluidPressable(onClick = { open = !open }, role = Role.Button, focusShape = ContinuousCornerShape(FluidRadius.Control)) else Modifier)
        .testTag(PauseTitleTag),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(TitleGap),
    ) {
      Icon(imageVector = GalleryIconStatus.Paused.glyph(), contentDescription = null, tint = ink, modifier = Modifier.size(TitleGlyph))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = ink,
        modifier = Modifier.semantics { hideFromAccessibility() },
      )
      if (detail != null) GalleryInfoGlyph(open = open)
    }
    if (detail != null) GalleryInfoDetail(text = detail, visible = open, color = ink, modifier = Modifier.testTag(ProblemDetailTag))
  }
}

/**
 * Un problema o la pausa: la capsula tonale con il segno e le due parole. Se c'e' una frase che spiega, la capsula porta la «i» e un
 * tocco la apre sotto (e la richiude). TalkBack non ha bisogno del tocco: la descrizione della capsula dice gia' etichetta e frase.
 */
@Composable
private fun ProblemNote(note: GalleryFooterNote) {
  val detail = note.detail
  var open by rememberSaveable(note.label) { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
    GalleryFooterPill(
      note = note,
      onClick = if (detail != null) ({ open = !open }) else null,
      trailing = if (detail != null) Icons.Outlined.Info else null,
    )
    if (open && detail != null) {
      Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(ProblemDetailTag).semantics { hideFromAccessibility() },
      )
    }
  }
}

/** Il numero di un lavoro che avanza: il segno di chi lavora (il backup, il NAS), il conto ("1.240 / 6.658") e il tempo che manca. */
@Immutable
private data class CloudGauge(val glyph: ImageVector, val count: String, val eta: String?)

/** Il conto dei lavori che hanno un totale; null per gli altri (la coda, il confronto, la rete che si sveglia): restano la loro frase. */
@Composable
private fun gaugeOf(item: CloudItem): CloudGauge? = when (item) {
  is CloudItem.Backup -> when (val s = item.status) {
    is LibraryBackupStatus.Running -> CloudGauge(Icons.Rounded.CloudUpload, ofTotal(GalleryNumbers.format(s.done), GalleryNumbers.format(s.total)), etaText(s.etaMillis))
    is LibraryBackupStatus.FirstBackup -> CloudGauge(Icons.Rounded.CloudUpload, ofTotal(GalleryNumbers.format(s.done), GalleryNumbers.format(s.total)), etaText(s.etaMillis))
    else -> null
  }
  is CloudItem.Nas -> when (val s = item.status) {
    is LibraryNasStatus.FirstScan -> s.total?.takeIf { it > 0 }?.let { total ->
      CloudGauge(Icons.Rounded.Storage, ofTotal(GalleryNumbers.format(s.done), GalleryNumbers.format(total)), etaText(s.etaMillis))
    }
    is LibraryNasStatus.Aligning -> s.bytesTotal.takeIf { it > 0 }?.let {
      CloudGauge(Icons.Rounded.Sync, ofTotal(cloudBytes(s.bytesDone), cloudBytes(s.bytesTotal)), etaText(s.etaMillis))
    }
    else -> null
  }
  // Grafica XU: il conto solo mentre analizza; in coda o in pausa la riga dice lo stato e il perche' (l'anello tiene comunque il percento).
  is CloudItem.Analysis -> (item.status as? LibraryAnalysisStatus.Running)?.let { s ->
    val ready = s.ready
    val total = s.total
    if (ready != null && total != null && total > 0) {
      CloudGauge(AnalysisGlyph, ofTotal(GalleryNumbers.format(ready), GalleryNumbers.format(total)), s.etaMinutes?.let { stringResource(R.string.library_cloud_analysis_eta, it) })
    } else {
      null
    }
  }
  is CloudItem.Network -> null
}

@Composable
private fun ofTotal(done: String, total: String): String = stringResource(R.string.library_cloud_gauge_of, done, total)

@Composable
private fun etaText(etaMillis: Long?): String? = etaMillis?.let { withEta(null, it) }

/**
 * Un lavoro della nuvola in una riga che si tocca: a sinistra l'anello che avanza con la percentuale (o il cerchio che gira, o il
 * segno dell'attesa), accanto il numero o le due parole, sotto il tempo che manca (con l'orologio) o la ragione dell'attesa; in coda la
 * freccia. Per TalkBack una descrizione sola, la frase intera di prima.
 */
@Composable
private fun ActivityRow(note: GalleryFooterNote, gauge: CloudGauge?, onClick: () -> Unit, mark: GalleryIconStatus? = null) {
  val ink = MaterialTheme.colorScheme.onSurface
  // CU-30: lo stesso avanzamento ha lo stesso colore ovunque, quello del backup.
  val accent = galleryRingColor(GalleryIconFamily.Backup)
  val spoken = note.description ?: listOfNotNull(note.label, note.detail).joinToString(". ")
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = RowMinHeight)
      .semantics(mergeDescendants = true) { contentDescription = spoken }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = ContinuousCornerShape(FluidRadius.Control))
      .testTag(ActivityRowTag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(RowGap),
  ) {
    Box(modifier = Modifier.size(GaugeSize), contentAlignment = Alignment.Center) {
      val progress = note.progress
      when {
        progress != null -> {
          GalleryProgressRing(progress = progress, modifier = Modifier.fillMaxSize(), color = accent, strokeWidth = GaugeStroke, animate = true)
          Text(
            text = stringResource(R.string.library_cloud_gauge_percent, (progress * PercentScale).toInt()),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
            color = ink,
            maxLines = 1,
            modifier = Modifier.semantics { hideFromAccessibility() },
          )
        }
        note.busy -> FluidSpinner(size = SpinnerSize, color = accent)
        else -> {
          GalleryProgressRing(progress = 0f, modifier = Modifier.fillMaxSize(), color = accent, strokeWidth = GaugeStroke)
          Icon(imageVector = note.glyph ?: Icons.Rounded.HourglassTop, contentDescription = null, tint = accent, modifier = Modifier.size(GaugeGlyph))
        }
      }
      // Grafica XU: il segno di stato appoggiato sull'anello, come nella testata dell'analisi (solo per le voci che lo passano).
      if (mark != null) {
        GalleryStatusMark(
          status = mark,
          size = RowMark,
          ring = MaterialTheme.colorScheme.surfaceContainerHigh,
          contentDescription = null,
          modifier = Modifier.align(Alignment.BottomEnd).offset(x = RowMarkOverhang, y = RowMarkOverhang),
        )
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LineGapTight)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InlineGap)) {
        if (gauge != null) Icon(imageVector = gauge.glyph, contentDescription = null, tint = ink, modifier = Modifier.size(InlineGlyph))
        Text(
          text = gauge?.count ?: note.label,
          style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
          color = ink,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.semantics { hideFromAccessibility() },
        )
      }
      val below = if (gauge != null) gauge.eta else note.detail
      if (below != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InlineGap)) {
          if (gauge != null) Icon(imageVector = Icons.Rounded.Timer, contentDescription = null, tint = ink, modifier = Modifier.size(SmallGlyph))
          Text(
            text = below,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
            color = ink,
            // GF-24: la riga dice gia' tutto nella sua descrizione; i testi a parte si vedono ma TalkBack non li rilegge.
            modifier = Modifier.semantics { hideFromAccessibility() },
          )
        }
      }
    }
    Icon(imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = ink, modifier = Modifier.size(InlineGlyph))
  }
}

/** Lo stato della lettura dello spazio, nel pannello aperto. */
private sealed interface SpaceUi {
  data object Loading : SpaceUi

  data class Shown(val space: LibraryNasSpace) : SpaceUi

  /** Senza NAS (o senza il legame con `:app`): la sezione non c'e'. */
  data object Absent : SpaceUi
}

/**
 * Lo spazio del NAS: si legge quando il pannello si apre (mai in background), come la pagina "Spazio NAS". Lo tiene il pannello,
 * che deve sapere se la sezione c'e' (il filo divisorio sopra non resta da solo).
 */
@Composable
private fun rememberNasSpace(commands: LibraryCommands): SpaceUi {
  var space by remember { mutableStateOf<SpaceUi>(SpaceUi.Loading) }
  LaunchedEffect(commands) {
    space = try {
      commands.nasSpace()?.let { SpaceUi.Shown(it) } ?: SpaceUi.Absent
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failed: Exception) {
      SpaceUi.Shown(LibraryNasSpace.Unavailable)
    }
  }
  return space
}

/**
 * Lo spazio del NAS senza il titolo: un anello pieno quanto il disco, con il disco dentro, e accanto quanto e' usato ("1,2 TB") sopra il
 * totale ("di 3,6 TB"). Mentre si legge l'anello e' vuoto e gira il cerchio; se non si legge, il disco barrato e un trattino.
 */
@Composable
private fun SpaceRow(shown: SpaceUi) {
  if (shown == SpaceUi.Absent) return
  val ink = MaterialTheme.colorScheme.onSurface
  // CU-30: lo stesso avanzamento ha lo stesso colore ovunque, quello del backup.
  val accent = galleryRingColor(GalleryIconFamily.Backup)
  val ready = (shown as? SpaceUi.Shown)?.space as? LibraryNasSpace.Ready
  val spoken = when {
    ready != null -> stringResource(R.string.library_cloud_space_description, stringResource(R.string.library_cloud_space_used, cloudBytes(ready.usedBytes), cloudBytes(ready.totalBytes)))
    shown == SpaceUi.Loading -> stringResource(R.string.library_cloud_space_loading)
    else -> stringResource(R.string.library_cloud_space_unavailable)
  }
  Row(
    modifier = Modifier.fillMaxWidth().testTag(SpaceRowTag).clearAndSetSemantics { contentDescription = spoken },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(RowGap),
  ) {
    Box(modifier = Modifier.size(GaugeSize), contentAlignment = Alignment.Center) {
      val fraction = ready?.let { if (it.totalBytes > 0) (it.usedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) else 0f } ?: 0f
      GalleryProgressRing(progress = fraction, modifier = Modifier.fillMaxSize(), color = accent, strokeWidth = GaugeStroke, animate = true)
      if (shown == SpaceUi.Loading) {
        FluidSpinner(size = SpinnerSize, color = accent)
      } else {
        Icon(
          imageVector = if (ready != null) Icons.Rounded.Storage else Icons.Rounded.CloudOff,
          contentDescription = null,
          tint = accent,
          modifier = Modifier.size(GaugeGlyph),
        )
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(LineGapTight)) {
      Text(
        text = ready?.let { cloudBytes(it.usedBytes) } ?: stringResource(R.string.library_cloud_space_unknown),
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
        color = ink,
      )
      if (ready != null) {
        Text(
          text = stringResource(R.string.library_cloud_space_of, cloudBytes(ready.totalBytes)),
          style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
          color = ink,
        )
      }
    }
  }
}

/** L'ultima sincronizzazione senza il titolo: il segno della cronologia e l'ora. TalkBack la dice per intero. */
@Composable
private fun LastSyncRow(atNasMillis: Long) {
  val ink = MaterialTheme.colorScheme.onSurface
  val moment = nasMoment(atNasMillis)
  val spoken = stringResource(R.string.library_cloud_last_sync_description, moment)
  Row(
    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(RowGap),
  ) {
    Box(modifier = Modifier.size(GaugeSize), contentAlignment = Alignment.Center) {
      Icon(imageVector = Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(SyncGlyph))
    }
    Text(text = moment, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium), color = ink)
  }
}

/** La frase in cima al pannello (e nella descrizione del tasto). Tutto salvato dice "Tutto al sicuro" se lo dice il backup. */
@Composable
internal fun cloudTitle(state: GalleryCloudState, savedByBackup: Boolean): String = stringResource(
  when (state) {
    GalleryCloudState.Saved -> if (savedByBackup) R.string.backup_footer_all_safe else R.string.nas_footer_up_to_date
    GalleryCloudState.Syncing -> R.string.library_cloud_title_syncing
    GalleryCloudState.Waiting -> R.string.library_cloud_title_waiting
    GalleryCloudState.Paused -> R.string.library_cloud_title_paused
    GalleryCloudState.Offline -> R.string.library_cloud_title_offline
    GalleryCloudState.Attention -> R.string.library_cloud_title_attention
    GalleryCloudState.Alarm -> R.string.library_cloud_title_alarm
  },
)

/** La voce del pannello: le frasi del piede di prima per il NAS e il backup, quella dell'indicatore globale per la rete. */
@Composable
private fun noteOf(item: CloudItem): GalleryFooterNote = when (item) {
  is CloudItem.Network -> GalleryFooterNote(
    tone = when (item.status.severity) {
      LibraryNetworkSeverity.Fine -> GalleryFooterTone.Calm
      LibraryNetworkSeverity.Working -> GalleryFooterTone.Working
      LibraryNetworkSeverity.Offline -> GalleryFooterTone.Waiting
      LibraryNetworkSeverity.Attention -> GalleryFooterTone.Attention
      LibraryNetworkSeverity.Alarm -> GalleryFooterTone.Alarm
    },
    label = item.status.label,
    glyph = item.status.glyph,
    detail = item.status.detail,
    busy = item.status.severity == LibraryNetworkSeverity.Working,
    description = item.status.description,
  )
  is CloudItem.Nas -> checkNotNull(nasFooterNote(item.status))
  is CloudItem.Backup -> checkNotNull(backupFooterNote(item.status))
  is CloudItem.Analysis -> analysisNoteOf(item.status)
}

@Composable
private fun actionLabel(item: CloudItem): String = stringResource(
  when (item) {
    is CloudItem.Network -> when (item.status.action) {
      LibraryNetworkAction.DIAGNOSTICS -> R.string.library_cloud_action_diagnostics
      LibraryNetworkAction.WIREGUARD -> R.string.library_cloud_action_wireguard
      LibraryNetworkAction.PASSWORD -> R.string.library_cloud_action_password
    }
    is CloudItem.Nas -> R.string.library_cloud_action_diagnostics
    is CloudItem.Backup -> R.string.library_cloud_action_backup
    is CloudItem.Analysis -> R.string.library_cloud_action_analysis
  },
)

/** Il segno del tasto sotto un problema: la Diagnostica lo stetoscopio, WireGuard la VPN, la password la chiave, il backup la nuvola. */
/** Il segno di stato sull'anello di una riga di lavoro: per ora solo l'analisi (lo stesso vocabolario della sua testata). */
private fun markOf(item: CloudItem): GalleryIconStatus? = when (item) {
  is CloudItem.Analysis -> when (val s = item.status) {
    is LibraryAnalysisStatus.Running -> GalleryIconStatus.Running
    is LibraryAnalysisStatus.Queued -> GalleryIconStatus.Waiting
    is LibraryAnalysisStatus.Paused -> if (s.reason == LibraryAnalysisWait.PREPARATION) GalleryIconStatus.Waiting else GalleryIconStatus.Paused
    is LibraryAnalysisStatus.Problem -> GalleryIconStatus.Problem
    else -> null
  }
  else -> null
}

private fun actionGlyph(item: CloudItem): ImageVector = when (item) {
  is CloudItem.Network -> when (item.status.action) {
    LibraryNetworkAction.DIAGNOSTICS -> Icons.Rounded.MonitorHeart
    LibraryNetworkAction.WIREGUARD -> Icons.Rounded.VpnLock
    LibraryNetworkAction.PASSWORD -> Icons.Rounded.Key
  }
  is CloudItem.Nas -> Icons.Rounded.MonitorHeart
  is CloudItem.Backup -> Icons.Rounded.Backup
  is CloudItem.Analysis -> AnalysisGlyph
}

/** Vero se la frase dice le stesse parole del tasto (a meno di maiuscole e punto finale): sotto la capsula non si ripete. */
private fun String?.sameWordsAs(action: String): Boolean = this != null && trim().trimEnd('.').equals(action.trim(), ignoreCase = true)

private fun actionOf(item: CloudItem, actions: LibraryCloudActions): () -> Unit = when (item) {
  is CloudItem.Network -> actions.onNetwork
  is CloudItem.Nas -> actions.onNas
  is CloudItem.Backup -> actions.onBackup
  is CloudItem.Analysis -> actions.onAnalysis
}

/** "Oggi alle 03:12", "12 ottobre 2026 alle 03:12": un istante in ora del NAS, mostrato come ora di casa. */
@Composable
private fun nasMoment(atNasMillis: Long): String {
  val at = Instant.ofEpochMilli(atNasMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
  val time = GalleryDates.time(at)
  return if (at.toLocalDate() == LocalDate.now()) {
    stringResource(R.string.nas_footer_up_to_date_today, time)
  } else {
    stringResource(R.string.nas_footer_up_to_date_day, GalleryDates.date(at), time)
  }
}

/** I byte dello spazio: "3,6 TB", "820 GB", "640 MB" (unita' decimali, come il NAS; un decimale sopra il TB e sotto i 100 GB). */
@Composable
internal fun cloudBytes(value: Long): String = when {
  value >= TeraByte -> stringResource(R.string.library_cloud_terabytes, tenths(value, TeraByte))
  value >= HundredGigaBytes -> stringResource(R.string.nas_footer_gigabytes, GalleryNumbers.format(value / GigaByte))
  value >= GigaByte -> stringResource(R.string.nas_footer_gigabytes, tenths(value, GigaByte))
  else -> stringResource(R.string.nas_footer_megabytes, GalleryNumbers.format(value / MegaByte))
}

/** [value] in [unit] con un decimale e la virgola ("3,6"). */
internal fun tenths(value: Long, unit: Long): String {
  val tenths = value * TenthsPerUnit / unit
  return GalleryNumbers.format(tenths / TenthsPerUnit) + DecimalComma + (tenths % TenthsPerUnit)
}

/** Il tag del filo divisorio del pannello: le prove verificano che non resti da solo in fondo (GF-12). */
internal const val CloudDividerTag: String = "nuvola-filo"

private const val MegaByte = 1_000_000L
private const val GigaByte = 1_000_000_000L
private const val HundredGigaBytes = 100 * GigaByte
private const val TeraByte = 1_000_000_000_000L
private const val TenthsPerUnit = 10L
private const val DecimalComma = ","

private val PanelMinWidth: Dp = 260.dp
private val PanelMaxWidth: Dp = 340.dp
private val PanelPaddingH: Dp = 16.dp
private val PanelPaddingV: Dp = 14.dp
private val PanelGap: Dp = 12.dp
private val LineGap: Dp = 6.dp
private val TitleGap: Dp = 10.dp
private val TitleGlyph: Dp = 24.dp
private val SpinnerSize: Dp = 22.dp
private val ButtonGlyph: Dp = 20.dp
private val RowMinHeight: Dp = 56.dp
private val RowGap: Dp = 12.dp
private val GaugeSize: Dp = 48.dp
private val GaugeStroke: Dp = GalleryProgressRingDefaults.stroke(GaugeSize)
private val GaugeGlyph: Dp = 22.dp
private val SyncGlyph: Dp = 26.dp
private val InlineGlyph: Dp = 18.dp
private val SmallGlyph: Dp = 14.dp
private val InlineGap: Dp = 6.dp
private val LineGapTight: Dp = 2.dp
private const val PercentScale = 100f

/** I tag delle righe del pannello per le prove. */
internal const val ActivityRowTag: String = "nuvola-lavoro"
internal const val SpaceRowTag: String = "nuvola-spazio"
internal const val ProblemDetailTag: String = "nuvola-frase"
internal const val PauseTitleTag: String = "nuvola-titolo-pausa"

/**
 * Pacchetto X, grafica XU: la riga dell'analisi nel pannello. In corso: l'anello con il percento, il conto delle pronte e il tempo (come il backup);
 * in coda o in pausa: due parole e il perche' in una riga, l'anello che tiene il percento e il segno dello stato sull'arco. L'icona e' quella
 * dell'analisi (la foto con la lente), la stessa della pagina "Analisi delle foto" e del visore.
 */
@Composable
private fun analysisNoteOf(status: LibraryAnalysisStatus): GalleryFooterNote {
  val remaining = when (status) {
    is LibraryAnalysisStatus.Running -> status.remaining
    is LibraryAnalysisStatus.Queued -> status.remaining
    is LibraryAnalysisStatus.Paused -> status.remaining
    else -> null
  }
  val rest = remaining?.takeIf { it > 0 }?.let { stringResource(R.string.library_cloud_analysis_remaining, GalleryNumbers.format(it)) }
  return when (status) {
    is LibraryAnalysisStatus.Running -> GalleryFooterNote(
      tone = GalleryFooterTone.Working,
      label = stringResource(R.string.library_cloud_analysis_running),
      glyph = AnalysisGlyph,
      detail = rest,
      progress = status.progressOrNull(),
      busy = status.progressOrNull() == null,
    )
    is LibraryAnalysisStatus.Queued -> GalleryFooterNote(
      tone = GalleryFooterTone.Waiting,
      label = stringResource(R.string.library_cloud_analysis_queued),
      glyph = AnalysisGlyph,
      detail = stringResource(R.string.library_cloud_analysis_queued_line),
      progress = status.progressOrNull(),
      description = listOfNotNull(stringResource(R.string.library_cloud_analysis_queued), rest).joinToString(". "),
    )
    is LibraryAnalysisStatus.Paused -> {
      val why = stringResource(analysisWaitRes(status.reason))
      GalleryFooterNote(
        tone = GalleryFooterTone.Waiting,
        label = stringResource(R.string.library_cloud_analysis_paused),
        glyph = AnalysisGlyph,
        detail = why,
        progress = status.progressOrNull(),
        description = listOfNotNull(stringResource(R.string.library_cloud_analysis_paused), why, rest).joinToString(". "),
      )
    }
    is LibraryAnalysisStatus.Problem -> GalleryFooterNote(
      tone = GalleryFooterTone.Attention,
      label = stringResource(R.string.library_cloud_analysis_problem),
      glyph = AnalysisGlyph,
    )
    else -> GalleryFooterNote(tone = GalleryFooterTone.Calm, label = stringResource(R.string.library_cloud_analysis_queued), glyph = AnalysisGlyph)
  }
}

/** L'icona dell'analisi delle foto: la foto con la lente (la stessa di Ricerca e modelli e del visore). */
private val AnalysisGlyph: ImageVector get() = Icons.Rounded.ImageSearch

private fun analysisWaitRes(reason: LibraryAnalysisWait): Int = when (reason) {
  LibraryAnalysisWait.NIGHT_WINDOW -> R.string.library_cloud_analysis_wait_window
  LibraryAnalysisWait.CHARGING -> R.string.library_cloud_analysis_wait_charging
  LibraryAnalysisWait.BATTERY -> R.string.library_cloud_analysis_wait_battery
  LibraryAnalysisWait.POWER_SAVER -> R.string.library_cloud_analysis_wait_power_saver
  LibraryAnalysisWait.THERMAL -> R.string.library_cloud_analysis_wait_thermal
  LibraryAnalysisWait.WIFI -> R.string.library_cloud_analysis_wait_wifi
  LibraryAnalysisWait.HOME_WIFI -> R.string.library_cloud_analysis_wait_home_wifi
  LibraryAnalysisWait.NAS -> R.string.library_cloud_analysis_wait_nas
  LibraryAnalysisWait.DATA_CAP -> R.string.library_cloud_analysis_wait_data_cap
  LibraryAnalysisWait.PREPARATION -> R.string.library_cloud_analysis_wait_preparation
  LibraryAnalysisWait.ANDROID_LIMIT, LibraryAnalysisWait.PROFILE, LibraryAnalysisWait.OTHER -> R.string.library_cloud_analysis_wait_other
}

/** Il segno di stato sull'anello di una riga di lavoro, e quanto sporge oltre l'anello. */
private val RowMark: Dp = 18.dp
private val RowMarkOverhang: Dp = 3.dp
