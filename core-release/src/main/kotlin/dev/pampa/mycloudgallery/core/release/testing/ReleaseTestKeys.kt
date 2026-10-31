package dev.pampa.mycloudgallery.core.release.testing

import dev.pampa.mycloudgallery.core.release.contract.PinnedKey
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.keys.ReleaseKeys
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust

/**
 * **KT**, la chiave di PROVA del manifest (docs/fase10-progetto.md D31, §2.8 "Le chiavi nell'app"; P1). Fidata **solo** nella build debug
 * (`app/src/debug` passa [DEBUG_TRUST] alla fabbrica, e `ReleaseTrust.forChannel` la toglie a ogni canale che non sia `DEBUG`) e nei test;
 * gli agenti firmano solo con lei (`ManifestTool sign --test-key`), mai con K1 o K2.
 *
 * Qui c'e' solo la chiave **pubblica**, come stringa: `tools/check.ps1` (passo 7i) legge da questo file le stringhe lunghe e pretende che non
 * compaiano nel dex della release (con il nome della classe, passo 7b). La privata sta **solo** sotto `core-release/src/test/` (il passo 3b
 * rifiuta una chiave privata altrove): e' una chiave di prova, pubblica per natura, che non apre niente su una release.
 */
object ReleaseTestKeys {
  /** Lo SPKI di KT in base64 (lo cerca il passo 7i). */
  const val KT_SPKI: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFxCWSqh8zrs3vVH0nW8RRTZRG4cEIccpVxgKcbUIvEc4FXjaVn8WDOL/UB7ogTMYea5ZFp0o+Xn4WTfSksjaVw=="

  /** L'impronta di KT (SHA-256 dello SPKI), anche in `docs/sicurezza.md` §5.6. */
  const val KT_FINGERPRINT: String = "d25e790837fef69cf64149a11778e5dae54d6e95a926ea02db285e1b6bf20d04"

  val KT: PinnedKey = ReleaseKeys.pinned(ReleaseKeyIds.KT, KT_SPKI, KT_FINGERPRINT)

  /** K1, K2 e KT: la fiducia della build debug (KT vale solo con il canale `DEBUG`). */
  val DEBUG_TRUST: ReleaseTrust = ReleaseTrust(ReleaseKeys.ALL + KT)

  /** Un insieme di chiavi qualsiasi, per le prove (chiavi generate nel test con [ReleaseTestSigner]). */
  fun trustOf(keys: Collection<PinnedKey>): ReleaseTrust = ReleaseTrust(keys.toList())
}
