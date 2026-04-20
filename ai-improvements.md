# Piano di Implementazione: Ottimizzazione APK, AI Avanzata (Gemini Nano, Gemma 4, FaceNet) e Refactoring Codebase

## 1. Background & Motivazione
L'attuale app MyCloud Gallery (ottimizzata per device di fascia alta come Samsung S25) necessita di un profondo aggiornamento architetturale per l'intelligenza artificiale:
1. **Riduzione Dimensioni APK**: Attualmente, i modelli di ML Kit (Image Labeling e OCR) sono inclusi ("bundled") all'interno dell'APK, aumentandone inutilmente il peso. Devono essere resi "unbundled" e scaricati dinamicamente tramite Google Play Services.
2. **Riconoscimento Scene Avanzato**: La categorizzazione di base deve essere potenziata sfruttando le NPU dei device moderni. Sarà implementato un sistema a doppio motore ("switchabile" dall'utente):
   - **Gemini Nano**: Tramite le API di Android AICore, integrato nel sistema senza necessità di scaricare modelli aggiuntivi.
   - **Gemma 4 (Custom LLM/VLM)**: Supporto per il download on-demand e l'esecuzione locale del modello tramite MediaPipe o un inference engine TFLite custom.
3. **Riconoscimento Facciale (Identificazione)**: ML Kit nativo offre solo il rilevamento dei volti (bounding box), non il riconoscimento (chi è la persona). Verrà integrato un modello `MobileFaceNet` TFLite scaricabile dinamicamente per estrarre gli "embeddings" facciali e raggruppare i visi uguali.

## 2. Analisi Dettagliata dei Problemi nel Codebase Attuale
Durante l'ispezione della codebase, sono emerse criticità rilevanti, in particolare in `app/src/main/java/com/mycloudgallery/worker/IndexingWorker.kt`, che potrebbero causare crash (Specialmente con i media pesanti di un S25):

- **[CRITICO] Rischio OutOfMemory (OOM) in `downloadFileBytes`**:
  Il codice `webDavClient.get(path).use { it.readBytes() }` carica l'intero file in memoria RAM come array di byte. Per file di grandi dimensioni (es. foto RAW da 30-50MB), questo causerà un rapido crash OOM.
  *Soluzione*: Sostituire il download in memoria con il salvataggio su un file temporaneo su disco (`java.io.File`), e processare l'immagine leggendo dal disco tramite flussi (es. `BitmapFactory.decodeFile`).
- **[MEDIO] Errore Logico nel Clustering dei Duplicati (`groupDuplicates`)**:
  Il calcolo del `pHash` è corretto, ma la query a DB per trovare immagini simili usa l'uguaglianza esatta (`match.pHash == pHash`). Il Perceptual Hash è progettato per essere confrontato calcolando la *Distanza di Hamming* tra i due hash. Due foto identiche salvate con compressione JPEG diversa produrranno hash simili (distanza < 5) ma non sempre testualmente identici.
  *Soluzione*: Modificare la query SQL in `MediaItemDao` o filtrare i risultati calcolando la distanza di Hamming bit-a-bit.
- **[BASSO] Uso scorretto delle Coroutines in ML Kit (`suspendCoroutine`)**:
  I metodi `runImageLabeling` e `runTextRecognition` avvolgono i Google Play Tasks (`addOnSuccessListener`) con un `suspendCoroutine` manuale.
  *Soluzione*: Usare la libreria `kotlinx-coroutines-play-services` e invocare semplicemente `.await()`.
- **[BASSO] Inefficienza nel controllo Batteria (`shouldPause`)**:
  Viene registrato ripetutamente un `IntentFilter` per `ACTION_BATTERY_CHANGED` all'interno del loop del worker, allocando costantemente nuovi oggetti.

## 3. Architettura della Soluzione Proposta

### 3.1. Unbundling e Gestione Modelli AI (APK Size Reduction)
L'APK non conterrà alcun modello AI. 
1. **Modelli ML Kit (OCR & Face Detection)**:
   Si sfrutteranno i Google Play Services. Al primo avvio o all'installazione dell'app, Play Services scaricherà in background i modelli leggeri (pochi MB). Questo si ottiene dichiarando una `<meta-data>` nel Manifest e cambiando le dipendenze Gradle da `com.google.mlkit:*` a `com.google.android.gms:play-services-mlkit-*`.
2. **Modelli Custom (Gemma 4 & MobileFaceNet)**:
   Verrà creato un `ModelDownloadManager` (basato su `DownloadManager` di Android o OkHttp). 
   I modelli (es. `gemma4.task` per MediaPipe, `mobile_facenet.tflite`) verranno scaricati da un URL remoto (es. il tuo server MyCloud o un bucket) direttamente in `context.filesDir` per non incidere sull'APK, mostrando una progress bar nella UI ("Download strumenti AI in corso...").

### 3.2. Switch: Gemini Nano vs Gemma 4
La logica di analisi verrà astratta in un'interfaccia `SceneAIProvider`:
- **GeminiNanoProvider**: Utilizzerà `android.app.aicore` (o l'SDK di ML Kit Document/Image QA per Gemini Nano). Essendo su S25, la latenza sarà minima e la precisione altissima. Verrà passato un prompt fisso: *"Descrivi questa scena in dettaglio e elenca oggetti, luoghi e atmosfera."*
- **Gemma4Provider**: Utilizzerà `MediaPipe Tasks Vision / GenAI` per caricare il file `.task` scaricato in locale e richiederà inferenza. Essendo un LLM on-device, consumerà più RAM ma sarà completamente autonomo (offrirà un'alternativa se AICore non è disponibile/attivo).

### 3.3. Riconoscimento Facciale (Pipeline a 2 stadi)
Il riconoscimento facciale non si limiterà a sapere *se* c'è una faccia, ma a identificare la persona.
1. **Fase 1 (Rilevamento)**: Si usa `play-services-mlkit-face-detection` per ottenere rapidamente le coordinate (Bounding Box) del volto nella foto.
2. **Fase 2 (Estrazione Embedding)**: Si effettua un "crop" del volto dalla Bitmap, lo si ridimensiona a 112x112 pixel, e lo si passa all'interprete TFLite (con il modello scaricato `MobileFaceNet`). L'output è un vettore di float (128 o 192 dimensioni).
3. **Fase 3 (Clustering)**: L'embedding viene salvato nel database Room (`FaceEntity`). Un algoritmo di clustering (es. DBSCAN semplificato o KNN) unisce i vettori la cui distanza euclidea o similarità del coseno supera una soglia definita, creando raggruppamenti (Es: "Persona 1", "Persona 2").

## 4. Implementation Steps (Piano Operativo)

### Step 1: Modifiche Gradle e Manifest (Unbundling)
- Aggiornare `libs.versions.toml`: 
  - Rimuovere `mlkit-image-labeling` e `mlkit-text-recognition`.
  - Aggiungere `play-services-mlkit-text-recognition`, `play-services-mlkit-face-detection` (l'image labeling non serve più se passiamo ai LLM/VLM, altrimenti aggiungerlo unbundled per fallback).
  - Aggiungere dipendenze per `org.tensorflow:tensorflow-lite` e `mediapipe-tasks-genai`.
- Aggiornare `AndroidManifest.xml`:
  Aggiungere: `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="ocr,face" />`

### Step 2: Correzione e Refactoring di `IndexingWorker.kt`
- Cambiare `downloadFileBytes` affinché scriva i chunk di stream `WebDav` in un `File.createTempFile` dentro `cacheDir`.
- Modificare l'estrazione di Bitmap e Exif in modo da leggere dal file temporaneo.
- Sostituire i `suspendCoroutine` con `Task<T>.await()`.
- Riscrivere il controllo della batteria estraendo lo stato *una tantum* per batch, senza registrare l'Intent ripetutamente.
- Modificare la ricerca `groupDuplicates` in `MediaItemDao` per eseguire un calcolo bitwise nel WHERE (es. in SQLite: usando UDF se disponibili o confrontando lato Kotlin dopo aver filtrato per similarità semplice).

### Step 3: Implementazione del `ModelDownloadManager`
- Creare una classe in grado di scaricare asincronamente i modelli da un repository, controllare gli SHA-256 per l'integrità, ed estrarre i file TFLite / Task in una cartella sicura dell'app.
- Creare lo switch nelle Impostazioni App (DataStore) per scegliere tra "Gemini Nano (Veloce/Integrato)" e "Gemma 4 (Pesante/Custom)".

### Step 4: Integrazione Riconoscimento Facciale
- Creare le nuove entità DB `FaceEntity` (colonne: id, mediaId, rectX, rectY, embeddingBlob, personGroupId).
- Implementare la pipeline a 2 stadi: ML Kit (Find Faces) -> Crop -> TFLite `MobileFaceNet` (Get Embedding) -> DB.
- Creare un Worker notturno per eseguire il "Clustering" dei volti orfani raggruppandoli in cartelle logiche di identità (PersonGroupId).

### Step 5: Integrazione Scene Recognition (LLMs)
- Creare `GeminiNanoProvider`: Setup e richiesta di autorizzazione all'AICore al primo avvio, gestione della chat multimodale.
- Creare `Gemma4Provider`: Caricamento del file del modello in memoria (Mmap), setup dell'engine MediaPipe e invocazione per inferenza zero-shot.
- Aggiornare `MediaFtsDao` per accogliere le descrizioni testuali estese ("Questa foto mostra due persone in un parco con un cane...") migliorando enormemente la ricerca testuale (Search).

## 5. Verifica e Rollback
- **Verifica APK Size**: Compilare un APK di rilascio prima e dopo le modifiche Gradle per certificare il calo di dimensioni (previsto: -20/30 MB min).
- **Verifica OOM**: Far elaborare al worker un file `.dng` RAW o un video molto grande per assicurarsi che l'app non superi il limite dell'heap memory.
- **Rollback**: Qualora Gemma 4 o i modelli unbundled non fossero disponibili (es. senza Google Play Services), il sistema degraderà automaticamente offrendo all'utente solo la ricerca per metadati base ed Exif, disabilitando la UI AI.
