package dev.pampa.mycloudgallery.debugtools.probes

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.graph.AppGraph

/** SOLO debug: l'account attivo dell'app, per i comandi di prova (un ricevitore non si inietta senza `@AndroidEntryPoint`: si chiede all'applicazione). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FieldProbeAccountEntryPoint {
  fun graph(): AppGraph
}

/**
 * SOLO debug, PU-3 della revisione del passo unico (12/11/2026): dal passo le recinzioni D12, D30 e D34 sono spente anche nella build debug, e con
 * l'**account vero** i comandi di prova che fanno partire lavori che scrivono ("Fai ora i lavori periodici", gli scenari dei volti `f103-compute` e
 * `f103-erase`) accoderebbero lavori veri sulle share vere. Questi comandi partono solo con un account di prova (`test`, `test2`): con un altro account,
 * o finche' l'account non si conosce, sono rifiutati con il motivo. Con gli account di prova restano le recinzioni D13 (telefono) e D33 (`ShareFence`).
 */
internal object FieldProbeAccounts {

  /** Il risultato del broadcast ordinato quando l'account attivo non e' di prova (`am broadcast` lo stampa con il motivo). */
  const val RESULT_NOT_TEST_ACCOUNT: Int = 3

  /** L'account attivo dal grafo dell'app; null se non si conosce (anche quando l'applicazione non ha il grafo di Hilt, come in una prova Robolectric). */
  val FROM_GRAPH: (Context) -> String? = { context ->
    try {
      EntryPointAccessors.fromApplication(context.applicationContext, FieldProbeAccountEntryPoint::class.java).graph().account.value?.user
    } catch (e: IllegalStateException) {
      null
    } catch (e: ClassCastException) {
      null
    }
  }

  /** Chi e' l'account attivo. Le prove lo sostituiscono e lo rimettono a [FROM_GRAPH]. */
  @Volatile var active: (Context) -> String? = FROM_GRAPH

  /** Null se un comando di prova che fa scrivere puo' partire adesso; altrimenti il motivo, da mostrare a chi l'ha chiesto. */
  fun refusal(context: Context): String? {
    val account = active(context)
    return when {
      account == null -> "rifiutato: l'account attivo non si conosce ancora; i comandi di prova che scrivono partono solo con test o test2"
      !TestShares.isTestAccount(account) ->
        "rifiutato: l'account attivo non e' di prova; con l'account vero il comando scriverebbe sulle share vere (recinzioni spente dal passo unico). Entra con test o test2"
      else -> null
    }
  }
}
