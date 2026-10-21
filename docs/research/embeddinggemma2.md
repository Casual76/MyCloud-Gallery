# EmbeddingGemma 2 dentro la nostra app Android: runtime, file, acceleratori, limiti

Data dello snapshot: 12/10/2026 (il modello e uscito il 6/10/2026, quindi quasi tutto e vecchio di 6 giorni).
Scopo: decidere la sottofase 0.3 del piano ("EmbeddingGemma 2 sui dispositivi reali").
Dispositivi target: Galaxy S25 (Snapdragon 8 Elite) e Galaxy Tab S9 SM-X710 (Snapdragon 8 Gen 2, Android 16).

Convenzione usata nel documento:

- **[V]** verificato su fonte (link accanto). Per il codice ho letto i file sorgente reali su GitHub, per i file del modello ho interrogato l'API di Hugging Face, per le librerie ho letto i POM e il contenuto degli AAR su Google Maven e Maven Central.
- **[D]** dedotto da me (da codice, da numeri o da analogia). Va confermato.
- **[?]** non trovato o da verificare sul dispositivo.

Nulla di quanto segue e stato eseguito su un telefono o su un tablet: non ho potuto misurare niente. Le uniche misure sull'S25 sono quelle che l'utente ha riferito (circa 3 immagini/s sulla NPU in Edge Gallery), che non ho potuto riprodurre ne trovare in una fonte pubblica.

---

## 0. Sintesi (le cose che cambiano le decisioni)

1. **Non serve ne MediaPipe ne ML Kit.** Esiste una API Kotlin diretta, `EmbeddingEngine` in `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (pubblicata su Google Maven il 6/10/2026). Edge Gallery invece passa da MediaPipe `UniversalEmbedder` (`com.google.mediapipe:tasks-retrieval:1.1.0`), che e un livello sopra la stessa libreria nativa. Per noi la via diretta e piu leggera e ha opzioni in piu (`outputSize` per le dimensioni MRL, `computeEmbeddingBatch`, `ModelInfo`). **[V]**
2. **Esistono tre pacchetti del modello**, non uno: testo 270M (157 MiB), testo+immagini 440M (370 MiB), completo 740M (462 MiB), piu per ciascuno una variante precompilata per ogni SoC. **Ci sono i file per i nostri due chip**: `Qualcomm_SM8750` (S25) e `Qualcomm_SM8550` (Tab S9). Non c'e bisogno dell'audio (300M) per una galleria: il 440M basta agli indicizzatori e il 270M ai dispositivi che fanno solo query. **[V]**
3. **La NPU Qualcomm non e "gratis".** Servono: il file specifico del SoC, `Backend.NPU(nativeLibraryDir)`, le librerie QNN di Qualcomm nell'APK (circa 40 MB scompattati per coprire solo S25 e Tab S9, con licenza Qualcomm separata), `minSdk 31`, solo `arm64-v8a`, `useLegacyPackaging = true`. La GPU richiede quasi niente. Google non pubblica numeri di latenza per la NPU Qualcomm su Android; i numeri pubblicati sono Pixel (TPU), S26 Ultra (CPU/GPU) e altri. Quindi **GPU contro NPU sull'S25 stesso va misurata**: ~3 img/s e meno dei circa 8 img/s pubblicati per la GPU dell'S26 Ultra (chip diverso, quindi non confrontabile, ma e un campanello). **[V] per i numeri pubblicati, [?] per il confronto**
4. **Il prefisso per le query testuali non lo aggiunge il motore, e le due documentazioni ufficiali sono in contrasto** (HF: `task: search result | query: ...`; documentazione LiteRT-LM: `task: search query | text: ...`). Edge Gallery manda il testo grezzo, senza prefisso. Va misurato sulle nostre 50 query italiane. **[V] per la discrepanza, [?] per quale sia migliore**
5. **I vettori si possono ridurre per semplice troncamento**: il motore tronca ai primi N valori e rinormalizza; 512d perde pochissimo (-0,3 su MIEB-lite), 256d perde 1,5 punti su MIEB-lite e 2,8 su MMEB, 128d degrada molto le immagini. Conviene calcolare sempre a 768 e decidere dopo cosa salvare. **[V]**
6. **Il modello vede al massimo circa 0,15 Mpx (budget 70 token) o 0,3 Mpx (budget 140 token)**, proporzioni mantenute, senza ritaglio. Una anteprima di analisi da 768 px sul lato lungo contiene gia tutto cio che il modello puo usare. Per l'OCR (altra fase) l'anteprima dovra restare piu grande. **[D da codice sorgente]**

---

## 1. Runtime e API Kotlin (punto 1)

### 1.1 Tre strade, una sola libreria nativa sotto

| Strada | Coordinate Maven | Versione | Stato | Note |
|---|---|---|---|---|
| **A. LiteRT-LM `EmbeddingEngine`** (consigliata) | `com.google.ai.edge.litertlm:litertlm-android` (Google Maven) | **0.18.0**, 6/10/2026 | disponibile | Contiene `EmbeddingEngine`, `EmbeddingEngineConfig`, `EmbeddingOptions`, `InputData`, `ModelInfo`, `Backend.NPU`. Verificato aprendo `classes.jar` dell'AAR. |
| B. MediaPipe `UniversalEmbedder` (quello di Edge Gallery) | `com.google.mediapipe:tasks-retrieval` (solo Google Maven, non Maven Central) | **1.1.0**, 6/10/2026, unica versione | disponibile | Tira dentro `tasks-core:1.1.0`, `litertlm-android:0.17.0-alpha1` (transitivo) e un vector store SQLite. Edge Gallery lo affianca a `litertlm-android:0.18.0` dichiarato esplicitamente. |
| C. ML Kit | nessuna | n/a | **non esiste ancora** (vedi sezione 8) | |

Fonti:
- Metadati Maven: [litertlm-android](https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml), [tasks-retrieval](https://dl.google.com/android/maven2/com/google/mediapipe/tasks-retrieval/maven-metadata.xml), [POM tasks-retrieval](https://dl.google.com/android/maven2/com/google/mediapipe/tasks-retrieval/1.1.0/tasks-retrieval-1.1.0.pom) **[V]**
- Note di rilascio LiteRT-LM v0.18.0: "Shipped multimodal EmbeddingGemma 2 ... across Python, Kotlin, Swift, Web, C++" ([release](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.18.0)) **[V]**
- Guida ufficiale per i modelli di embedding: [developers.google.com/edge/litert-lm/embedding_models](https://developers.google.com/edge/litert-lm/embedding_models) **[V]**

Perche la A: la B costa 14 MB (arm64, 6,6 MB compressi) di `libmediapipe_tasks_jni.so` in piu, piu `libicing.so` e `libsqlite_vector_store_jni.so` (circa 9 MB scompattati), cioe un vector store che a noi non serve (abbiamo Room e un indice nostro sul NAS), e `UniversalEmbedder` non espone la dimensione di uscita (nessun campo `outputSize` in [`UniversalEmbedderOptions.java`](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/retrieval/universalembedder/UniversalEmbedderOptions.java)). **[V]**

Dipendenze transitive di `litertlm-android:0.18.0`: `gson:2.14.0`, `kotlin-reflect:2.4.0`, `kotlinx-coroutines-android:1.11.0` ([POM](https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.pom)). Attenzione: Edge Gallery, che usa Kotlin 2.2.21, e dovuta ricorrere a `-Xskip-metadata-version-check` e a `kotlin-metadata-jvm:2.4.0` per KSP ([build.gradle.kts](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/build.gradle.kts), [libs.versions.toml](https://github.com/google-ai-edge/gallery/blob/main/Android/src/gradle/libs.versions.toml)). **[V]** Se il nuovo progetto parte da un Kotlin piu vecchio, serve allinearlo (decisione della Fase 1.1). **[?]**

### 1.2 Forma dell'API Kotlin (estratto dalla documentazione ufficiale, non codice di produzione)

Fonte: [guida embedding](https://developers.google.com/edge/litert-lm/embedding_models) e sorgenti [`EmbeddingEngine.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt), [`EmbeddingConfig.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/EmbeddingConfig.kt). **[V]**

```kotlin
val config = EmbeddingEngineConfig(
    modelPath = "...litertlm",          // oppure modelFd
    backend = Backend.GPU(),            // CPU() / GPU() / NPU(nativeLibraryDir)
    visionBackend = Backend.GPU(),      // null = encoder visivo non inizializzato
    audioBackend = null,
    cacheDir = context.cacheDir.path,   // cache delle compilazioni, accelera il 2o avvio
    maxInputLength = 128,               // sceglie la "signature" del testo (128/256/512/...)
    visionTokensPerImage = 70,          // 70 o 140
)
EmbeddingEngine(config).use { engine ->
    engine.initialize()                 // fino a ~10 s (guida Kotlin); fuori dal main thread
    val r = engine.computeEmbedding(
        listOf(InputData.Text("...")),  // oppure InputData.Image(bytes), InputData.Audio(bytes)
        EmbeddingOptions(normalize = true, outputSize = 768),
    )
    val v: FloatArray = r.embedding
}
```

Altri metodi: `computeEmbeddingBatch`, versioni `suspend`/callback (`computeEmbeddingAsync`, `computeEmbeddingBatchAsync`). L'invocazione e **serializzata** (`synchronized(lock)` dentro `computeEmbedding`): un motore = una richiesta alla volta. **[V]**

`ModelInfo.from(path)` permette di leggere prima di caricare: dimensione del vettore, signature disponibili, `maxVisionTokenBudget()`, `supportedBackends(Modality)`, `npuBrand(...)`, `socName(...)`, `minRuntimeVersion()` ([`ModelInfo.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/ModelInfo.kt)). **[V]** Utilissimo per lo spike: dice se il file e davvero quello per il nostro SoC.

### 1.3 Come lo fa Edge Gallery (con link)

Repo: [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) (Apache 2.0). Le funzioni "Instant Media Search"/"Smart Album" e "Video Moment Finder" usano EmbeddingGemma 2. **[V]**

| File | Cosa contiene |
|---|---|
| [`services/semanticretrieval/OnDeviceEmbedder.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt) | Classe `UniversalOnDeviceEmbedder`: crea `UniversalEmbedder` con `BaseOptions.setModelAssetPath`, `setDelegate(NPU/GPU/CPU)`, `NpuOptions.setDispatchLibraryDirectory(nativeLibraryDir)` e `setCompilerPluginLibraryDirectory(nativeLibraryDir)`, `setL2Normalize(true)`, `setMaxInputLength`, `setVisionTokensPerImage`. Audio sempre su CPU ("for performance and accuracy purposes for now"). **Fallback**: se l'inizializzazione fallisce si ricrea su CPU; se un'inferenza fallisce si chiude e si ripete su CPU. Usa `ModelInfo.from(path)` per leggere `maxContextTokens`, `maxVisionTokenBudget`, `inputModalities`. Isola `cacheDir` per ogni coppia (token visivi, lunghezza massima) per non far collidere le cache dei programmi compilati. |
| [`.../GemmaEmbeddingModelStore.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/GemmaEmbeddingModelStore.kt) | Cache degli embedder; dimensione 768; acceleratore predefinito: **TPU su Pixel 11, CPU su Exynos/Mali (i kernel visivi lunghi bloccano i fence EGL), GPU sugli altri**. |
| [`.../EmbedPart.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/EmbedPart.kt) | Parti ordinate Testo / Immagine (byte codificati) / Audio (PCM 16 kHz mono). |
| [`.../SemanticRetrievalService.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/SemanticRetrievalService.kt) | Vector store SQLite (`SqliteVectorStore`, dimensione 768) con ripiego in memoria; coseno calcolato a mano. |
| [`customtasks/smartalbum/SmartAlbumIndexingWorker.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt) (righe ~288-310) | Un WorkManager in primo piano: per ogni media `loadKeyframes(asset, targetDimension = 1024)` e poi **`ImageUtils.encodeTga(bitmap)`** e `addRecord(imageDataList)`. Un solo vettore per media. |
| [`customtasks/smartalbum/SmartAlbumViewModel.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumViewModel.kt) (righe ~1052-1053) | Predefiniti: `maxInputSequenceLength = 256`, `visionTokenBudget = 70`. |
| [`common/ImageUtils.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ImageUtils.kt) | Codifica un `Bitmap` in TGA non compresso a 32 bit (`getPixels` e scrittura diretta): evita compressione e decompressione JPEG. |
| [`services/photolibrary/PhotoLibraryService.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt) (righe ~1055-1140, ~1275) | Foto: `ImageDecoder` con `setTargetSize` (decodifica gia ridotta, lato lungo 1024) e ripiego `loadThumbnail`. Video: `MediaMetadataRetriever`, **solo primo e ultimo fotogramma chiave** in un unico embedding. |
| [`data/ModelAllowlist.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt), [`model_allowlists/1_0_20.json`](https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_20.json) | Mappa SoC -> file (`socToModelFiles`, chiave `Build.SOC_MODEL`) e URL `https://huggingface.co/<repo>/resolve/<commit>/<file>?download=true`. Nel file pubblico l'unica voce EmbeddingGemma-2 e il file generico `embeddinggemma-2-740m.litertlm` con `accelerators: gpu,cpu`; la NPU Qualcomm non e nel file pubblico (puo esserlo nella lista servita dall'app: **[?]**). |
| [`AndroidManifest.xml`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/AndroidManifest.xml) (righe ~163-168) | `uses-native-library` con `required="false"` per `libvndksupport.so`, `libOpenCL.so`, **`libcdsprpc.so`** (DSP/NPU Qualcomm), e le librerie `libedgetpu_*` (Pixel). |

### 1.4 Le tre richieste del punto 1

**(a) Un `Bitmap` -> vettore.** `InputData.Image(bytes)` vuole **byte di un'immagine codificata**; la documentazione del campo dice "Supported format: PNG and JPG" ([`Session.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Session.kt)) **[V]**. Il decoder nativo e `stb_image` ([`stb_image_preprocessor.cc`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/support/preprocessor/stb_image_preprocessor.cc), `stbi_load_from_memory`) **[V]**: legge JPEG/PNG/BMP/TGA, **non** HEIC, AVIF o WebP **[D]**. Edge Gallery manda TGA a 32 bit (funziona, non e documentato). Con MediaPipe si puo anche usare `embedImage(MPImage)` con `BitmapImageBuilder(bitmap)` ([guida Android](https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android)) **[V]**; guardando [`UniversalEmbedder.java`](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/retrieval/universalembedder/UniversalEmbedder.java) si vede che e un involucro sottile: converte il `Bitmap` in TGA a 32 bit (`encodeAsTga`) e chiama `EmbeddingEngine.computeEmbedding(contents, EmbeddingOptions(l2Normalize))`, cioe esattamente la strada A. Un `ByteArray` grezzo passato a `embedContent` viene trattato come immagine, salvo che abbia un'intestazione WAV. **[V]** Questo spiega perche Gallery usa TGA: e il formato scelto da MediaPipe stesso.
Conseguenza pratica: decodificare sempre con `ImageDecoder`/`BitmapFactory` (gestisce HEIC e applica l'orientamento EXIF), ridurre, poi passare JPEG ad alta qualita o PNG/TGA. `stb_image` non applica la rotazione EXIF **[D]**: l'anteprima deve gia essere raddrizzata.

**(b) Un testo -> vettore.** `InputData.Text(String)`. BOS/EOS vengono inseriti dal motore (`insertSpecialTokens` predefinito vero). **Il prefisso di compito NON viene aggiunto dal motore**: nel sorgente [`embedding_engine_impl.cc`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/runtime/core/embedding_engine_impl.cc) non compare nessun testo "task:", e il demo web di Google applica il prefisso lato app ([`app_config.ts`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/js/apps/embedding_search/src/app_config.ts), `applyQueryTemplate`). **[V]** Dettagli e conflitto fra le fonti nella sezione 7.

**(c) Fotogrammi video.** L'API Kotlin ha solo `InputData.Text/Image/Audio` ([`Session.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Session.kt)): **un video e una lista di immagini** che il chiamante estrae. **[V]** Modi di usarlo:
- *Un vettore per fotogramma*: ogni fotogramma e un'immagine a se. Flessibile (ricerca per momento, "il gol"), ma piu vettori e piu tempo.
- *Un solo vettore per piu fotogrammi*: si passa una lista di `InputData.Image` in una sola chiamata (e quello che fa Edge Gallery con 2 fotogrammi). Il limite e il contesto: per esempio con 70 token per immagine e `maxInputLength = 256` ci stanno circa 3 fotogrammi, con 1024 circa 14 **[D]** (Gallery cita proprio 256 per Smart Album e 1024 per Video Moment Finder nel commento di `getIsolatedContext`).
- La scheda del modello su HF (versione PyTorch) campiona 1 fotogramma/s, massimo 32 fotogrammi, 140 token per fotogramma ([`processor_config.json`](https://huggingface.co/google/embeddinggemma-2/raw/main/processor_config.json), [scheda](https://huggingface.co/google/embeddinggemma-2)) **[V]**; la modalita "video" nativa e elencata solo per il pacchetto 740M ([scheda LiteRT-LM](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)) **[V]**, ma non ho trovato un tipo di input video nell'API Kotlin **[?]**: cosa aggiunga il 740M rispetto al 440M su una lista di fotogrammi e da verificare.

---

## 2. File del modello (punto 2)

Tutti i dati qui sotto vengono dall'[API di Hugging Face](https://huggingface.co/api/models/litert-community/embeddinggemma-2-740m-litert-lm) e dalle schede, letti il 12/10/2026. **[V]**

### 2.1 Accesso e licenza

- **Non "gated"**: l'API HF riporta `gated: false` per `google/embeddinggemma-2` e per le tre repo `litert-community`. Il download e libero, senza account. **[V]**
- Licenza: la scheda dice **Apache 2.0** (la voce "License" rimanda a `ai.google.dev/gemma/docs/gemma_4_license`, che e il testo integrale di Apache 2.0). La stessa scheda aggiunge: "Deployments must adhere to the Gemma Prohibited Use Policy". **La pagina MediaPipe invece scrive "available under the Gemma license"** ([universal_embedder](https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder)). **[V] il contrasto**, quindi **[?]** quale testo prevalga; per un uso privato di famiglia il rischio pratico e basso, ma va annotato in `docs/sicurezza.md` o nelle note legali. Apache 2.0 consente di ridistribuire i file (per esempio metterli sul NAS) mantenendo gli avvisi.
- Le librerie `litertlm-android`, `tasks-retrieval`, `litert-npu-runtime-qualcomm` sono Apache 2.0. **La libreria QNN di Qualcomm (`com.qualcomm.qti:qnn-runtime`) ha una licenza diversa**: "Qualcomm AI Hub Model License" nel POM, con `LICENSE.pdf` dentro l'AAR ([POM 2.42.0](https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.pom)). **[V]** Da leggere prima di includerla in un APK, anche se l'app non e pubblica.

### 2.2 Pacchetti disponibili (`.litertlm`, formato unico che contiene piu sotto-modelli TFLite)

Quantizzazione (identica in tutte le repo): trasformatore e embedding **int4 per canale (QAT)**, encoder visivo **int8 per canale (QAT)**, encoder audio int2/int4/int8 misto (QAT) ([scheda 740M](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)). Non esistono varianti con altre quantizzazioni nelle repo LiteRT. **[V]** I benchmark della scheda ufficiale sono stati fatti sul checkpoint a piena precisione ("All results ... use the full-precision checkpoint"), quindi **la qualita delle versioni int4 QAT non e pubblicata** **[?]**.

| Pacchetto | Moduli | File generico (CPU/GPU) | `_Qualcomm_SM8750` (S25) | `_Qualcomm_SM8550` (Tab S9) | Repo |
|---|---|---|---|---|---|
| **Testo 270M** | testo | 164.626.432 B = **157 MiB** | 187.596.800 B = 179 MiB | 207.896.576 B = 198 MiB | [text-270m](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm) |
| **Testo+Visione 440M** | testo + immagini | 387.710.976 B = **370 MiB** | 446.611.456 B = 426 MiB | 465.993.728 B = 444 MiB | [text-vision-440m](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm) |
| **Completo 740M** | testo + immagini + audio (+ video) | 484.622.336 B = **462 MiB** | 543.522.816 B = 518 MiB | 562.905.088 B = 537 MiB | [740m](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm) |

Altre varianti per SoC esistono per Google Tensor G5/G6, MediaTek MT6991/MT6993, Intel PTL e Qualcomm QCS8275/SM8650/SM8850 (stesso schema di nome: `embeddinggemma-2-<pacchetto>_<Marca>_<SoC>.litertlm`). **[V]**

SHA-256 dei file che ci interessano (da usare per verificare i download; commit HF il 12/10/2026: 740M `24d962e9...`, 440M `e301f74d...`, 270M `9be6e8b9...`, aggiornati l'ultima volta il 6/10/2026 19:04 UTC):

| File | SHA-256 |
|---|---|
| 440M generico | `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0` |
| 440M SM8750 | `c5c05cb59833541a2d0d9374c7b50d2c3a406a4a84c85762775e5f9c6d96cd0c` |
| 440M SM8550 | `4427eecca4289555e72b297308f4c9cf97110a657de25dd2fe7946ac824f6214` |
| 270M generico | `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` |
| 270M SM8750 | `b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99` |
| 270M SM8550 | `6a5043146940e738cfa9111aa445b1cdb3dc7bbef30caef367622f14ada1a895` |
| 740M generico | `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c` |
| 740M SM8750 | `ac1973c0195dc6e28a621651f2f5e04d35fdc7a7b11cdbbe94f29f5dccb57996` |
| 740M SM8550 | `802686a865fd7350f2ce4b6fa17ce02a411f56b5b508ec2bd8cb7310b2db82e6` |

URL di download (senza autenticazione): `https://huggingface.co/litert-community/<repo>/resolve/<commit-o-main>/<file>`. Edge Gallery fissa il commit; **i file possono cambiare**: la voce nell'allowlist pubblica di Gallery fissa il commit `86b903a2...`, ma la repo oggi e al commit `24d962e9...` (modificata il 6/10 alle 19:04). Quindi: fissare commit e SHA-256 nell'app. **[V]**

Le versioni non LiteRT (`google/embeddinggemma-2`, safetensors bf16, `unsloth/`, `mlx-community/`, GGUF, ecc.) non servono per Android. **[V]**

---

## 3. Acceleratori (punto 3)

### 3.1 Quadro generale

- Un solo file `.litertlm` per CPU e GPU su tutte le piattaforme; la NPU richiede il file **specifico del SoC** (precompilato AOT). Ci sono file per `SM8750` e `SM8550`. **[V]** ([README 740M](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm), [blog Google](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/): "A single .litertlm file runs on both CPU and GPU")
- Il backend si sceglie **per modalita**: `backend` (testo), `visionBackend`, `audioBackend` ([`EmbeddingConfig.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/EmbeddingConfig.kt)). **[V]**
- Mappa dispositivi -> SoC -> file: S25 = Snapdragon 8 Elite = `SM8750`; Tab S9 = Snapdragon 8 Gen 2 = `SM8550` **[D, noto ma da confermare con `adb shell getprop ro.soc.model`, come indica la [guida NPU di LiteRT-LM](https://developers.google.com/edge/litert/next/litert_lm_npu)]**. Gallery legge `Build.SOC_MODEL`.
- Android 16 sul Tab S9: nessuna fonte specifica trovata **[?]**.

### 3.2 NPU Qualcomm: cosa serve nell'app

Tutto cio che segue e **[V]** salvo dove indicato.

1. **Backend**: `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)`; la documentazione del campo dice di usare quella directory quando le librerie NPU sono "built-in" ([`Config.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Config.kt)).
2. **Libreria di dispatch e compilatore LiteRT**: `com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0` (Google Maven, Apache 2.0, 510 KB). Contiene `libLiteRtDispatch_Qualcomm.so` (446 KB) e `libLiteRtCompilerPlugin_Qualcomm.so` (773 KB), solo `arm64-v8a`. ([POM](https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-qualcomm/2.3.0/litert-npu-runtime-qualcomm-2.3.0.pom), contenuto dell'AAR ispezionato.)
3. **Librerie QNN (Qualcomm)**: non sono nell'AAR precedente. Strade: (i) `com.qualcomm.qti:qnn-runtime` su Maven Central (versioni 2.26.0-2.51.0; 2.42.0 pesa 63 MB e contiene `libQnnHtp.so`, `libQnnSystem.so`, stub e skel Hexagon v68-v81, `libQnnHtpPrepare.so` da 84 MB scompattati), usata anche dalla [pagina LiteRT Qualcomm](https://developers.google.com/edge/litert/android/npu/qualcomm) (esempio 2.34.0); (ii) lo script `fetch_qualcomm_library.sh` con i moduli di Play Feature Delivery `qualcomm_runtime_v69 ... v81` ([guida NPU](https://developers.google.com/edge/litert/next/npu), [blog Qualcomm](https://developers.googleblog.com/en/unlocking-peak-performance-on-qualcomm-npu-with-litert/)), pensata per Google Play (noi non siamo su Play: la (i) e piu semplice). La guida LiteRT-LM cita la versione QAIRT 2.42.0.251225 come esempio ([litert_lm_npu](https://developers.google.com/edge/litert/next/litert_lm_npu)).
   - Quali librerie servono per i nostri due chip: `libQnnHtp.so` + `libQnnSystem.so` + stub e skel della versione Hexagon del SoC (guida LiteRT-LM: `libQnnHtp*Stub.so`, `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so`, `libQnnHtp*Skel.so`). Mappa SoC -> Hexagon: **SM8550 = v73, SM8750 = v79 [D: ricordata a memoria, non confermata da una fonte trovata; i moduli Google si chiamano v69, v73, v75, v79, v81]**.
   - Peso solo per S25 + Tab S9, senza `libQnnHtpPrepare`: `libQnnHtp` 2,6 MB + `libQnnSystem` 2,7 MB + (V73 stub 0,6 + skel 16,3) + (V79 stub 0,6 + skel 16,1) + dispatch 0,4 + compiler plugin 0,8 = **circa 40 MB scompattati** (circa 10 MB compressi) **[D, somma dei file dell'AAR]**. Se servisse anche `libQnnHtpPrepare.so` (la compilazione JIT sul dispositivo) si aggiungono 84 MB scompattati. Con file AOT specifici per SoC probabilmente non serve **[?]**.
   - Compatibilita fra la versione QNN usata per compilare i `.litertlm` e quella nell'APK: non documentata **[?]**. `ModelInfo.minRuntimeVersion()` riguarda LiteRT-LM, non QNN.
4. **Gradle** ([guida NPU](https://developers.google.com/edge/litert/next/npu)): `minSdk >= 31`, `ndk { abiFilters += "arm64-v8a" }` ("NPU supports only arm64-v8a"), `packaging { jniLibs { useLegacyPackaging = true } }` ("needed for Qualcomm NPU runtime libraries").
5. **Manifest**: `uses-native-library` (`android:required="false"`) per `libcdsprpc.so`, `libOpenCL.so`, `libvndksupport.so`, come in Gallery. La guida Kotlin richiede `libvndksupport.so` e `libOpenCL.so` per la GPU ([guida Kotlin](https://developers.google.com/edge/litert-lm/android)); `libcdsprpc.so` e in Gallery, la documentazione non lo cita **[V da codice, non da documentazione]**.
6. **SoC supportati da LiteRT per Qualcomm**: SM8850, SM8750, SM8650, SM8550, SM8475, SM8450 ([pagina Qualcomm](https://developers.google.com/edge/litert/next/qualcomm)). I nostri due ci sono.
7. **Controllo a runtime**: `ModelInfo.supportedBackends(Modality.TEXT)`, `npuBrand(...)` (valore `QUALCOMM`), `socName(...)`. Inoltre la guida LiteRT descrive `NpuCompatibilityChecker.Qualcomm.isDeviceSupported()` per `CompiledModel` (non per `EmbeddingEngine`) **[V]**.

### 3.3 GPU e CPU

- **GPU**: richiede solo il file generico e le voci `uses-native-library` per OpenCL. Impostazione predefinita per `ActivationDataType`: FP16 su GPU, FP32 su CPU ([`Config.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Config.kt)) **[V]**. **Attenzione**: la scheda ufficiale avverte che in `float16` il modello (PyTorch) produce NaN o embedding degradati senza errore, e raccomanda bf16/fp32 ([scheda](https://huggingface.co/google/embeddinggemma-2)) **[V]**. Non e detto che valga per i pacchetti QAT compilati per GPU (Google li misura in GPU) **[?]**, ma lo spike deve confrontare i vettori GPU con quelli CPU.
- **CPU**: sempre disponibile, ripiego di Gallery. Usa `maxrss` piu alto (694 MB testo+visione su S26 Ultra contro 427 MB in GPU) ([scheda 740M](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)). **[V]**
- **Gallery forza la CPU su Exynos/Mali** per problemi di fence EGL. Non ci riguarda (Adreno), ma dice che la GPU ha particolarita per fabbricante. **[V]**

### 3.4 Numeri pubblicati (per orientarsi, non per decidere)

Testo+immagine con 70 token visivi e testo da 128 token, media di 5 prove ([scheda 740M](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm), [blog](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/)). **[V]**

| Dispositivo | Acceleratore | Testo | Testo+immagine | Immagini/s (1/latenza) | RAM CPU (maxrss) testo+immagine |
|---|---|---|---|---|---|
| Pixel 11 Pro (XL) | TPU | 8,3 ms | 49 ms | 20,6 (indicato dal blog) | 125 MB |
| S26 Ultra | GPU | 25,9 ms | 119 ms | ~8,4 | 427 MB |
| S26 Ultra | CPU | 27,1 ms | 175 ms | ~5,7 | 694 MB |
| iPhone 18 Pro | GPU | 11,6 ms | 69,8 ms | 14,3 | 196 MB |
| Dell XPS 16 | NPU Intel | 13,3 ms | 49,8 ms | 20,1 | 394 MB |

**Non ci sono numeri per NPU Qualcomm su Android.** Il dato dell'utente (~3 img/s sull'S25 NPU) e dello stesso ordine di grandezza di una latenza di ~330 ms per immagine, 2,8 volte piu lenta della GPU dell'S26 Ultra. Possibili cause **[D]**: percorso end-to-end (decodifica, ridimensionamento, scrittura DB) incluso nella misura; budget visivo piu alto di 70; NPU Qualcomm meno efficiente della GPU per il solo encoder visivo; o inizializzazioni. Cio che si vuole sapere e il tempo del solo `computeEmbedding` su NPU, GPU e CPU **sullo stesso dispositivo**.

---

## 4. Separabilita: solo encoder testuale (punto 4)

- **Si.** Esiste un pacchetto **solo testo da 270M**: 157 MiB (generico), 179 MiB (SM8750), 198 MiB (SM8550). **[V]** ([repo](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm))
- Nei pacchetti 440M e 740M gli encoder visione e audio si caricano **solo su richiesta** ("On-Demand Modality Loading: Yes", [README](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)); nel codice Kotlin `visionBackend = null` significa nessun encoder visivo (`EmbeddingEngine.initialize` passa stringa vuota; per `EngineConfig` la documentazione dice "If null, vision executor will not be initialized"). **[V]** (comportamento esatto per `EmbeddingEngineConfig`: **[?]**)
- **RAM**: Google dichiara circa **191 MB di RAM attiva solo testo** e circa **567 MB per il multimodale completo** (Pixel 11 Pro, quantizzato; [blog Google](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/)). **[V]** Misure `maxrss` della scheda LiteRT (non confrontabili fra sistemi operativi, non includono la memoria dell'acceleratore, ricavate al secondo caricamento):

  | Dispositivo/backend | Testo | Testo+immagine | Testo+immagine+audio |
  |---|---|---|---|
  | Pixel 11 Pro TPU | 112 MB | 125 MB | 127,5 MB |
  | S26 Ultra GPU | 333 MB | 427 MB | 493 MB |
  | S26 Ultra CPU | 334 MB | 694 MB | 811 MB |

  Su Samsung la sola parte testuale pesa quindi circa **330 MB di RSS di picco**, non 190. **[V]** per i numeri, **[?]** per il Tab S9 (da misurare con PSS).
- **Latenza query testuale** (testo da 128 token): 8,3 ms (Pixel TPU), 25,9-27,1 ms (S26 Ultra GPU/CPU), 11,6 ms (iPhone GPU). **[V]** Le schede 270M e 440M ripetono gli stessi numeri del 740M: sono misure del 740M usato solo col testo, non misure separate del pacchetto 270M. **[V]** (le tabelle sono identiche)
- **Stesso spazio vettoriale?** Google dice che il modello e "modulare" con testo a 270M e encoder opzionali, e che tutte le configurazioni condividono lo spazio. Il trasformatore (130M) e gli embedding (140M) sono gli stessi in tutti e tre i pacchetti (tabella parametri della scheda). Quindi i vettori di testo del 270M **dovrebbero** coincidere con quelli del 440M/740M, a meno delle differenze di compilazione e quantizzazione. **[D]** (la stampa tecnica riassume la cosa come "tutte le configurazioni condividono un solo spazio vettoriale", per esempio [unite.ai](https://www.unite.ai/deepmind-debuts-embeddinggemma-2-mapping-five-modalities-into-one-space/); non ho verificato la frase sul testo originale di Google). **Da verificare con un confronto di coseno fra le due versioni sugli stessi testi e sulle stesse query prima di mescolare dispositivi indicizzatori (440M/740M) e dispositivi solo-query (270M) nello stesso indice.** **[?]**
- Per la nostra galleria: indicizzatori = **440M** (testo+immagini, niente audio da 300M); non-indicizzatori = **270M**. Il 740M servirebbe solo se si vuole audio dei video **[D]**.
- Nota per i profili del tablet: il modello vive nell'archivio privato dell'app, che e **per utente Android**; ogni profilo che usa la ricerca scaricherebbe una copia propria (157-198 MiB a profilo per il 270M) a meno di una cartella condivisa. **[D]** da verificare.

---

## 5. Dimensione del vettore (MRL) e precisione di memorizzazione (punto 5)

### 5.1 Dimensioni supportate e qualita

Dimensioni: **768** (nativa), **512**, **256**, **128**, tutte supportate dai pacchetti LiteRT (sizes in byte float32: 3072, 2048, 1024, 512). Il vettore ridotto deve essere **rinormalizzato**; query e documenti devono avere la stessa dimensione. **[V]** ([scheda ufficiale](https://huggingface.co/google/embeddinggemma-2), [scheda 740M](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm))

Qualita misurata da Google sul checkpoint a piena precisione (768d come riferimento):

| Dimensione | Rapporto | MTEB multiling. v2 | MIEB lite (immagini) | MMEB v2 (generale multimodale) | MSEB retrieval (audio) |
|---|---|---|---|---|---|
| 768 | 1:1 | 61,36 | 64,64 | 59,01 | 69,54 |
| 512 | 1:1,5 | 61,17 | 64,32 | 58,38 | 69,18 |
| 256 | 1:3 | 60,41 | 63,13 | 56,24 | 66,76 |
| 128 | 1:6 | 57,89 | 59,06 | 45,65 | 56,71 |

La scheda dice: "close to lossless down to 256 dimensions. 128 dimensions degrades multimodal quality substantially" **[V]**. Sono benchmark generali, non ricerca di foto personali con query italiane: **[?]** per il nostro caso.

### 5.2 Come si ottiene

- **LiteRT-LM** `EmbeddingOptions.outputSize`: il motore tiene i **primi N valori e poi normalizza** ([`embedding_engine_impl.cc`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/runtime/core/embedding_engine_impl.cc): `embedding.resize(output_size)` poi `L2Norm`). **[V]**
- **MediaPipe `UniversalEmbedder`** non ha l'opzione: si prende il vettore da 768 e lo si taglia nell'app. **[V]**
- Quindi: **troncare dopo e rinormalizzare e equivalente**. Si puo calcolare a 768, salvare a 768 (o a meno) e produrre le versioni ridotte in seguito. L'inverso non e possibile (se si salva solo 256 non si torna a 768 senza ricalcolare). **[D]**

### 5.3 Precisione di memorizzazione

- La scheda vieta `float16` per **l'inferenza** (NaN/degrado) e dice di usare bf16/fp32. Questo riguarda l'esecuzione del modello, non l'archiviazione. Un vettore gia normalizzato ha componenti nell'intervallo [-1, 1] e in float16 l'errore relativo e circa 1e-3: l'effetto sul coseno e trascurabile **[D]**. Non ho trovato una fonte con numeri su float16 o int8 per questi vettori **[?]**. Per int8 (scala per vettore) l'errore tipico e piu grande e **va misurato**.
- Dimensioni indicative per 100.000 media (calcolo mio, **[D]**):

  | Dimensione e tipo | Per vettore | 100.000 media |
  |---|---|---|
  | 768 float32 | 3072 B | 307 MB |
  | 768 float16 | 1536 B | 154 MB |
  | 512 float16 | 1024 B | 102 MB |
  | 256 float16 | 512 B | 51 MB |
  | 256 int8 (+4 B di scala) | 260 B | 26 MB |

  Se un media e un video con piu vettori, i numeri crescono.
- Proposta di partenza per lo spike: **calcolare a 768, confrontare 512 e 256 in float16 (e int8) con i 768 float32 su recall@10 sulle nostre 50 query**, e scegliere il piu piccolo che non perde piu di circa un punto. Il rischio e solo la perdita di margine: nelle gallerie familiari (decine di migliaia di media) anche 768 float16 e gestibile.

---

## 6. Input immagine: risoluzione, token, preprocessing (punto 6)

### 6.1 Budget di token

- Nei pacchetti LiteRT i budget visivi disponibili sono **70 e 140** token per immagine ("Image soft tokens: 70, 140", [scheda 740M](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)). Si imposta con `visionTokensPerImage` (per motore) o `EmbeddingOptions.visionTokensPerImage` (per chiamata). Il benchmark di Google usa **70**; Edge Gallery usa **70** per Smart Album. **[V]**
- La versione PyTorch usa **280 token** per immagine come predefinito (`vision_soft_tokens_per_image: 280`, patch 16, pooling 3x3; la scheda parla di budget da 70 a 1120) ([config.json](https://huggingface.co/google/embeddinggemma-2/raw/main/config.json), [scheda](https://huggingface.co/google/embeddinggemma-2)). **[V]** Il "280" che circolava non e un'opzione dei pacchetti LiteRT.
- Contesto: 8192 token in tutto; a 280 token per immagine sono circa 29 immagini. **[V]**
- Il testo e sempre presente: la sequenza e BOS + inizio immagine + token visivi + fine immagine + EOS. Con 70 token visivi sono circa 74 token, che entrano nella signature da 128; con 140 sono circa 144, e serve la signature da 256 (`maxInputLength = 256`). **[D]** da conferma con `ModelInfo`.

### 6.2 Preprocessing (dal codice sorgente)

[`image_preprocessor_utils.cc`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/support/preprocessor/image_preprocessor_utils.cc), [`stb_image_preprocessor.cc`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/support/preprocessor/stb_image_preprocessor.cc), [metadati `vision140`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/models/embeddinggemma2/EmbeddingMetadataProto_vision140.pbtext): **[V]**

1. Decodifica con `stb_image` e conversione a **RGB a 3 canali**.
2. **Ridimensionamento che mantiene le proporzioni, senza ritaglio**, verso un budget di pixel = `max_num_patches x 16 x 16`, con `max_num_patches = token x 9` (pooling 3x3). I lati diventano **multipli di 48** (3 x 16), arrotondati per difetto. Filtro **Catmull-Rom**, colore sRGB a 8 bit. Il ridimensionamento va **anche in su**: un'immagine piccola viene ingrandita fino al budget. **[V]**
3. Valori in [0, 1] (divisi per 255), **senza media/deviazione standard** (la configurazione PyTorch ha `do_normalize` falso). Poi patch 16x16. **[V]**

Dimensioni effettive che ne derivano (calcolo mio con lo stesso algoritmo, **[D]**):

| Proporzioni | Budget 70 token (630 patch, ~161.000 px) | Budget 140 token (1260 patch, ~323.000 px) |
|---|---|---|
| 1:1 | 384 x 384 | 528 x 528 |
| 4:3 | 432 x 336 | 624 x 480 |
| 3:2 | 480 x 288 | 672 x 432 |
| 16:9 | 528 x 288 | 720 x 384 |
| 2:1 | 528 x 240 | 768 x 384 |
| 3:1 (panorama) | 672 x 192 | 960 x 288 |

(Per confronto, il budget da 280 token della versione PyTorch darebbe circa 768 x 768, 912 x 672, 1056 x 576.)

### 6.3 Che anteprima di analisi salvare

Conclusioni **[D]**, da confermare con l'esperimento "anteprima contro originale" del piano:

- Il modello non usa mai piu di circa **0,15 Mpx (70)** o **0,3 Mpx (140)**. Un'anteprima con **lato lungo 768 px** contiene gia piu informazione di quella che il motore usa, anche a 140 token e per proporzioni normali (3:2 -> 672, 16:9 -> 720). Per panorami molto allungati (3:1) a 140 token il lato lungo effettivo e 960.
- Il costo di inferenza **non dipende dalla dimensione di ingresso** (tutto viene portato allo stesso budget), ma il costo di **decodifica** si': decodificare un JPEG da 12 Mpx con `stb_image` e lento e usa molta memoria. Passare un'anteprima gia piccola e piu veloce, e non serve ridurre due volte.
- Poiche la stessa anteprima servira all'OCR (ML Kit, Fase 0.4), che a 768 px perde testo piccolo, **la scelta e limitata dall'OCR, non dagli embedding**. Edge Gallery usa **1024 px sul lato lungo**. Per gli embedding da soli 640-768 px basterebbero.
- Requisiti dell'anteprima: orientamento EXIF gia applicato (stb non lo applica **[D]**), formato JPEG o PNG (documentati), niente HEIC/AVIF/WebP, qualita JPEG alta (>= 90) per non aggiungere artefatti. Una anteprima sotto il budget (per esempio 320 x 240) viene ingrandita: funziona ma perde dettaglio.
- Per la decodifica sul dispositivo seguire Gallery: `ImageDecoder` con `setTargetSize` e `ALLOCATOR_SOFTWARE` (decodifica gia ridotta e senza copie su GPU).

---

## 7. Prefissi per le query e per i documenti, e italiano (punto 7)

### 7.1 Cosa dicono le fonti (e dove si contraddicono)

| Fonte | Query | Documento | Note |
|---|---|---|---|
| [Scheda HF ufficiale](https://huggingface.co/google/embeddinggemma-2) e [`config_sentence_transformers.json`](https://huggingface.co/google/embeddinggemma-2/raw/main/config_sentence_transformers.json) | `task: search result \| query: {query}` | `title: {title} \| text: {content}` (`title: none` se senza titolo) | "Using the right prefix improves quality; omitting it still works but reduces precision. Prefixes apply to text only. Pass images, video, and audio without any prefix." |
| [Documentazione LiteRT-LM, embedding_models](https://developers.google.com/edge/litert-lm/embedding_models) | `task: search query \| text: {query}` | `task: search result \| text: {content}` | Anche questa dice di rifilare gli spazi iniziali e finali. Esempio di codice con `'task: search query \| text: Top places to visit in Kyoto'`. |
| Edge Gallery (Smart Album), vedi [`OnDeviceEmbedder.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt) e [`SmartAlbumSearchViewModel.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchViewModel.kt) | testo **grezzo** | immagini senza prefisso | Il demo web di Google lo chiama "Raw (Gallery default)" e offre un'alternativa "EmbeddingGemma retrieval" con il formato della scheda HF ([`app_config.ts`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/js/apps/embedding_search/src/app_config.ts)). Nel commento dice che Gallery "sets `disable_instruction_prefix`": opzione che non ho trovato in nessuna API pubblica (ne `UniversalEmbedderOptions` ne `EmbeddingEngineConfig` ne `EmbeddingOptions`) **[?]**. |

Verificato: **il motore LiteRT-LM non aggiunge prefissi** (nessuna stringa "task:" in `embedding_engine_impl.cc`, ne in `embedding_metadata.proto`) e **il livello Java di MediaPipe passa il testo cosi com'e** a `EmbeddingEngine` (`embedText` -> `InputData.Text(text)` in [`UniversalEmbedder.java`](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/retrieval/universalembedder/UniversalEmbedder.java)). **[V]** La frase del demo sul flag resta quindi non spiegata (forse un residuo di una versione precedente dell'API), ma e irrilevante: usando `EmbeddingEngine` il testo arriva al modello esattamente come lo scriviamo.

### 7.2 Cosa implica per la ricerca testo -> immagine

- Le immagini si embeddano **senza** prefisso (regola della scheda). I testi delle query possono avere il prefisso di "search result | query" o non averlo. **Non e documentato quale dia risultati migliori nel confronto testo -> immagine** **[?]**: la scheda parla di recupero di testo.
- Piano: nello spike confrontare almeno tre varianti sulle nostre 50 query: (1) testo grezzo; (2) `task: search result | query: {q}` (formato HF); (3) `task: search query | text: {q}` (formato LiteRT-LM). Eventualmente un quarto con una frase piu naturale tipo "una foto di {q}". Il risultato decide una costante dell'app.
- Il prefisso fa parte del "contratto dei vettori" del piano: cambiare scelta dopo l'indicizzazione non richiede di ricalcolare i vettori delle immagini (che non hanno prefisso), ma solo i vettori di testo. Per i documenti di testo (es. OCR indicizzato come testo, se un giorno si facesse) si dovrebbe adottare il formato `title: none | text: ...`.

### 7.3 Italiano

- La scheda dice "understands 100+ languages", con la cautela "Quality varies across languages" ([scheda](https://huggingface.co/google/embeddinggemma-2)). **[V]**
- Il blog dice che Instant Media Search supporta "multilingual queries" ([blog](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/)). **[V]**
- Non ho trovato risultati specifici per l'italiano (nessun benchmark per lingua nel materiale ufficiale; l'unico numero e MTEB multilingue v2 = 61,36 complessivo, invariato rispetto a EmbeddingGemma 1 = 61,15) **[non trovato]**. Va misurato con le nostre query: oggetti, scene, eventi, testo, colori, luoghi.

---

## 8. Stato del servizio ML Kit (punto 8)

- **Annunciato, non disponibile.** Il [blog per sviluppatori](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/) del 6/10/2026 scrive: "We are bringing these unified search capabilities to ML Kit in the coming weeks" e promette accelerazione NPU quando disponibile, aggiornamenti automatici del modello e nessuna gestione del modello ne inclusione nell'APK. Non da dettagli di integrazione. **[V]**
- Al 12/10/2026 non esiste nessuna API pubblica: la [pagina ML Kit GenAI](https://developers.google.com/ml-kit/genai) elenca solo Summarization, Proofreading, Rewriting, Image description, Speech recognition (alpha) e Prompt; **nessuna voce su embedding**; il gruppo `com.google.mlkit` su [Google Maven](https://dl.google.com/android/maven2/com/google/mlkit/group-index.xml) ha solo gli artefatti `genai-*` esistenti (nessun artefatto di embedding); le [note di rilascio ML Kit](https://developers.google.com/ml-kit/release-notes) hanno l'ultima voce del 21/7/2026 e non citano embedding. **[V]**
- Nomi di classi o dipendenze: **non trovati**. Non e dato sapere se ML Kit richiedera servizi Google Play o AICore **[?]**.
- Consiglio: **non attendere ML Kit**. Dietro un'interfaccia nostra (`ImageEmbedder`/`TextEmbedder`) si puo cambiare implementazione quando uscira. Il vantaggio promesso (nessun modello da scaricare, aggiornamenti automatici) e anche un rischio: un aggiornamento automatico del modello cambierebbe i vettori e invaliderebbe l'indice condiviso sul NAS; i vettori vanno etichettati con modello e versione (il piano gia pensa a un "contratto" sui vettori).

---

## 9. Rischi e incognite (punto 9)

| # | Rischio | Gravita | Dettaglio / mitigazione |
|---|---|---|---|
| 1 | **API giovani**: `litertlm-android` 0.18.0 (0.x, 6 giorni), `tasks-retrieval` 1.1.0 (unica versione); la documentazione LiteRT-LM e incompleta (niente coordinate Maven nella guida embedding, niente `minSdk`). Il codice sorgente e a volte piu aggiornato della documentazione. | Alta | Fissare le versioni esatte; incapsulare in un'interfaccia; tenere un test di regressione sui vettori (coseno contro vettori salvati). |
| 2 | **Modello soggetto a modifiche**: i file HF sono stati ricaricati il 6/10 dopo il commit fissato da Gallery. | Media | Fissare commit e SHA-256; ospitare la copia sul NAS (Apache 2.0 lo consente). |
| 3 | **NPU Qualcomm**: pacchetti QNN con licenza Qualcomm; versione di QNN compatibile con i file AOT sconosciuta; `useLegacyPackaging = true`; solo arm64; minSdk 31; librerie scompattate (circa 40 MB, piu 84 MB se serve `libQnnHtpPrepare`). | Alta se si vuole la NPU | Partire dalla GPU (nessuna di queste complicazioni). Provare la NPU solo se la GPU e troppo lenta o consuma troppo. |
| 4 | **Prestazioni NPU non pubblicate** per Qualcomm su Android; il dato dell'utente (~3 img/s) e basso. | Media | Misura diretta GPU/NPU/CPU, scomposta per fase. |
| 5 | **Precisione GPU (FP16 predefinito)** contro l'avviso "non usare float16" della scheda. | Media | Confronto coseno CPU-GPU-NPU sulle stesse immagini; soglia di accettazione. |
| 6 | **Qualita non pubblicata per i pacchetti int4 QAT** (i benchmark sono sul checkpoint a piena precisione) e per l'italiano su foto personali. | Media | L'esperimento di qualita del piano (100 foto, 50 query). Opzionale: riferimento bf16 da PC con `transformers`. |
| 7 | **Versioni Kotlin/AGP/KSP**: dipendenze transitive di versioni molto recenti (`kotlin-reflect 2.4.0`, coroutines 1.11.0); Gallery ha dovuto usare `-Xskip-metadata-version-check`. | Media | Verificare nella Fase 1.1 che il progetto compili con queste dipendenze. |
| 8 | **Conflitti di librerie native**: Gallery ha `packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }`. | Bassa/Media | Usando solo `litertlm-android` (strada A) il problema non dovrebbe presentarsi **[D]**. |
| 9 | **Dimensione APK** (solo arm64): `liblitertlm_jni.so` = 22 MB scompattati (9,7 MB compressi). MediaPipe aggiungerebbe circa 14 + 9 MB. Con NPU: +40 MB scompattati. Il **modello non e nell'APK**: va scaricato (157-562 MiB). | Bassa/Media | Scaricare dal NAS o da HF al primo avvio, con verifica SHA-256; elencare lo spazio nelle impostazioni. |
| 10 | **Download a runtime**: l'app ha bisogno di rete verso HF (o di un file sul NAS). Il piano vuole "nessuna telemetria": HF vede un download anonimo. | Bassa | Preferire la copia sul NAS: l'indicizzatore la scarica una volta, gli altri dispositivi la prendono dal NAS. |
| 11 | **Licenza**: Apache 2.0 sulla scheda e sul testo della licenza, ma la pagina MediaPipe dice "Gemma license" e la scheda cita la Gemma Prohibited Use Policy; QNN ha licenza propria. | Bassa (uso privato) | Registrare in un documento di licenze. |
| 12 | **Primo caricamento lento** (fino a ~10 s dichiarati; compilazione GPU/NPU alla prima volta, poi in cache nel `cacheDir`) e **un solo motore, una richiesta alla volta**. | Media | Misurare caricamento a freddo e a caldo; tenere un motore per profilo di uso (query o indicizzazione); `cacheDir` separato per configurazione. |
| 13 | **RAM**: 330-430 MB di `maxrss` su Samsung solo testo/testo+immagine, 694 MB in CPU. Su un tablet condiviso con profili e altre app puo causare il kill del processo. | Media | Misurare PSS reale; scaricare il motore quando non serve (query: caricare e scaricare). |
| 14 | **Termico e batteria** sull'indicizzazione di decine di migliaia di foto. | Media | Misure sostenute (500+ immagini) con il dispositivo in carica; il piano gia prevede "solo in carica". |
| 15 | **Modello per profilo Android** (tablet condiviso): copie multiple del file. | Bassa | Valutare una posizione condivisa o accettare il costo. |
| 16 | **Tab S9 non verificato da nessuna fonte**: esiste il file `SM8550`, ma non ho trovato prove di funzionamento NPU/GPU con EmbeddingGemma 2 su di esso. | Media | E il primo test dello spike. |

---

## 10. Raccomandazione per lo spike (sul tablet, poi sull'S25)

L'ordine parte dal tablet perche e il caso piu incerto (nessun dato pubblico, SoC piu vecchio, Android 16, profili separati). Ogni passo ha una uscita misurabile. Fare tutte le misure con dispositivo in carica **e** una serie scollegato per la batteria.

**Passo 0 - Identificare l'hardware (5 minuti).** `adb shell getprop ro.soc.model` su Tab S9 e S25; atteso `SM8550` e `SM8750`. Salvare anche versione Android, patch One UI, GPU (`adb shell dumpsys SurfaceFlinger | grep GLES`).

**Passo 1 - Mini app di prova** (modulo separato, nessuna UI della galleria): dipendenza `litertlm-android:0.18.0`, `minSdk 31`, `abiFilters arm64-v8a`. Scaricare dal Hugging Face (con permesso dell'utente) **440M generico** (370 MiB) e **270M generico** (157 MiB), verificare lo SHA-256 della tabella 2.2, copiarli nell'archivio dell'app. Stampare `ModelInfo` per entrambi: dimensione del vettore, `signatureSelection`, `maxVisionTokenBudget`, `visionSignatureSelection`, `supportedBackends`, `minRuntimeVersion`.
- *Numeri*: tempo di `ModelInfo.from`, tempo di `initialize()` a freddo (prima volta) e a caldo (con `cacheDir`).

**Passo 2 - CPU, poi GPU (nessuna libreria extra).** Un motore, 20 immagini e 20 testi.
- *Correttezza*: nessun NaN, norma uguale a 1, **coseno CPU-GPU sulle stesse immagini e sugli stessi testi >= 0,99** (se sotto, bloccare e approfondire: tema FP16).
- *Prestazioni, per fase (p50 e p95, ms)*: decodifica e ridimensionamento della foto (con `ImageDecoder`), conversione in byte, `computeEmbedding` visivo a **70** e a **140** token, `computeEmbedding` testo con `maxInputLength = 128`. Immagini/s a regime su 100 foto e su 500 foto (calo termico: confrontare le prime 50 con le ultime 50).
- *Memoria*: PSS/RSS di picco con solo testo, con testo+visione; dopo `close()`.

**Passo 3 - Parte testuale separata.** 270M contro 440M: stessi 50 testi; **coseno fra i due vettori dello stesso testo >= 0,99**, stessa classifica sulle query; RAM e latenza della query a freddo (compreso il caricamento) e a caldo.

**Passo 4 - NPU Qualcomm** (solo dopo i passi 2-3, e solo se la GPU non basta). Scaricare i file `Qualcomm_SM8550` (Tab) e `Qualcomm_SM8750` (S25) del 440M. Aggiungere `litert-npu-runtime-qualcomm:2.3.0` e le librerie QNN (prima via `com.qualcomm.qti:qnn-runtime`, versione 2.42.0 come in guida LiteRT-LM), `useLegacyPackaging = true`, `uses-native-library` per `libcdsprpc.so`/`libOpenCL.so`/`libvndksupport.so`. Leggere prima `ModelInfo.supportedBackends/npuBrand/socName`.
- *Numeri*: se si carica; ms per immagine e testo; **coseno NPU-CPU >= 0,99**; ripetere la scomposizione per fase per vedere dov'e il collo di bottiglia dei ~3 img/s sull'S25; confronto **GPU contro NPU sullo stesso dispositivo** per immagini/s e batteria (% ogni 1000 immagini); picco RAM.
- *Decisione*: se la GPU fa piu di ~5-8 img/s e la NPU non guadagna, scegliere la GPU e rimandare la NPU (evita 40 MB di librerie, licenza Qualcomm e fragilita). Riferimento: 3 img/s = 9,3 ore per 100.000 media, 8 img/s = 3,5 ore, 20 img/s = 1,4 ore.

**Passo 5 - Qualita (100+ foto, 50 query italiane etichettate, come da piano).** Metriche: recall@5, recall@10, MRR (e una nota qualitativa sui risultati di testo e colori).
- Varianti, una alla volta rispetto a una base (440M, 140 token, anteprima 1024 px, 768d float32, query grezza):
  1. **prefisso della query**: grezza / formato HF / formato LiteRT-LM;
  2. **token visivi**: 70 contro 140;
  3. **anteprima**: originale (o 2048 px) / 1024 / 768 / 512 px;
  4. **dimensione e precisione**: 768/512/256/128 x float32/float16/int8;
  5. **GPU/CPU/NPU**: stessi punti, per vedere se la differenza numerica cambia la classifica;
  6. (facoltativo) 740M contro 440M sullo stesso insieme, per capire se serve il modulo audio/video.
- Uscita: tabella con le scelte per `docs/decisioni.md`: risoluzione dell'anteprima di analisi, token visivi, prefisso, dimensione e precisione dei vettori, backend per dispositivo, indicizzatore consigliato (S25 e/o tablet), e se i non-indicizzatori usano il 270M.

**Passo 6 - Video (facoltativo, solo dopo il resto).** Su 10 video: (a) un vettore per fotogramma ogni N secondi; (b) un solo vettore con 3 fotogrammi a `maxInputLength = 256`; misurare tempo e qualita su 10 query del tipo "video di Natale". Capire se il pacchetto 740M ha vantaggi sui video.

**Numeri da riportare in `docs/decisioni.md` a fine spike (per dispositivo)**:
immagini/s a regime (p50/p95, GPU, NPU, CPU) a 70 e 140 token; latenza query testuale a caldo e a freddo; tempo di caricamento a freddo e a caldo; RAM (PSS) solo testo e testo+visione; calo termico dopo 500 immagini; % batteria ogni 1000 immagini; coseno CPU-GPU-NPU; recall@10 per variante.

**Cose da tenere fuori dallo spike**: audio, ML Kit (non esiste), MobileCLIP2/SigLIP2 (piano B del piano: valutare solo se i passi 2-5 falliscono).

---

## 11. Nota del 21/10/2026 (progetto della Fase 6)

Verifiche rifatte il 21/10/2026 per il progetto della Fase 6 (`docs/fase6-progetto.md`, §2.1 e §10). **[V]** su fonte, salvo dove
indicato.

- **Libreria**: `litertlm-android` e ora alla **0.18.1** (20/10/2026). Corregge un'incompatibilita ABI di EmbeddingGemma 2 nelle
  build da sorgente. La scelta del §1 (strada A, `EmbeddingEngine` diretto) non cambia: la 0.18.1 e la versione che l'app fissa
  (D2 della Fase 6), al posto della 0.18.0 su cui e scritto questo documento.
- **File dei modelli invariati dal 6/10/2026**: commit del 270M `9be6e8b9…` (`9be6e8b90982095dc05c2bd162e4b954ee4dbac7`) e del
  440M `e301f74d…` (`e301f74d5551b0c2641bd5cb4652a76239d5c5f8`), stesse dimensioni e stessi SHA-256 del §2.2, licenza Apache 2.0,
  non "gated". Sono i commit che il catalogo dell'app fissa.
- **Gli host di scaricamento di Hugging Face cambiano.** `huggingface.co` risponde con una redirezione verso un CDN il cui nome
  varia nel tempo: `cdn-lfs*.hf.co` (per esempio `cdn-lfs.hf.co`, `cdn-lfs-us-1.hf.co`), `cas-bridge.xethub.hf.co` (l'archivio
  Xet) e altri. Per questo l'app ammette per i modelli i **suffissi** `.hf.co` e `.huggingface.co` (piu `huggingface.co` stesso) e
  non nomi esatti: un elenco di nomi esatti si romperebbe al primo cambio di CDN. L'integrita non dipende dall'host: la danno
  dimensione e SHA-256 fissati nell'app e verificati a ogni caricamento; le redirezioni si seguono a mano, al massimo 5, solo verso
  host ammessi e solo `https`. Fonti: §10 di `docs/fase6-progetto.md`.
- **Scaricamento del 270M del 21/10/2026**, dall'indirizzo
  `https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm`:
  **164.626.432 byte**, **SHA-256 `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` verificato** (coincide con il
  §2.2). Il file e tenuto **fuori dal repository**, in `%USERPROFILE%\.mycloudgallery\models\embeddinggemma-2-text-270m\`, solo per
  il test strumentato di P0 (quello che inizializza il motore 270M); non entra nell'APK.
- **Il 440M non e stato scaricato** (non approvato): valgono i valori del §2.2, verificati con l'API di Hugging Face, e il suo
  primo scaricamento aspetta il consenso.

---

## Appendice: fonti principali

- Scheda LiteRT 740M: https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm (e [440M](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm), [270M](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm))
- Scheda ufficiale Google: https://huggingface.co/google/embeddinggemma-2
- Blog Google: https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/
- Blog sviluppatori (ML Kit, MediaPipe, NPU): https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/
- Guida embedding LiteRT-LM: https://developers.google.com/edge/litert-lm/embedding_models
- Guida Kotlin LiteRT-LM: https://developers.google.com/edge/litert-lm/android
- NPU LiteRT-LM: https://developers.google.com/edge/litert/next/litert_lm_npu
- NPU LiteRT (Qualcomm, Maven): https://developers.google.com/edge/litert/next/npu , https://developers.google.com/edge/litert/next/qualcomm , https://developers.google.com/edge/litert/android/npu/qualcomm
- MediaPipe Universal Embedder: https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder
- Codice: https://github.com/google-ai-edge/LiteRT-LM (Kotlin in `kotlin/java/com/google/ai/edge/litertlm/`, C++ in `runtime/` e `support/`), https://github.com/google-ai-edge/gallery (`Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/`), https://github.com/google-ai-edge/mediapipe (`mediapipe/tasks/java/.../retrieval/universalembedder/`)
- Maven: https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml , https://dl.google.com/android/maven2/com/google/mediapipe/tasks-retrieval/maven-metadata.xml , https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-qualcomm/2.3.0/ , https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/maven-metadata.xml
- ML Kit: https://developers.google.com/ml-kit/genai , https://developers.google.com/ml-kit/release-notes
