package dev.pampa.mycloudgallery.work

import dev.pampa.mycloudgallery.core.rules.run.YieldToApp
import dev.pampa.mycloudgallery.graph.AppGraph
import kotlinx.coroutines.withContext

/**
 * Il lavoro pesante di fondo che si fa da parte quando l'app e' in uso (review generale, OB-3; primo requisito dell'utente: «l'app deve
 * sempre rimanere fluida, e i caricamenti [...] non si sentono durante l'utilizzo»). Il calcolo AI, i volti e la preparazione girano nello
 * stesso processo dell'interfaccia: dentro [block], finche' l'app e' in primo piano, ogni thread su cui il lavoro passa (quelli di
 * `Dispatchers.Default` e `IO`, i thread dei motori) scende a `THREAD_PRIORITY_BACKGROUND` e i motori dei volti si aprono con meno thread
 * ([YieldToApp]); in secondo piano niente cambia e il lavoro va a ritmo pieno. Il ritmo (lotti piccoli e pause) lo decidono le regole
 * (`Pace.APP_IN_USE`, `RuleEvaluator`); questo e' il pezzo che vale anche per «Indicizza ora» e «Analizza ora», che il ritmo non lo riducono.
 *
 * Lo stato dell'app e' quello di `ProcessWatcher` (`AppGraph.appContext`): una lettura in memoria a ogni ripresa della coroutine.
 */
internal suspend fun <T> AppGraph.quietly(block: suspend () -> T): T =
  withContext(YieldToApp(appInUse = { appContext.context.value.inForeground })) { block() }

/** Anche un PhoneSyncWorker puo' sovrapporsi alla UI: usa lo stesso segnale dell'attach. */
internal suspend fun <T> dev.pampa.mycloudgallery.gallery.GalleryGraph.quietly(block: suspend () -> T): T =
  withContext(YieldToApp(appInUse = { uiForeground })) { block() }
