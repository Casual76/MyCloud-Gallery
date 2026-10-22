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
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Le sezioni della schermata: l'avviso di un tocco compare in fondo alla sezione del tocco, non in cima (LA-8). */
private enum class Section { ENV, T09, T14, T15, T16, JOBS, REPORT }

private class Notice(val section: Section, @StringRes val text: Int)

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
  // LA-6: le scelte che nessuno ha ancora toccato diventano esplicite, cosi' il resoconto dice quale forma e quale latenza sono state usate.
  var report by remember {
    store.seedChoices()
    mutableStateOf(store.render())
  }
  var notice by remember { mutableStateOf<Notice?>(null) }
  // LA-7: cosa si aspetta dal dialogo o dall'altra app sopravvive alla ricreazione della schermata (e alla morte del processo).
  var pendingName by rememberSaveable { mutableStateOf<String?>(null) }

  fun pendingNow(): Pending? = pendingName?.let { name -> Pending.entries.firstOrNull { it.name == name } }

  fun setPending(value: Pending?) {
    pendingName = value?.name
  }

  fun say(section: Section, text: Int?) {
    notice = text?.let { Notice(section, it) }
  }

  /** L'avviso dell'ultimo tocco, in fondo alla sezione in cui e' stato fatto (LA-8: stava sempre sotto "Ambiente"). */
  fun LazyListScope.noticeItem(section: Section) {
    val shown = notice?.takeIf { it.section == section } ?: return
    item(key = "notice-${section.name}") { FluidSectionFootnote(text = stringResource(shown.text)) }
  }
  var form by remember { mutableStateOf(FieldProbeForm.of(store.get(FieldProbeKey.T14_FORM)).code) }
  var latency by remember { mutableStateOf(store.get(FieldProbeKey.T14_LATENCY_MS) ?: 0L) }
  var count by remember { mutableStateOf(store.get(FieldProbeKey.T14_COUNT) ?: 1L) }
  var closeFirst by remember { mutableStateOf(store.get(FieldProbeKey.T15_CLOSED_FIRST) == 1L) }

  fun refresh() {
    report = store.render()
  }

  val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    val finished = pendingNow()
    setPending(null)
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
    val finished = pendingNow()
    setPending(null)
    if (finished == Pending.EDIT) t16.finishEdit(result.resultCode, result.data)
    refresh()
  }

  fun startRequest(sender: IntentSender?, kind: Pending) {
    if (sender == null) {
      say(Section.T09, R.string.probe_nothing_to_do)
      return
    }
    say(Section.T09, null)
    setPending(kind)
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
            say(Section.ENV, if (rows == 0) R.string.probe_no_rows else null)
            refresh()
          },
        )
      }
    }
    noticeItem(Section.ENV)

    // --- T-09 -------------------------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_t09)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.probe_t09_base_title),
          subtitle = stringResource(R.string.probe_t09_base_detail),
          onClick = {
            say(Section.T09, if (t09.takeBaseline() == 0) R.string.probe_no_rows else null)
            refresh()
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t09_compare_title),
          subtitle = stringResource(R.string.probe_t09_compare_detail),
          onClick = {
            scope.launch {
              say(Section.T09, if (t09.compare()) null else R.string.probe_need_baseline)
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
          onClick = {
            // D-3: su One UI le foto sono nel Cestino di Galleria; la prova lo dice e lo misura invece di "nessuna foto adatta".
            when (val start = t09.prepareRestore()) {
              is FieldProbeT09.RestoreStart.Dialog -> startRequest(start.sender, Pending.RESTORE)
              is FieldProbeT09.RestoreStart.InGalleryTrash -> say(Section.T09, R.string.probe_t09_restore_in_gallery)
              is FieldProbeT09.RestoreStart.AlreadyBack -> say(Section.T09, R.string.probe_t09_restore_already_back)
              FieldProbeT09.RestoreStart.Nothing -> say(Section.T09, R.string.probe_t09_restore_nothing)
            }
            refresh()
          },
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
    noticeItem(Section.T09)

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
    noticeItem(Section.T14)

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
              say(Section.T16, R.string.probe_nothing_to_do)
            } else {
              val launched = try {
                context.startActivity(intent)
                true
              } catch (_: ActivityNotFoundException) {
                false
              }
              t16.markViewLaunched(launched)
              say(Section.T16, if (launched) null else R.string.probe_t16_no_gallery)
              refresh()
            }
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_t16_compare_title),
          subtitle = stringResource(R.string.probe_t16_compare_detail),
          onClick = {
            say(Section.T16, if (t16.compareView()) null else R.string.probe_need_baseline)
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
              say(Section.T16, R.string.probe_nothing_to_do)
            } else {
              setPending(Pending.EDIT)
              activityLauncher.launch(intent)
            }
          },
        )
      }
    }
    noticeItem(Section.T16)

    // --- Lavori periodici (gruppo B, punto 9) -----------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.probe_section_jobs)) }
    item {
      FluidListGroup {
        FieldProbePeriodicJob.ALL.forEachIndexed { index, job ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = stringResource(jobTitle(job)),
            subtitle = stringResource(jobDetail(job)),
            onClick = {
              FieldProbePeriodicJobs.enqueue(context, listOf(job))
              say(Section.JOBS, R.string.probe_jobs_queued)
            },
          )
        }
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.probe_jobs_all_title),
          subtitle = stringResource(R.string.probe_jobs_all_detail),
          onClick = {
            FieldProbePeriodicJobs.enqueue(context, FieldProbePeriodicJob.ALL)
            say(Section.JOBS, R.string.probe_jobs_queued)
          },
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.probe_jobs_footnote)) }
    noticeItem(Section.JOBS)

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
          say(Section.REPORT, R.string.probe_report_copied)
        }, size = FluidButtonSize.Medium)
        FluidButton(text = stringResource(R.string.probe_report_save), onClick = {
          save(context, report)
          say(Section.REPORT, R.string.probe_report_saved)
        }, size = FluidButtonSize.Medium, style = FluidButtonStyle.Tinted)
      }
    }
    item {
      FluidButton(text = stringResource(R.string.probe_report_reset), onClick = {
        // LA-6: si azzerano le misure, non le scelte (forma, latenza, file, modo di T-15): la schermata le mostra ancora e i componenti le usano.
        store.resetNumbers()
        store.seedChoices()
        form = FieldProbeForm.of(store.get(FieldProbeKey.T14_FORM)).code
        latency = store.get(FieldProbeKey.T14_LATENCY_MS) ?: 0L
        count = store.get(FieldProbeKey.T14_COUNT) ?: 1L
        closeFirst = store.get(FieldProbeKey.T15_CLOSED_FIRST) == 1L
        refresh()
      }, size = FluidButtonSize.Medium, style = FluidButtonStyle.Tinted)
    }
    noticeItem(Section.REPORT)
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

/** I testi di una riga di "Lavori periodici". */
@StringRes
private fun jobTitle(job: FieldProbePeriodicJob): Int = when (job) {
  FieldProbePeriodicJob.TRASH -> R.string.probe_job_trash
  FieldProbePeriodicJob.HQ -> R.string.probe_job_hq
  FieldProbePeriodicJob.PREPARATION -> R.string.probe_job_prep
  FieldProbePeriodicJob.SYNC -> R.string.probe_job_sync
  FieldProbePeriodicJob.SCAN -> R.string.probe_job_scan
  FieldProbePeriodicJob.BACKUP -> R.string.probe_job_backup
}

@StringRes
private fun jobDetail(job: FieldProbePeriodicJob): Int = when (job) {
  FieldProbePeriodicJob.TRASH -> R.string.probe_job_trash_detail
  FieldProbePeriodicJob.HQ -> R.string.probe_job_hq_detail
  FieldProbePeriodicJob.PREPARATION -> R.string.probe_job_prep_detail
  FieldProbePeriodicJob.SYNC -> R.string.probe_job_sync_detail
  FieldProbePeriodicJob.SCAN -> R.string.probe_job_scan_detail
  FieldProbePeriodicJob.BACKUP -> R.string.probe_job_backup_detail
}
