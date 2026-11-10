package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCloudButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromePopoverAnchor
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCloudState
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterPill
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.design.glyph
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
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

  data class Network(val status: LibraryNetworkStatus, override val state: GalleryCloudState) : CloudItem

  data class Nas(val status: LibraryNasStatus, override val state: GalleryCloudState) : CloudItem

  data class Backup(val status: LibraryBackupStatus, override val state: GalleryCloudState) : CloudItem
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

  val showsSpace: Boolean get() = problems.isEmpty() && paused.isEmpty()
}

/**
 * La mappa dagli stati veri alla nuvola. Null = niente da dire (nessun NAS, nessun backup, la rete a posto): la nuvola non c'e'.
 *
 * - **la rete**: a posto non dice niente; il NAS che si sveglia e' un lavoro; senza rete la nuvola e' barrata; il resto e' un problema
 *   ([LibraryNetworkStatus.severity]);
 * - **il NAS**: lettura e allineamento sono lavori, "aggiornato" e' salvato, i lavori fermi sono un allarme, le aree non raggiungibili
 *   un'attenzione, la pubblicazione di prova un'attesa;
 * - **il backup**: confronto e giri sono lavori, la coda e la prova sono attese, "tutto al sicuro" e' salvato, un problema prende il
 *   tono della sua riga in [BackupFooterProblems] (allarme, attenzione, o attesa se riparte da solo). **Fermo** (OA-10) non e' mai
 *   "salvato": la pausa dell'utente ha il suo stato, il lavoro fermato da Android e' un'attesa (riprende da solo).
 *
 * Lo stato e' il piu' grave delle voci, in quest'ordine: allarme, senza rete, attenzione, in pausa, in corso, in attesa, salvato. La
 * pausa sta sopra i lavori in corso: finche' dura le foto nuove non si salvano, e solo l'utente la toglie.
 */
internal fun libraryCloud(network: LibraryNetworkStatus?, nas: LibraryNasStatus, backup: LibraryBackupStatus): LibraryCloud? {
  val items = listOfNotNull(network?.let(::networkItem), nasItem(nas), backupItem(backup))
  if (items.isEmpty()) return null
  val state = items.maxBy { severityRank(it.state) }.state
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
  LibraryNasStatus.LocalOnly -> CloudItem.Nas(status, GalleryCloudState.Waiting)
}

private fun backupItem(status: LibraryBackupStatus): CloudItem? = when (status) {
  LibraryBackupStatus.Idle -> null
  LibraryBackupStatus.TestOnly, is LibraryBackupStatus.Queued -> CloudItem.Backup(status, GalleryCloudState.Waiting)
  is LibraryBackupStatus.Comparing, is LibraryBackupStatus.FirstBackup, is LibraryBackupStatus.Running -> CloudItem.Backup(status, GalleryCloudState.Syncing)
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
  lastSync: State<Long?>,
  commands: LibraryCommands,
  actions: LibraryCloudActions,
  modifier: Modifier = Modifier,
) {
  // Cio' che TalkBack dice dopo lo stato: il problema piu' grave o, in pausa, che cosa e' in pausa.
  val problem by remember(network) { derivedStateOf { libraryCloud(network, nas.value, backup.value)?.withAction?.firstOrNull() } }
  val title = cloudTitle(state, savedByBackup = backup.value is LibraryBackupStatus.AllSafe)
  val headline = problem?.let { noteOf(it).label }
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
    val cloud = remember(network) { derivedStateOf { libraryCloud(network, nas.value, backup.value) } }.value
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
 * Il pannello del pop-up della nuvola. In cima lo stato in una frase. Poi:
 *  - i **problemi** e la **pausa** del backup, ognuno con la sua capsula tonale (ferma: e' un'etichetta), cosa succede e il tasto che
 *    porta dove si risolve. La frase sotto la capsula non ripete il tasto (GF-12: «VPN spenta», «Apri WireGuard» e poi il tasto
 *    «Apri WireGuard»);
 *  - i **lavori** in corso o in attesa: la capsula si tocca e porta alla sua pagina, e lo dice con la freccia in coda (GF-12: accanto
 *    alle capsule ferme dei problemi non si capiva quali fossero tasti);
 *  - **senza niente da sistemare**: lo spazio usato sul totale del NAS (letto all'apertura), l'ultima sincronizzazione e "Libera
 *    spazio", sotto un filo divisorio che c'e' solo se sotto c'e' qualcosa (GF-12: senza NAS e senza "Libera spazio" restava solo).
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitleGap)) {
      Icon(imageVector = cloud.state.glyph(), contentDescription = null, tint = ink, modifier = Modifier.size(TitleGlyph))
      Text(
        text = cloudTitle(cloud.state, cloud.savedByBackup),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = ink,
        modifier = Modifier.semantics { heading() },
      )
    }
    for (item in cloud.withAction) {
      val action = actionLabel(item)
      val note = noteOf(item)
      CloudNote(note = if (note.detail.sameWordsAs(action)) note.copy(detail = null) else note, onClick = null)
      FluidButton(
        text = action,
        onClick = {
          close()
          actionOf(item, actions)()
        },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
    for (item in cloud.activity) {
      CloudNote(note = noteOf(item), onClick = {
        close()
        actionOf(item, actions)()
      })
    }
    if (cloud.showsSpace) {
      val space = rememberNasSpace(commands)
      val anythingBelow = space != SpaceUi.Absent || lastSyncNasMillis != null || commands.canFreeSpace
      if (cloud.activity.isNotEmpty() && anythingBelow) FluidHairline(modifier = Modifier.testTag(CloudDividerTag))
      SpaceSection(space)
      if (lastSyncNasMillis != null) {
        Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
          Text(text = stringResource(R.string.library_cloud_last_sync), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = ink)
          Text(text = nasMoment(lastSyncNasMillis), style = MaterialTheme.typography.bodyMedium, color = ink)
        }
      }
      if (commands.canFreeSpace) {
        Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
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
          )
          Text(
            text = stringResource(R.string.library_cloud_free_space_detail),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = ink,
          )
        }
      }
    }
  }
}

/**
 * Una voce del pannello: la capsula tonale e, sotto, il dettaglio e l'avanzamento. Con [onClick] la capsula e' un tasto e lo dice
 * con la freccia in coda; senza, e' un'etichetta (il tasto della voce sta sotto).
 */
@Composable
private fun CloudNote(note: GalleryFooterNote, onClick: (() -> Unit)?) {
  Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
    GalleryFooterPill(note = note, onClick = onClick, trailing = if (onClick != null) Icons.AutoMirrored.Rounded.KeyboardArrowRight else null)
    note.progress?.let { p -> FluidProgressBar(progress = { p }, modifier = Modifier.fillMaxWidth()) }
    note.detail?.let { detail ->
      Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
        // GF-24: la capsula sopra dice gia' etichetta e dettaglio (la sua descrizione e' «etichetta. dettaglio»): TalkBack non lo rilegge dal testo a parte.
        modifier = Modifier.semantics { hideFromAccessibility() },
      )
    }
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

/** La sezione dello spazio del NAS nel pannello: il titolo e la misura (o che la si sta leggendo, o che non si legge). */
@Composable
private fun SpaceSection(shown: SpaceUi) {
  if (shown == SpaceUi.Absent) return
  val ink = MaterialTheme.colorScheme.onSurface
  Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
    Text(text = stringResource(R.string.library_cloud_space_title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = ink)
    when (shown) {
      SpaceUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitleGap)) {
        FluidSpinner(size = SpinnerSize, color = ink)
        Text(text = stringResource(R.string.library_cloud_space_loading), style = MaterialTheme.typography.bodyMedium, color = ink)
      }
      is SpaceUi.Shown -> when (val s = shown.space) {
        is LibraryNasSpace.Ready -> {
          Text(
            text = stringResource(R.string.library_cloud_space_used, cloudBytes(s.usedBytes), cloudBytes(s.totalBytes)),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = ink,
          )
          val fraction = if (s.totalBytes > 0) (s.usedBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f) else 0f
          FluidProgressBar(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        LibraryNasSpace.Unavailable -> Text(text = stringResource(R.string.library_cloud_space_unavailable), style = MaterialTheme.typography.bodyMedium, color = ink)
      }
      SpaceUi.Absent -> Unit
    }
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
  },
)

/** Vero se la frase dice le stesse parole del tasto (a meno di maiuscole e punto finale): sotto la capsula non si ripete. */
private fun String?.sameWordsAs(action: String): Boolean = this != null && trim().trimEnd('.').equals(action.trim(), ignoreCase = true)

private fun actionOf(item: CloudItem, actions: LibraryCloudActions): () -> Unit = when (item) {
  is CloudItem.Network -> actions.onNetwork
  is CloudItem.Nas -> actions.onNas
  is CloudItem.Backup -> actions.onBackup
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
private val SpinnerSize: Dp = 18.dp
