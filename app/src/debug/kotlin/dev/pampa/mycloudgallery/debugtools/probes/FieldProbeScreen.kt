package dev.pampa.mycloudgallery.debugtools.probes

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaRewriter
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** SOLO debug: la schermata delle prove guidate (Impostazioni → Sviluppo). */
@Serializable
data object FieldProbesRoute

/** Cosa aspetta il risultato del dialogo di sistema o dell'altra app, per sapere come finire quando torna. */
private enum class Pending { TRASH, TRASH_MANY, RESTORE, WRITE, VIEW, EDIT }

/**
 * SOLO build debug (Fase 5B, P0-B; riga P0-B del §4.4 di docs/fase5-progetto.md): la schermata delle **prove guidate** T-09 (cestino), T-14 (selettori e
 * provider dei documenti), T-15 (condivisioni in arrivo) e T-16 ("Modifica con…"). Si apre da Impostazioni → Sviluppo. Ogni passo e' una riga da toccare;
 * il resoconto in fondo e' fatto di **soli numeri** ([FieldProbeStore]) e si copia in `docs/android.md`. Le prove toccano solo le cartelle
 * `DCIM/mcg-prova` e `Pictures/mcg-prova` ([FieldProbePhone]); le richieste di sistema (cestino, scrittura) partono dal dialogo di Android e niente si
 * cancella per sempre. **Non eseguire senza l'utente**: servono il telefono, il dialogo di sistema e altre app.
 */
@Composable
internal fun FieldProbeScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { FieldProbeStore.of(context) }
  val phone = remember { FieldProbePhone(context) }
  val t09 = remember {
    val source = AndroidMediaStoreSource(context)
    FieldProbeT09(
      phone = phone,
      store = store,
      intents = AndroidPhoneMediaIntents(context),
      rewriter = MediaStorePhoneMediaRewriter(context),
      cacheDir = File(context.cacheDir, "field-probe"),
      deletedSince = { generation -> source.deletedSince(FieldProbePhone.VOLUME, generation)?.map { it.id } },
    )
  }
  val t16 = remember { FieldProbeT16(context, phone, store) }

  var report by remember { mutableStateOf(store.render()) }
  var message by remember { mutableStateOf<Int?>(null) }
  var pending by remember { mutableStateOf<Pending?>(null) }
  var form by remember { mutableStateOf(FieldProbeForm.of(store.get(FieldProbeKey.T14_FORM)).code) }
  var latency by remember { mutableStateOf(store.get(FieldProbeKey.T14_LATENCY_MS) ?: 0L) }
  var count by remember { mutableStateOf(store.get(FieldProbeKey.T14_COUNT) ?: 1L) }
  var closeFirst by remember { mutableStateOf(store.get(FieldProbeKey.T15_CLOSED_FIRST) == 1L) }

  fun refresh() {
    report = store.render()
  }

  val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    val finished = pending
    pending = null
    scope.launch {
      when (finished) {
        Pending.TRASH -> t09.finishTrash(result.resultCode, many = false)
        Pending.TRASH_MANY -> t09.finishTrash(result.resultCode, many = true)
        Pending.RESTORE -> t09.finishRestore(result.resultCode)
        Pending.WRITE -> t09.finishWrite(result.resultCode)
        else -> Unit
      }
      refresh()
    }
  }
  val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val finished = pending
    pending = null
    if (finished == Pending.EDIT) t16.finishEdit(result.resultCode, result.data)
    refresh()
  }

  fun startRequest(sender: IntentSender?, kind: Pending) {
    if (sender == null) {
      message = R.string.probe_nothing_to_do
      return
    }
    message = null
    pending = kind
    senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
  }

  FluidScreen(title = stringResource(R.string.probe_title), subtitle = stringResource(R.string.probe_subtitle), onBack = onBack) {
    // --- Ambiente ---------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_env)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.probe_env_title),
          subtitle = stringResource(R.string.probe_env_detail),
          onClick = {
            val rows = t09.environment()
            FieldProbeHandlers.measure(context, store)
            message = if (rows == 0) R.string.probe_no_rows else null
            refresh()
          },
        )
      }
    }
    message?.let { item { FluidSectionFootnote(text = stringResource(it)) } }

    // --- T-09 -------------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_t09)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.probe_t09_base_title),
          subtitle = stringResource(R.string.probe_t09_base_detail),
          onClick = {
            message = if (t09.takeBaseline() == 0) R.string.probe_no_rows else null
            refresh()
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t09_compare_title),
          subtitle = stringResource(R.string.probe_t09_compare_detail),
          onClick = {
            scope.launch {
              message = if (t09.compare()) null else R.string.probe_need_baseline
              refresh()
            }
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t09_trash_title),
          subtitle = stringResource(R.string.probe_t09_trash_detail),
          onClick = { startRequest(t09.prepareTrash(TRASH_FEW, many = false), Pending.TRASH) },
        )
        FluidListDivider()
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FluidChip(label = stringResource(R.string.probe_t09_samsung_yes), selected = store.get(FieldProbeKey.T09_TRASH_IN_SAMSUNG) == 1L, onClick = {
            t09.answerSamsungTrash(true)
            refresh()
          })
          FluidChip(label = stringResource(R.string.probe_t09_samsung_no), selected = store.get(FieldProbeKey.T09_TRASH_IN_SAMSUNG) == 0L, onClick = {
            t09.answerSamsungTrash(false)
            refresh()
          })
        }
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t09_restore_title),
          subtitle = stringResource(R.string.probe_t09_restore_detail),
          onClick = { startRequest(t09.prepareRestore(), Pending.RESTORE) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t09_many_title),
          subtitle = stringResource(R.string.probe_t09_many_detail),
          onClick = { startRequest(t09.prepareTrash(FieldProbeT09.MANY, many = true), Pending.TRASH_MANY) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t09_write_title),
          subtitle = stringResource(R.string.probe_t09_write_detail),
          onClick = { startRequest(t09.prepareWrite(), Pending.WRITE) },
        )
      }
    }

    // --- T-14 -------------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_t14)) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidSectionFootnote(text = stringResource(R.string.probe_t14_form_label))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((label, value) in listOf(R.string.probe_form_direct to 1L, R.string.probe_form_proxy to 2L, R.string.probe_form_copy to 3L)) {
            FluidChip(label = stringResource(label), selected = form == value, onClick = {
              form = value
              store.set(FieldProbeKey.T14_FORM, value)
              refresh()
            })
          }
        }
        FluidSectionFootnote(text = stringResource(R.string.probe_t14_latency_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((label, value) in listOf(R.string.probe_latency_0 to 0L, R.string.probe_latency_200 to 200L)) {
            FluidChip(label = stringResource(label), selected = latency == value, onClick = {
              latency = value
              store.set(FieldProbeKey.T14_LATENCY_MS, value)
              refresh()
            })
          }
        }
        FluidSectionFootnote(text = stringResource(R.string.probe_t14_count_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((label, value) in listOf(R.string.probe_count_1 to 1L, R.string.probe_count_3 to 3L)) {
            FluidChip(label = stringResource(label), selected = count == value, onClick = {
              count = value
              store.set(FieldProbeKey.T14_COUNT, value)
              refresh()
            })
          }
        }
      }
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.probe_t14_how_title),
          subtitle = stringResource(R.string.probe_t14_how_detail),
          onClick = { refresh() },
        )
      }
    }

    // --- T-15 -------------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_t15)) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidSectionFootnote(text = stringResource(R.string.probe_t15_mode_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FluidChip(label = stringResource(R.string.probe_t15_read_now), selected = !closeFirst, onClick = {
            closeFirst = false
            store.set(FieldProbeKey.T15_CLOSED_FIRST, 0)
            refresh()
          })
          FluidChip(label = stringResource(R.string.probe_t15_close_first), selected = closeFirst, onClick = {
            closeFirst = true
            store.set(FieldProbeKey.T15_CLOSED_FIRST, 1)
            refresh()
          })
        }
      }
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.probe_t15_how_title),
          subtitle = stringResource(R.string.probe_t15_how_detail),
          onClick = { refresh() },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t15_retest_title),
          subtitle = stringResource(R.string.probe_t15_retest_detail),
          onClick = {
            FieldProbeT15.retest(context, store)
            refresh()
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t15_clear_title),
          subtitle = stringResource(R.string.probe_t15_clear_detail),
          onClick = { store.clearShares() },
        )
      }
    }

    // --- T-16 -------------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_t16)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.probe_t16_view_title),
          subtitle = stringResource(R.string.probe_t16_view_detail),
          onClick = {
            val intent = t16.prepareView()
            if (intent == null) {
              message = R.string.probe_nothing_to_do
            } else {
              val launched = try {
                context.startActivity(intent)
                true
              } catch (_: ActivityNotFoundException) {
                false
              }
              t16.markViewLaunched(launched)
              message = if (launched) null else R.string.probe_t16_no_gallery
              refresh()
            }
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t16_compare_title),
          subtitle = stringResource(R.string.probe_t16_compare_detail),
          onClick = {
            message = if (t16.compareView()) null else R.string.probe_need_baseline
            refresh()
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t16_edit_title),
          subtitle = stringResource(R.string.probe_t16_edit_detail),
          onClick = {
            val intent = t16.prepareEdit()
            if (intent == null) {
              message = R.string.probe_nothing_to_do
            } else {
              pending = Pending.EDIT
              activityLauncher.launch(intent)
            }
          },
        )
      }
    }

    // --- Resoconto --------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_report)) }
    item {
      Text(
        text = report.ifEmpty { stringResource(R.string.probe_report_empty) },
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      )
    }
    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidButton(text = stringResource(R.string.probe_report_refresh), onClick = { refresh() }, size = FluidButtonSize.Medium, style = FluidButtonStyle.Tinted)
        FluidButton(text = stringResource(R.string.probe_report_copy), onClick = {
          copy(context, report)
          message = R.string.probe_report_copied
        }, size = FluidButtonSize.Medium)
        FluidButton(text = stringResource(R.string.probe_report_save), onClick = {
          save(context, report)
          message = R.string.probe_report_saved
        }, size = FluidButtonSize.Medium, style = FluidButtonStyle.Tinted)
      }
    }
    item {
      FluidButton(text = stringResource(R.string.probe_report_reset), onClick = {
        store.resetNumbers()
        refresh()
      }, size = FluidButtonSize.Medium, style = FluidButtonStyle.Tinted)
    }
    item { FluidSectionFootnote(text = stringResource(R.string.probe_report_footnote)) }
  }
}

private const val TRASH_FEW = 5

private fun copy(context: Context, report: String) {
  val manager = context.getSystemService(ClipboardManager::class.java)
  manager?.setPrimaryClip(ClipData.newPlainText("resoconto", report))
}

private fun save(context: Context, report: String) {
  val dir = context.getExternalFilesDir(null) ?: return
  File(dir, "field-probe-report.txt").writeText(report + "\n")
}
