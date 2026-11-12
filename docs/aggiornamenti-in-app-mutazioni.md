# Le mutazioni dell'aggiornamento dentro l'app (pacchetto U, giro 1 delle critiche, 12/11/2026)

Ogni riga e' un controllo tolto (o falsato) a mano nel sorgente, la prova lanciata e l'esito: **CADE** vuol dire che almeno una prova fallisce senza
quel controllo, cioe' che il controllo e' davvero provato. Il file torna sempre com'era dopo ogni mutazione. Le mutazioni del primo giro (M01-M23)
sono rifatte sul codice del giro 1 (M18 ora e' il promemoria di «Dopo», che ha preso il posto dell'avviso una volta per versione); M24 in poi sono le
garanzie nuove del giro 1.

Come si rifanno: si applica la modifica della riga al file indicato e si lancia il comando (con il regime del Coordinatore:
`gradlew.bat --no-daemon --no-watch-fs --max-workers=2 <comando>`); la prova deve cadere; poi si rimette il file com'era. Il 12/11 le ha eseguite
in serie uno script degli appunti del giro (due lotti, un posto del semaforo ciascuno, 612 s e 534 s), che non sta nel repository.

## Il codice dell'app

| Mutazione | File | Modifica | Esito e prova che cade | Comando |
|---|---|---|---|---|
| M01 sha256 obbligatorio | `UpdateRules.kt` | tolto `!SHA256.matches(update.sha256.trim()) -> UpdateProblem.CHECKSUM_MISSING` | **CADE** GuardedUpdateInstallerTest.senzaSha256NelElencoNonSiScaricaNemmeno, UpdateRulesTest.senzaSha256NonSiScarica | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest` |
| M02 peso obbligatorio | `UpdateRules.kt` | tolto `update.sizeBytes <= 0L -> UpdateProblem.SIZE_MISSING` | **CADE** GuardedUpdateInstallerTest.senzaPesoNelElencoNonSiScaricaNemmeno, UpdateRulesTest.senzaPesoNonSiScaricaEOltreIlTettoNemmeno | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest` |
| M03 tetto del peso | `UpdateRules.kt` | tolto `update.sizeBytes > maxBytes -> UpdateProblem.TOO_LARGE` | **CADE** UpdateRulesTest.senzaPesoNonSiScaricaEOltreIlTettoNemmeno | `:feature-update:testDebugUnitTest --tests UpdateRulesTest` |
| M04 nomi ammessi | `UpdateRules.kt` | `private fun safeName(name: String): Boolean = NAME.matches(…` → `private fun safeName(name: String): Boolean = true` | **CADE** UpdateRulesTest.nomiConPercorsiOCaratteriStraniNonPassano | `:feature-update:testDebugUnitTest --tests UpdateRulesTest` |
| M05 repository delle release | `UpdateRules.kt` | tolto `update.downloadUrl != UpdateSourceConfig.downloadUrl(update.releaseTag, update.apkAsset) …` | **CADE** UpdateRulesTest.lIndirizzoDeveEssereQuelloDelRepositoryDelleRelease | `:feature-update:testDebugUnitTest --tests UpdateRulesTest` |
| M06 stessa app | `UpdateRules.kt` | tolto `archive.packageName != installed.packageName -> UpdateProblem.OTHER_APP` | **CADE** UpdateRulesTest.unApkIllegibileODiUnAltraAppNonSiInstalla, GuardedUpdateInstallerTest.unApkDiUnAltraAppNonArrivaAdAndroid | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest` |
| M07 versionCode strettamente maggiore | `UpdateRules.kt` | tolto `archive.versionCode <= installed.versionCode -> UpdateProblem.NOT_NEWER` | **CADE** GuardedUpdateInstallerTest.unApkNonPiuNuovoNonArrivaAdAndroid, UpdateRulesTest.versionCodeStrettamenteMaggiore_nienteRitorniIndietro | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest` |
| M08 stessa firma | `UpdateRules.kt` | tolto `normalized(archive.signers) != normalized(installed.signers) -> UpdateProblem.OTHER_SIGNA…` | **CADE** GuardedUpdateInstallerTest.unApkFirmatoDaUnAltroNonArrivaAdAndroid, GuardedUpdateInstallerTest.chiPassaDallEngine_passaDagliStessiControlli, UpdateRulesTest.laFirmaDeveEssereQuellaDellAppInstallata | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest` |
| M09 sha256 del file prima di Android | `GuardedUpdateInstaller.kt` | tolto `UpdateRules.checksumProblem(update.sha256, checksum.sha256(file))         ?:` | **CADE** GuardedUpdateInstallerTest.primaDellaSessioneIlFileSiRicontrolla, GuardedUpdateInstallerTest.unFileConUnAltroSha256NonArrivaAdAndroid | `:feature-update:testDebugUnitTest --tests GuardedUpdateInstallerTest` |
| M10 permesso prima dello scaricamento | `GuardedUpdateInstaller.kt` | `if (!permission.granted()) {       send(InstallStep.NeedsPe…` → `val file = try {` | **CADE** GuardedUpdateInstallerTest.senzaPermessoCiSiFermaPrimaDelloScaricamentoSenzaAprireNiente | `:feature-update:testDebugUnitTest --tests GuardedUpdateInstallerTest` |
| M11 host dell'elenco chiuso | `HostGate.kt` | tolto `if (host !in hosts) return null` | **CADE** GuardedUpdateHttpTest.unRimandoRelativoSiRisolveSullIndirizzoDiAdesso, GuardedUpdateHttpTest.unIndirizzoDiPartenzaFuoriElencoNonSiApre, GuardedUpdateHttpTest.ilCancelloAmmetteSoloHttpsVersoGliHostDellElenco | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M12 solo https | `HostGate.kt` | tolto `if (!uri.scheme.equals("https", ignoreCase = true)) return null` | **CADE** GuardedUpdateHttpTest.unRimandoRelativoSiRisolveSullIndirizzoDiAdesso, GuardedUpdateHttpTest.ilCancelloAmmetteSoloHttpsVersoGliHostDellElenco | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M13 rimando fuori elenco | `GuardedUpdateHttp.kt` | `current = HostGate.redirect(current, location, hosts)      …` → `current = location ?: current` | **CADE** GuardedUpdateHttpTest.unElencoChiRimandaFuoriElencoNonSiSegue, GuardedUpdateHttpTest.unRimandoDelloScaricamentoFuoriElencoNonSiSegue | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M14 tetto dei byte scaricati | `GuardedUpdateHttp.kt` | tolto `if (downloaded > expectedBytes) throw UpdateNetException(UpdateNetRefusal.TOO_LARGE, "Il …` | **CADE** GuardedUpdateHttpTest.unFileCheSuperaIlPesoAnnunciatoSiFermaENonResta | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M15 nessun cookie | `GuardedUpdateHttp.kt` | `if (cookieHandlerInstalled()) throw UpdateNetException(Upda…` → `Unit` | **CADE** GuardedUpdateHttpTest.conUnGestoreDeiCookieInstallatoNonSiParte | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M16 una volta ogni 24 ore | `AppUpdates.kt` | tolto `if (!manual && !UpdateRules.isDue(withContext(io) { store.lastCheckedAt() }, now())) retu…` | **CADE** AppUpdatesTest.allAperturaAlPiuUnControlloOgni24Ore, AppUpdatesTest.anchUnControlloNonRiuscitoContaPerLe24Ore_eAllAvvioNonSiVede | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M17 canale salvato | `AppUpdates.kt` | tolto `store.setChannel(channel)` | **CADE** AppUpdatesTest.ilCanaleEStabileDiSerie_laSceltaSiSalvaESiUsa | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M18 promemoria di Dopo (anche con nomi alternati) | `AppUpdates.kt` | `if (mayPrompt && promptAllowed()) promptOpen.value = true` → `if (mayPrompt) promptOpen.value = true` | **CADE** AppUpdatesTest.lAvvisoNonTornaAlternandoDueNomi | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M19 nessuna installazione senza tocco | `AppUpdates.kt` | `if (mayPrompt && promptAllowed()) promptOpen.value = true` → `if (mayPrompt && promptAllowed()) { promptOpen.va…` | **CADE** AppUpdatesTest.nienteInstallazioneSenzaIlTocco_eSuiDatiMobiliPrimaSiChiede | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M20 domanda dei dati mobili | `AppUpdates.kt` | tolto `if (!consent && metered()) {       mutable.update { it.copy(askMetered = true, install = …` | **CADE** AppUpdatesTest.wifiPoiDatiMobiliDuranteIlPermesso_alRitornoSiChiede, AppUpdatesTest.nienteInstallazioneSenzaIlTocco_eSuiDatiMobiliPrimaSiChiede | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M21 sha256 obbligatorio anche al controllo | `AppUpdates.kt` | `val problem = UpdateRules.offerProblem(clean)` → `val problem: UpdateProblem? = null` | **CADE** AppUpdatesTest.unaVersionePubblicataSenzaSha256NonSiOffreNeSiAnnuncia | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M22 rete solo in net/ | `AppUpdates.kt` | `/** Il client dell'aggiornamento:` → `private fun mutante() = java.net.URL("https://exa…` | **CADE** NoNetworkSourcesTest.nessunSorgenteDiProduzioneFuoriDaNetUsaLaRete | `:feature-update:testDebugUnitTest --tests NoNetworkSourcesTest` |
| M24 US-2 consenso per tentativo al ritorno dal permesso | `AppUpdates.kt` | `startAttempt(update, consent = current.meteredConsent)` → `startAttempt(update, consent = true)` | **CADE** AppUpdatesTest.wifiPoiDatiMobiliDuranteIlPermesso_alRitornoSiChiede | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M25 US-3 forma della versione | `UpdateRules.kt` | tolto `!VERSION.matches(update.version) -> UpdateProblem.BAD_VERSION` | **CADE** UpdateRulesTest.versioniFuoriFormaNonSiOffrono | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests PublisherManifestTest` |
| M26 US-3 note tagliate prima | `UpdateRules.kt` | `val cut = if (raw.length > MAX_NOTES_CHARS) raw.substring(0…` → `val cut = raw` | **CADE** UpdateRulesTest.leNoteSiTaglianoPrimaEPerdonoIControlliEIlBidi | `:feature-update:testDebugUnitTest --tests UpdateRulesTest` |
| M27 US-3 via controlli e bidi | `UpdateRules.kt` | tolto `ch in INVISIBLE -> Unit` | **CADE** UpdateRulesTest.leNoteSiTaglianoPrimaEPerdonoIControlliEIlBidi | `:feature-update:testDebugUnitTest --tests UpdateRulesTest` |
| M28 versione annunciata (versionName dell'APK) | `UpdateRules.kt` | tolto `expectedVersionName != null && !archive.versionName.isNullOrBlank() && archive.versionNam…` | **CADE** UpdateRulesTest.laVersioneDellApkDeveEssereQuellaAnnunciata, GuardedUpdateInstallerTest.unApkDiUnAltraVersioneNonArrivaAdAndroid | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest` |
| M29 ricontrollo prima della sessione | `GuardedUpdateInstaller.kt` | `// Di nuovo, sul file che va davvero alla sessione: fra «Pr…` → `val problem: UpdateProblem? = null` | **CADE** GuardedUpdateInstallerTest.primaDellaSessioneIlFileSiRicontrolla | `:feature-update:testDebugUnitTest --tests GuardedUpdateInstallerTest` |
| M30 UU-1 sessione solo con l'app davanti | `AppUpdates.kt` | `if (foreground()) {` → `if (true) {` | **CADE** AppUpdatesTest.conLAppDietro_siAspettaInstalla_senzaRiscaricare | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M31 UU-1 finestra di Android chiusa: Pronta | `AppUpdates.kt` | tolto `InstallStep.AwaitingUser -> backToReady()` | **non eseguita**  | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M32 UU-2 Aggiornata alla riapertura | `AppUpdates.kt` | tolto `justUpdated = installedVersion` | **CADE** AppUpdatesTest.aggiornataAllaPrimaApertura | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M33 UU-2 tentativo salvato prima della sessione | `AppUpdates.kt` | `private suspend fun commit(update: AvailableAppUpdate, file…` → `private suspend fun commit(update: AvailableAppUp…` | **CADE** AppUpdatesTest.aggiornataAllaPrimaApertura | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M34 UU-3 permesso salvato (riavvio) | `AppUpdates.kt` | `InstallStep.NeedsPermission -> saveAttempt(AttemptStage.PER…` → `is InstallStep.Failed -> clearAttempt()` | **CADE** AppUpdatesTest.ilPermessoSopravviveAlRiavvio | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M35 UU-4 versione trovata ricordata | `AppUpdates.kt` | tolto `withContext(io) { store.setOffer(SavedOffer(channel, clean)) }` | **CADE** AppUpdatesTest.laVersioneTrovataSiRicordaDopoUnRiavvio, AppUpdatesTest.ilPermessoSopravviveAlRiavvio, AppUpdatesTest.dopoRimandaDiTreGiorni_alPiuTreVolte | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M36 UU-6 annullata non e' un guasto | `GuardedUpdateInstaller.kt` | tolto `text == ABORTED -> null` | **CADE** GuardedUpdateInstallerTest.nessunaFraseDellEngineArrivaAllUtente, GuardedUpdateInstallerTest.annullataInAndroid_nonEUnGuasto_siTornaAPronta | `:feature-update:testDebugUnitTest --tests GuardedUpdateInstallerTest` |
| M37 UU-6 Riprova solo dove serve | `UpdateRules.kt` | `val retryable: Boolean get() = this in RETRYABLE` → `val retryable: Boolean get() = true` | **CADE** UpdateRulesTest.riprovaSoloDoveServe_eIlFileRestaSoloPerIGuastiDiAndroid, UpdatesScreenTest.unGuastoCheNonSiRisolveRiprovando_haSoloChiudiEAChiRivolgersi | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests UpdatesScreenTest` |
| M38 UU-6 il file resta per Riprova | `UpdateRules.kt` | `val keepsFile: Boolean get() = this == NO_SPACE // this == …` → `val keepsFile: Boolean get() = false` | **CADE** UpdateRulesTest.riprovaSoloDoveServe_eIlFileRestaSoloPerIGuastiDiAndroid, AppUpdatesTest.unGuastoCheTieneIlFile_riprovaSenzaRiscaricare, GuardedUpdateInstallerTest.unGuastoDiAndroidChiudeIlFileSoloSeServe | `:feature-update:testDebugUnitTest --tests UpdateRulesTest --tests GuardedUpdateInstallerTest --tests AppUpdatesTest` |
| M39 punto 7: rimandi a mano anche nello scaricamento | `GuardedUpdateHttp.kt` | tolto `connection.instanceFollowRedirects = false` | **non eseguita**  | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M40 punto 7: montaggio con il client avvolto | `AppUpdates.kt` | `transfer = EngineApkTransfer(AndroidAppUpdateInstaller(app,…` → `transfer = EngineApkTransfer(AndroidAppUpdateInst…` | **non eseguita**  | `:feature-update:testDebugUnitTest --tests UpdateWiringTest` |
| M41 punto 7: tetto del documento dell'elenco | `GuardedUpdateHttp.kt` | tolto `if (total > max) throw UpdateNetException(UpdateNetRefusal.TOO_LARGE, "Elenco delle versi…` | **non eseguita**  | `:feature-update:testDebugUnitTest --tests GuardedUpdateHttpTest` |
| M42 cache svuotata all'avvio | `AppUpdates.kt` | tolto `runCatching(clearCache)` | **CADE** AppUpdatesTest.allAvvioLaCacheSiSvuota | `:feature-update:testDebugUnitTest --tests AppUpdatesTest` |
| M43 UU-11 tasti da 48 dp nell'avviso | `UpdatePrompt.kt` | `size = FluidButtonSize.Large,` → `size = FluidButtonSize.Medium,` | **CADE** UpdatesScreenTest.iTastiDellAvvisoSonoAltiAlmeno48dp | `:feature-update:testDebugUnitTest --tests UpdatesScreenTest` |
| M44 UU-11 scelte spente per TalkBack | `UpdatesScreen.kt` | tolto `if (!enabled) disabled()` | **CADE** UpdatesScreenTest.mentreScarica_lAnelloEUnaBarraDAvanzamento_eLeSceltePiuTenuiESpente | `:feature-update:testDebugUnitTest --tests UpdatesScreenTest` |
| M45 UU-4 voce in cima alle Impostazioni | `SettingsScreen.kt` | tolto `item(key = "update-available") { FluidListGroup { UpdateEntryRow(summary = updateSummary,…` | **CADE** UpdateEntryTest.laVoceCompareSoloSeCollegata_eConUnaVersioneNuovaLaDice | `:feature-settings:testDebugUnitTest --tests *UpdateEntryTest` |
| M46 UU-8 versione fermata offre Aggiorna | `ReleaseRootUi.kt` | `item { FluidListGroup { update() } }` → `item { }` | **CADE** ReleaseStoppedUpdateTest.laVersioneFermataOffreAggiorna_eLaNavigazioneResteFuori, ReleaseStoppedUpdateTest.senzaUnaVersioneTrovataOffreControllaAdesso | `:app:testDebugUnitTest --tests *ReleaseStoppedUpdateTest` |

M23 del primo giro (la pagina «versione fermata» senza «Aggiorna») e' qui M46, sul codice nuovo della pagina (una figura sola).

## Gli strumenti di rilascio (PowerShell, senza Gradle)

Comando: `powershell -NoProfile -ExecutionPolicy Bypass -File tools\release\verifica-elenco-prove.ps1` (T1-T12) oppure `... versioni-prove.ps1`
(T13-T15), con la modifica applicata a `tools/release/verifica-elenco-lib.ps1` o `release-lib-core.ps1`.

| Mutazione | Esito e prova che cade |
|---|---|
| T1 asset byte per byte | CADE ["[KO] l'asset pubblicato non e' l'APK locale --  / scritto: beta: sha256"] |
| T2 firma pampa.jks | CADE ["[KO] APK firmato con un'altra chiave --  / scritto: beta: sha256"] |
| T3 un solo firmatario | CADE ['[KO] due firmatari --  / scritto: beta: sha256'] |
| T4 pacchetto | CADE ['[KO] un altro pacchetto --  / scritto: beta: sha256'] |
| T5 versionName | CADE ['[KO] versionName diverso --  / scritto: beta: sha256'] |
| T6 versionCode | CADE ['[KO] versionCode diverso da release/versions.txt --  / scritto: beta: sha256'] |
| T7 versione della voce | CADE ["[KO] la voce dice un'altra versione --  / scritto: beta: sha256"] |
| T8 repository | CADE ["[KO] l'elenco indica un altro repository --  / scritto: beta: sha256"] |
| T9 nomi sicuri | CADE ['[KO] un tag con un percorso --  / scritto: beta: sha256', '[KO] ...e senza scaricare niente'] |
| T10 sha256 presente e diverso | CADE ["[KO] sha256 nell'elenco diverso dall'APK --  / scritto:"] |
| T11 elenco cambiato | NON CADE golette e barre nelle stringhe |
| T12 forma della versione (strumento) | CADE ["[KO] una versione fuori forma (beta con una cifra) -- la voce 'beta' dice la versione '1.1.0-beta.01', non 1.1.0-beta.1 / scritto:"] |
| T13 forma della versione (versions.txt) | CADE ["[KO] 'v1.1.0' non passa", "[KO] '1.1.0+build.5' non passa", "[KO] '1.1.0.0' non passa"] |
| T14 il nome cresce | CADE ['[KO] una 1.0.0-beta.02 dopo la 1.0.0 non passa (sembrerebbe un passo indietro)', "[KO] un APK nuovo con lo stesso nome (codice piu' alto) non passa"] |
| T15 il confronto dell'app | CADE ['[KO] 1.1.0 contro 1.1.0-beta.02: 1 -- ottenuto -1', "[KO] dalla beta alla stabile omonima con un APK nuovo passa -- versions.txt: il nome 1.1.0 non e' p… |
| T11 elenco cambiato (i due controlli tolti insieme) | CADE |

T11 tolta da sola non cade: lo strumento ricontrolla l'elenco due volte (prima di comporre il testo nuovo e subito prima di sostituire il file); tolte
insieme, la prova cade (ultima riga).

## Che cosa non si prova con una mutazione sul computer

- `AndroidPackageFacts` (i certificati da `apkContentsSigners`, US-4), `AndroidInstallPermission`, la sessione di Android, il ricevitore e la
  finestra di conferma: servono un telefono e APK firmati davvero (la prova vera, beta 1 → beta 2, e' in `docs/aggiornamenti-in-app.md`).
- La posizione dell'avviso sopra la barra delle schede: la mostrano gli screenshot di `:app` (`UAggiornamento*ScreenshotTest`), non una prova.
- Lo scaricamento vero di `verifica-elenco.ps1` (`Invoke-GuardedDownload`) e la lettura con apksigner/aapt2: le prove usano finti, la rete non si
  usa qui.
