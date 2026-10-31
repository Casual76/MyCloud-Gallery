package dev.pampa.mycloudgallery.core.release.keys

import dev.pampa.mycloudgallery.core.release.codec.EcdsaP256
import dev.pampa.mycloudgallery.core.release.contract.PinnedKey
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import java.util.Base64

// LE CHIAVI DI PUBBLICAZIONE FISSATE NELL'APP (docs/fase10-progetto.md D31, §2.8 "Le chiavi nell'app"; C440, C442). P1.
//
// **K1** attiva e **K2** di riserva: le due chiavi pubbliche P-256 come SubjectPublicKeyInfo in base64, con l'impronta (SHA-256 dello SPKI in
// esadecimale) scritta anche in `docs/sicurezza.md` §5.6 (`ReleaseKeysTest` confronta codice e documento). Le private non sono MAI qui ne' nel
// repository: le genera l'admin sul PC con `ManifestTool keys` (PKCS#12 con frase segreta in `%USERPROFILE%\.mycloudgallery\release\`, piu'
// la copia offline e il foglio di K2).
//
// **Oggi sono SEGNAPOSTO** ([PROVISIONAL]): due punti della curva derivati da una frase con SHA-256 ("nothing up my sleeve", la derivazione
// e' in `ReleaseKeysTest`), di cui **nessuno conosce la chiave privata**. Un'app con i segnaposto non accetta nessun manifest vero (nessun
// effetto, come un manifest assente): e' sicuro, ma non c'e' il kill switch. L'utente genera K1 e K2 (`docs/decisioni.md`, "Fase 10 P1",
// "Cosa deve fare l'utente"), incolla qui le due righe che lo strumento stampa, mette [PROVISIONAL] a false e aggiorna le impronte nel
// documento; `release.ps1` (P12) deve rifiutare una release con [PROVISIONAL] vero.
//
// Unico posto, insieme a `testing/`, dove si costruisce una [PinnedKey] (Phase10ArchitectureTest, regola 3). KT, la chiave di prova, NON e'
// qui: sta in `testing/ReleaseTestKeys` e la fida solo la build debug (`ReleaseTrust.forChannel`).

object ReleaseKeys {
  /** Vero finche' K1 e K2 sono i segnaposto senza chiave privata: nessun manifest vero puo' essere accettato. */
  const val PROVISIONAL: Boolean = true

  private const val K1_SPKI: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3C6IsoK2I9EaCqWIr154e623IYYdPRaozngdcy6cKOfShgu2jvmOU00bP4Y1HR8MweUEScHMesFqPsHUVQu/2g=="
  private const val K1_FINGERPRINT: String = "c1d4f6308984503d52d8d0929d4aebb3e430356ff85e47de60f3e3bf42b68fdf"

  private const val K2_SPKI: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3oDPdI6m0tH21sWKTjiTnfhj2M9LWzNCCEilLOJB2z6cZcDr0XWFyOXi7l9AGefAPNkNaj14b5H5rfwCkZqZnA=="
  private const val K2_FINGERPRINT: String = "375ec283667d649173a9f731370939b58f3faa274c2d9cac2a9c9d413b64587d"

  /** La chiave attiva: firma ogni manifest finche' e' buona. */
  val K1: PinnedKey = pinned(ReleaseKeyIds.K1, K1_SPKI, K1_FINGERPRINT)

  /** La chiave di riserva, offline: si usa solo se K1 e' persa o rubata (D31, E-REL8). */
  val K2: PinnedKey = pinned(ReleaseKeyIds.K2, K2_SPKI, K2_FINGERPRINT)

  /** Le chiavi di una build di release (beta e stabile). */
  val ALL: List<PinnedKey> = listOf(K1, K2)

  /**
   * Una chiave fissata da [spkiBase64] (SPKI P-256 non compresso, punto della curva) e dalla sua [fingerprint]: se non tornano, lancia (un
   * errore di chi scrive il codice, che i test trovano prima di ogni build).
   */
  internal fun pinned(id: Int, spkiBase64: String, fingerprint: String): PinnedKey {
    val spki = Base64.getDecoder().decode(spkiBase64)
    require(EcdsaP256.isValidSpki(spki)) { "La chiave $id non e' uno SPKI P-256 valido." }
    require(EcdsaP256.sha256Hex(spki) == fingerprint) { "L'impronta della chiave $id non torna." }
    return PinnedKey(id, spki, fingerprint)
  }
}
