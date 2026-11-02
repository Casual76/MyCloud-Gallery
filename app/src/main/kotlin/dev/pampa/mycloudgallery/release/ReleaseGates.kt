package dev.pampa.mycloudgallery.release

import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import dev.pampa.mycloudgallery.core.rules.WorkRequest

// IL MANIFEST NEI CANCELLI CHE NON SCRIVONO (Fase 10, P13, §2.8 "blocks ALL": "nessuna sessione del NAS, nessun lavoro, nessun traffico"; E-REL6). Le scritture le ferma
// `NasInputs.releaseWritesStopped` (indice, album, volti, dati personali, modelli sullo scaffale, operazioni sui file, backup); la sessione la ferma `AppGraph` (nessun
// account); qui i lavori a tappe e lo scaricamento dei modelli da Internet. Solo restrittivo: con `Normal` le regole di sempre, senza un passo in piu'.

/** Le regole di una tappa con la versione fermata: ogni lavoro a tappe si ferma (`Stop`), qualunque cosa dicano le regole. */
class ReleaseStageRules(private val inner: StageRules, private val stopped: () -> Boolean) : StageRules {
  override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision =
    if (stopped()) RuleDecision.Stop(WaitReason.ActivityDisabled) else inner.decide(request, running)

  override suspend fun isPluggedIn(): Boolean = inner.isPluggedIn()
}

/** Lo scaricamento dei modelli con la versione fermata: nessun traffico (come senza rete). */
class ReleaseModelNetworkGate(private val inner: ModelNetworkGate, private val stopped: () -> Boolean) : ModelNetworkGate {
  override suspend fun verdict(trigger: ModelTrigger, bytes: Long, source: ModelSourceKind): ModelNetworkVerdict =
    if (stopped()) ModelNetworkVerdict.Refused(ModelGateReason.OFFLINE) else inner.verdict(trigger, bytes, source)
}
