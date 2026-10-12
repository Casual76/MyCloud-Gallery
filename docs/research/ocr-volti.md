# OCR e volti on-device: scelta di soluzioni, modelli e licenze (Fase 0.4)

Data della ricerca: 12/10/2026. Autore: ricerca per la sottofase 0.4 del piano (`cozy-swinging-origami.md`, §0, 0.4, Fase 8, E-A7, E-A8, E-F1..E-F6).

Convenzioni usate nel documento:

- **[V] verificato**: letto su fonte primaria (documentazione ufficiale, Maven, file LICENSE, scheda del modello, repository) in data 12/10/2026. Il link è accanto al dato.
- **[D] dedotto**: ragionamento mio, ricordo non ricontrollato, o dato riportato solo da terzi. Va confermato prima di basarci una decisione.
- Questo documento non è consulenza legale. Le parti su licenze e GDPR sono una lettura tecnica delle fonti.

Premessa sulle licenze dei volti, perché cambia tutta la lettura delle tabelle: per un modello contano **tre** licenze diverse, che spesso non coincidono. (1) La licenza del **codice**. (2) La licenza dei **pesi**. (3) I termini del **dataset** con cui i pesi sono stati addestrati. Molti modelli hanno codice MIT e pesi "Apache-2.0" ma dataset di sola ricerca. Per questo, qui sotto, ogni candidato ha la colonna "Dati di addestramento".

---

## 1. OCR

### 1.1 ML Kit Text Recognition v2 (punto 1 della richiesta)

| Aspetto | Esito |
|---|---|
| Versione attuale, variante bundled | `com.google.mlkit:text-recognition:16.0.1`, pubblicata il 07/08/2024; su Google Maven non esistono versioni successive [V] ([metadata](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition/maven-metadata.xml), [release notes](https://developers.google.com/ml-kit/release-notes)) |
| Versione attuale, variante unbundled (Play Services) | `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1`, 07/08/2024 [V] ([metadata](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-text-recognition/maven-metadata.xml)). Le release notes fino al 21/07/2026 le danno invariate [V] |
| 16 KB page size | L'aggiornamento di agosto 2024 ha reso gli SDK compatibili con pagine da 16 KB, inclusi bundled 16.0.1 e unbundled 19.0.1 [V] ([release notes](https://developers.google.com/ml-kit/release-notes)) |
| Dimensione APK | Bundled: circa 4 MB per script e per architettura. Unbundled: circa 260 KB, ma il modello viene scaricato da Google Play Services [V] ([docs v2 Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)). L'AAR bundled su Maven pesa 1,38 MB (misurato con HEAD) [V], quindi l'impatto reale sull'APK va misurato in build [D] |
| Lingue | Script Latino, che include l'italiano (`it`, Latn) e altre 30 lingue latine [V] ([lingue](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)). Sono disponibili anche cinese, devanagari, giapponese e coreano come librerie separate (altri circa 4 MB ciascuna se bundled) [V]. Greco, cirillico, arabo ed ebraico non compaiono tra gli script supportati [V per assenza]. La pagina non documenta come gestisce accenti e diacritici [V: non documentato]; né la scrittura a mano [V: non documentata] |
| Risoluzione consigliata | Ogni carattere almeno 16x16 px; oltre circa 24x24 px non migliora l'accuratezza. Esempio ufficiale: un foglio "letter" può richiedere circa 720x1280 px [V] ([docs](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)) |
| Prestazioni | La documentazione dice solo che la libreria Latina "gira in tempo reale sulla maggior parte dei dispositivi" [V]. Non pubblica latenze né accuratezza per lingua [V: assenti] |
| minSdk | La pagina riporta API 23 (e altrove 21): irrilevante con minSdk 29 [V] |
| Licenza e termini | Distribuito sotto i [termini ML Kit](https://developers.google.com/ml-kit/terms) (che incorporano i Google APIs ToS). Le pagine non contengono divieti d'uso per riconoscimento o dati biometrici [V]. Il testo dei termini non tratta la distribuzione fuori dal Play Store [V: non trattata]; l'SDK bundled non richiede Play Services, quindi non dipende dal canale di installazione [D] |
| **Telemetria** | I termini dicono che l'input (immagini, testo) è elaborato interamente sul dispositivo e non viene inviato ai server Google [V]. Dicono però che l'SDK contatta i server Google per correzioni, modelli aggiornati e compatibilità degli acceleratori, e **invia metriche di prestazione e uso** [V] ([termini](https://developers.google.com/ml-kit/terms)). Il dettaglio ([data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)) elenca: produttore e modello del dispositivo, versione OS, nome del pacchetto e versione dell'app, identificatori per installazione, latenza, configurazione API (formato e risoluzione dell'immagine), codici d'errore. Non viene indicato un modo per disattivarlo [V: non documentato]. Vale per "tutte le funzioni", quindi anche per la variante bundled [V] |

Effetto sul piano: il principio "nessuna telemetria" (§0) **non è garantito da ML Kit**. I dati inviati non contengono contenuto delle foto, ma sono comunque traffico verso Google. Va misurato (vedi "Da verificare") e, se non accettabile, l'OCR di riserva (1.2) diventa quello principale.

Unbundled vs bundled per questo progetto:

- Bundled: nessuna dipendenza da Google Play Services, funziona subito e offline, +circa 4 MB. Adatto alla distribuzione fuori dal Play Store e al principio offline-first.
- Unbundled: APK più piccolo, ma il modello si scarica al primo uso. Le richieste fatte prima della fine del download "non producono risultati" [V]. Il download automatico all'installazione si configura con il meta-data `com.google.mlkit.vision.DEPENDENCIES` = `ocr`, ed è descritto nel contesto dell'installazione da Play Store [V per il meta-data; D per il comportamento in sideload]. Per un'app che dichiara "tutto funziona offline", è un rischio inutile.

**Problema di risoluzione da risolvere subito (impatta le "anteprime di analisi")** [D, calcoli miei da verificare]:

- Regola ufficiale: 16 px per carattere. Con un'anteprima di 1280 px sul lato lungo, un foglio A4 verticale ha circa 4,3 px/mm. Un testo da 10-12 pt ha altezza maiuscole di circa 2,5-3 mm, cioè 11-13 px: sotto soglia.
- Screenshot: uno screenshot da 1080x2340 ridotto a 1280 px di lato lungo viene scalato di circa 0,55. Un testo da 40 px diventa circa 22 px (ok), ma il testo piccolo (note a piè, barre di stato, chat) scende sotto 16 px.
- Scontrini fotografati con molto margine: caratteri di 2-3 mm su un lato lungo di 1280 px per 20-30 cm di carta danno circa 8-16 px: al limite.
- Conseguenza: o l'OCR usa un'anteprima dedicata più grande (per esempio 2048 px sul lato lungo, solo per immagini "testuali"), o divide l'immagine in tessere (tiling) con sovrapposizione, o lavora sull'originale per gli screenshot. Questo va deciso insieme alla risoluzione delle anteprime di analisi (sottofase 0.3).

### 1.2 Alternativa: PaddleOCR PP-OCRv5 mobile (punto 2)

| Aspetto | Esito |
|---|---|
| Licenza | Codice PaddleOCR: Apache-2.0 [V] ([LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE)). Pesi: le schede Hugging Face ufficiali dichiarano `apache-2.0` sia per [`latin_PP-OCRv5_mobile_rec`](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec) sia per [`PP-OCRv5_mobile_det`](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det) [V]. Dati di addestramento: non verificati [D] |
| Lingue | Il riconoscitore Latino elenca l'italiano (`it`) tra circa 47 lingue [V] ([doc PP-OCRv5 multilingua](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md)) |
| Dimensioni | Riconoscitore latino: 7,97 MB di pesi, 8,22 MB in totale [V] ([file](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec/tree/main)). Detector mobile: circa 4,7 MB di storage nel repository [V, valore `usedStorage` dell'API HF, comprende anche i file di configurazione] |
| Accuratezza dichiarata | Riconoscitore latino: 84,7% sul loro set latino da 3.111 immagini, a livello di riga intera (una riga è sbagliata se un solo carattere è sbagliato) [V]. Non è confrontabile con ML Kit, che non pubblica numeri [V: assenti] |
| Formato | Ufficiale: PaddlePaddle (`inference.pdiparams`, `inference.json`). Conversioni ONNX: solo di terzi (es. `monkt/paddleocr-onnx`, che dichiara Apache-2.0) [V per l'esistenza, D per l'affidabilità] |
| Runtime | ONNX Runtime Android 1.31.0 (09/10/2026), licenza MIT [V] ([Maven](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml), [LICENSE](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)). LiteRT 2.3.0 (06/10/2026) [V] ([Maven](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml)) |
| Wrapper pronti | RapidOCR (Apache-2.0, codice e modelli) [V] ([repo](https://github.com/RapidAI/RapidOCR)). La demo Android `RapidOcrAndroidOnnx` ha l'ultima voce di changelog del 16/02/2023 con ONNX Runtime 1.14.0: obsoleta [V] |
| Costo di integrazione | Alto rispetto a ML Kit: rilevamento righe, eventuale classificatore di orientamento, riconoscimento con decodifica CTC e dizionario, ordinamento di righe e blocchi. Stima: giorni, non ore [D] |

Ruolo consigliato: **piano B**. Si attiva se ML Kit fallisce sul set di prova (scontrini, testo piccolo), oppure se la telemetria di ML Kit risulta non accettabile. Ha il vantaggio di non avere nessuna rete implicita e di poter lavorare su immagini grandi con tiling controllato.

---

## 2. Rilevamento volti (punto 3)

### 2.1 Confronto

| | ML Kit Face Detection | MediaPipe Face Detector (BlazeFace) | YuNet (OpenCV Zoo) |
|---|---|---|---|
| Versione | Bundled `com.google.mlkit:face-detection:16.1.7` (07/08/2024, circa 6,9 MB). Unbundled `play-services-mlkit-face-detection:17.1.0` (16/08/2022, circa 800 KB) [V] ([docs](https://developers.google.com/ml-kit/vision/face-detection/android), [metadata](https://dl.google.com/dl/android/maven2/com/google/mlkit/face-detection/maven-metadata.xml)) | `com.google.mediapipe:tasks-vision:1.1.0`, aggiornato il 06/10/2026 [V] ([metadata](https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/maven-metadata.xml)) | `face_detection_yunet_2023mar.onnx` (232.589 byte) e `face_detection_yunet_2026may.onnx` (229.738 byte, input dinamico) [V] ([README](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/README.md)) |
| 16 KB page | Bundled sì; unbundled 17.1.0 non aggiornata a quella data [V] ([release notes](https://developers.google.com/ml-kit/release-notes)) | Non verificato [D] | Solo modello ONNX; dipende dal runtime (ONNX Runtime) [D] |
| Licenza | Termini ML Kit [V] | Codice MediaPipe Apache-2.0 [V] ([LICENSE](https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE)). La scheda del modello rimanda ad Apache 2.0 con un collegamento, ma il PDF non lo scrive in chiaro [V parziale] | **MIT** (Shiqi Yu, 2020) [V] ([LICENSE](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE)) |
| Telemetria | Sì, come per OCR [V] | Non verificata [D] | Nessuna (solo un file ONNX; dipende dal runtime scelto) [D] |
| Ingresso | Immagine di almeno 480x360 px; ogni volto almeno 100x100 px (200x200 per i contorni) [V] | Modello da 128x128 px (float16): short-range per selfie entro circa 2 m, full-range per foto con camera posteriore entro circa 5 m [V] ([docs](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector)). L'esempio Android ufficiale usa lo short-range [V] | Volti da circa 10x10 a 300x300 px [V]; il modello 2026may accetta qualsiasi risoluzione [V] |
| Volti piccoli | `setMinFaceSize` (rapporto larghezza testa/immagine, default 0,1), "non è un limite rigido" [V]. Sotto 100 px il rilevatore è fuori dalla guida ufficiale [V] | Tutto il fotogramma viene ridotto a 128x128: in una foto di gruppo da 1280 px i volti piccoli diventano di pochi pixel [D, dedotto dalla dimensione d'ingresso] | Il punto di forza dichiarato; AP su WIDER FACE (set difficile) 0,708 (valore in testa al README) o 0,750 (tabella di valutazione): il README riporta due serie di numeri diverse e non spiega perché [V] |
| Profili / rotazioni | Fornisce `headEulerAngleY/Z` ma non documenta un intervallo di rotazione supportato. Le classificazioni (sorriso, occhi aperti) valgono solo per volti frontali (Y tra -18° e 18°) [V] | Non documentato [V: assente] | Non documentato [V: assente]; WIDER FACE "hard" include pose e occlusioni [D] |
| Uscita | Riquadro, angoli, landmark (`LEFT_EYE`, `RIGHT_EYE`, `NOSE_BASE`, `MOUTH_LEFT`, `MOUTH_RIGHT`, `MOUTH_BOTTOM`, orecchie, guance), contorni, classificazione [V] ([FaceLandmark](https://developers.google.com/android/reference/com/google/mlkit/vision/face/FaceLandmark)). Nessun embedding né riconoscimento [V] | Riquadro e 6 punti: occhi, punta del naso, **un solo punto** per la bocca, due trago [V] | Riquadro e **5 punti** (occhi, naso, angoli della bocca) [V, dal README di SFace che usa YuNet come rilevatore con 5 landmark] |
| Velocità riportata | Non pubblicata [V: assente] | Pixel 6, short-range: 2,94 ms CPU, 7,41 ms GPU [V] | Non pubblicata nelle fonti lette [V: assente] |
| Integrazione | API Kotlin pronta, 1-2 righe | API Tasks pronta, ma modello e opzioni da gestire | Serve ONNX Runtime (o OpenCV) e codice di decodifica delle uscite (ancore, NMS): poche centinaia di righe [D] |

Note sull'allineamento (serve per l'embedder, vedi 3.1):

- I modelli ArcFace/SFace si aspettano un volto allineato con **5 punti** (occhi, punta del naso, angoli della bocca) su un modello 112x112. YuNet li dà esattamente.
- ML Kit dà occhi, `NOSE_BASE` (punto medio tra le narici, non la punta) e angoli della bocca [V]: sufficiente, ma il naso è spostato rispetto al modello standard, con un piccolo errore sistematico [D].
- BlazeFace dà un solo punto bocca: richiede un allineamento a 4 punti con un modello diverso [D].

Una considerazione che alleggerisce il problema dei "volti piccoli" [D]: l'embedding su volti sotto circa 50-60 px di lato è comunque poco affidabile (il modello vede 112x112 ingranditi). Quindi non conviene "cercare di più": conviene rilevare, ma **escludere dal raggruppamento** i volti troppo piccoli, sfocati o di profilo (vedi 5). La domanda vera diventa: a che risoluzione facciamo girare il rilevatore, e se l'anteprima di analisi da 1024-1280 px lascia abbastanza pixel nelle foto di gruppo.

### 2.2 Scelta

**YuNet (MIT)** come rilevatore principale. Ragioni: licenza chiara e permissiva, 230 KB, nessuna telemetria, 5 landmark compatibili con SFace, ingresso a risoluzione libera, pensato per volti piccoli. **ML Kit Face Detection bundled 16.1.7** come piano B (stessa uscita utile con integrazione più semplice, ma telemetria e raccomandazione di 100x100 px). BlazeFace è la scelta peggiore per questo uso (input 128x128 sull'intera immagine, un solo punto bocca) ed è adatto ai selfie, non a una galleria con foto di gruppo.

---

## 3. Embedding facciale (punto 4)

### 3.1 Candidati

Colonne: "Pesi" è la licenza dei pesi; "Dati" sono i dataset di addestramento e i loro vincoli. L'accuratezza su LFW è ormai satura (sopra 99%) e discrimina poco: per profili, età e pose guardare CFP-FP, AgeDB-30, CPLFW, IJB-C dove disponibili.

| Candidato | Pesi | Dati di addestramento | Dimensione e formato | Accuratezza dichiarata | Vettore | Allineamento | Verdetto |
|---|---|---|---|---|---|---|---|
| **SFace** (OpenCV Zoo, 2021dec; MobileFaceNet con loss SFace) | **Apache-2.0** [V] ([LICENSE](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/LICENSE)) | CASIA-WebFace, VGGFace2, MS-Celeb-1M [V] ([arXiv 2205.12010](https://arxiv.org/abs/2205.12010)). Vedi avvertenza dataset sotto | fp32 38,70 MB; int8 9,90 MB; int8 a blocchi 10,67 MB (puntatori Git LFS, `size`) [V]. ONNX nativo; conversione a LiteRT possibile ma non verificata [D] | 0,9940 fp32 / 0,9932 int8 su `tools/eval` con LFW ([README](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/README.md), [eval](https://github.com/opencv/opencv_zoo/blob/main/tools/eval/README.md)) [V]. CFP-FP e AgeDB non ricavati [V: assenti] | 128 [D, da API OpenCV `FaceRecognizerSF`, non riletto] | 5 punti, "5-landmark warping" [V]; ingresso 112x112 [D]. Soglia coseno del demo ufficiale: 0,363 [V] ([sface.py](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/sface.py)) | **Scelto** |
| **MobileFaceNet** (foamliu, esposto anche da Qualcomm AI Hub) | **Apache-2.0** (repo e scheda AI Hub) [V] ([foamliu](https://github.com/foamliu/MobileFaceNet), [AI Hub](https://aihub.qualcomm.com/mobile/models/mobile_facenet)). I pesi TorchScript non hanno una licenza separata [V] | MS-Celeb-1M "raffinato": 3.804.846 volti, 85.164 identità [V] | Circa 1 M parametri, 4 MB float [V]. Distribuito come `mobilefacenet.pt`/TorchScript; AI Hub non elenca TFLite/ONNX nella pagina [V]. Esportazione verso ONNX/TFLite possibile [D] | LFW 99,48% (99,55% nel paper) [V] | 128, ingresso 112x112 [V (AI Hub)] | Tipicamente 5 punti [D] | Alternativa leggera, stessa avvertenza dataset |
| **AuraFace-v1** (fal) | **Apache-2.0** (metadato HF) [V] ([scheda](https://huggingface.co/fal/AuraFace-v1)) | Dichiarato "dataset commerciale" di cui non sono forniti nome, licenza né dimensioni [V] ([blog](https://huggingface.co/blog/isidentical/auraface)) | ResNet100 (stessa base di ArcFace). `glintr100.onnx` = **260,7 MB** [V] ([API HF](https://huggingface.co/api/models/fal/AuraFace-v1)) | LFW 99,65; CFP-FP 95,19; AgeDB 96,10; CALFW 94,70; CPLFW 90,93 [V]. Il blog ammette prestazioni inferiori a ArcFace [V] | 512 [D] | 5 punti stile InsightFace [D] | Alternativa se si pubblica; pesante |
| **GhostFaceNets** (HamadYA) | Barra laterale GitHub: "MIT license"; il file `LICENSE.md` non è stato trovato (404). Un sito terzo parla di due licenze MIT e una Apache-2.0 [V parziale] ([repo](https://github.com/HamadYA/GhostFaceNets)) | MS1MV2 / MS1MV3 [V]. Per InsightFace e per il porting Keras, questi dati sono "solo ricerca non commerciale" [V] ([Keras_insightface](https://github.com/leondgarse/Keras_insightface)) | Keras `.h5`, circa 16,5 MB per V1 W1.3 S1 (da DeepFace, terzi) [V terzi]. TFLite ottenibile da Keras [D] | V1-1.3-1 (MS1MV3): LFW 99,73; CFP-FP 96,83; AgeDB-30 98,0; IJB-C 94,94 [V] | 512 (`emb_shape=512`) [V] | 112x112, 5 punti [D] | Scartato: dati vincolati |
| **dlib ResNet v1** (Davis King) | **CC0 / pubblico dominio** [V] ([dlib-models](https://github.com/davisking/dlib-models)) | Circa 3 M immagini, 7.485 identità: FaceScrub, dataset VGG, immagini raccolte dall'autore [V]. Termini di quei dataset non verificati [D] | ResNet con 29 convoluzioni [V]. Formato `.dat` di dlib; conversioni ONNX di terzi [D]. Non pensato per mobile [D] | LFW 99,38% [V] | 128 [D] | Allineamento dlib (5 o 68 punti, 150x150) [D] | Licenza pesi più pulita; modello vecchio, poco accurato su CFP-FP/età, non ottimizzato |
| **EdgeFace** (Idiap; XS/XXS/S/base) | Codice **BSD-3** [V] ([LICENSE](https://github.com/otroshi/edgeface/blob/main/LICENSE)). Pesi sulla scheda HF di EdgeFace-XS-GAMMA: **CC BY-NC-SA 4.0**, solo non commerciale [V] ([scheda](https://huggingface.co/Idiap/EdgeFace-XS-GAMMA)) | WebFace260M (sottoinsiemi 12M e 4M) [V] | 1,24 - 3,65 M parametri per le varianti piccole, 154 MFLOPs per XS [V]. Solo PyTorch nel README [V] | XS: LFW 99,73; CALFW 95,28; CPLFW 91,58; CFP-FP 94,71; AgeDB-30 96,08; IJB-C 94,85 [V] | 512 [D] | Sì (`get_aligned_face`) [V] | Escluso per distribuzione (non commerciale) |
| **InsightFace buffalo_s** (`w600k_mbf`, MobileFaceNet) | Codice MIT; **tutti i modelli "solo ricerca non commerciale"** [V] ([README](https://github.com/deepinsight/insightface), [model zoo](https://github.com/deepinsight/insightface/tree/master/model_zoo)). Dal 24/11/2025 per il pacchetto buffalo_l serve contattare il fornitore per la licenza [V] | WebFace600K [V] | MBF 13 MB (pacchetto buffalo_s 159 MB) [V]. ONNX [D] | LFW 99,70; CFP-FP 98,00; AgeDB-30 96,58 [V] | 512 [D] | 5 punti, 112x112 [D] | Escluso per distribuzione; utile solo come riferimento di accuratezza in laboratorio |

Altri: FaceNet di Sandberg (MIT, Inception-ResNet-v1, LFW 99,05% con CASIA e 99,65% con VGGFace2, allineamento MTCNN; nessuna licenza esplicita per i pesi, il repository chiede solo di citare i dataset) [V] ([repo](https://github.com/davidsandberg/facenet)). Esistono conversioni TFLite FP16 nel progetto Android di esempio `OnDevice-Face-Recognition-Android` (Apache-2.0), che però non dichiara la licenza dei modelli [V] ([repo](https://github.com/shubham0204/OnDevice-Face-Recognition-Android)). Modello grande (circa 23 M parametri) [D] e superato: non candidato.

**Avvertenza dataset** (vale per SFace, MobileFaceNet, GhostFaceNets, EdgeFace e InsightFace):

- MS-Celeb-1M è stato ritirato da Microsoft nel 2019 dopo un'inchiesta, dopo essere stato raccolto da foto web con licenza Creative Commons per riuso accademico; le copie circolano ancora [V] ([Vice](https://www.vice.com/en/article/microsoft-deleted-a-facial-recognition-database-but-its-not-dead/), [Irish Times](https://www.irishtimes.com/business/technology/microsoft-quietly-deletes-largest-public-face-recognition-data-set-1.3916825)).
- CASIA-WebFace e VGGFace2 sono dataset nati per la ricerca; non ho verificato i loro termini attuali [D].
- Quindi una licenza Apache-2.0 sui pesi, come per SFace, **non cancella il dubbio sui dati di addestramento**. Per uso familiare privato il rischio pratico è trascurabile; per una pubblicazione larga il quadro giuridico sui pesi addestrati su dati di sola ricerca è incerto [D] e andrebbe valutato da chi pubblica. Tutti i modelli piccoli e accurati disponibili oggi hanno questo limite; AuraFace è l'unico che dichiara dati commerciali, ma senza fornire prove e con un modello da 261 MB.
- Il repository di AuraFace contiene anche file di InsightFace (`scrfd_10g_bnkps.onnx`, `1k3d68.onnx`, `2d106det.onnx`, `genderage.onnx`) la cui origine e licenza non sono spiegate [V per l'elenco, D per il rischio]: usare solo il riconoscitore (`glintr100.onnx`) e verificarne la provenienza prima di adottarlo. Il nome del file coincide con un modello InsightFace [D].

### 3.2 Scelta

**SFace 2021dec (Apache-2.0)**, con YuNet come rilevatore. Perché:

1. Unico candidato con pesi sotto licenza permissiva **e** piccolo **e** in ONNX **e** abbinato a un rilevatore con i 5 punti che servono per allinearlo.
2. 9,9 MB in int8, 38,7 MB in fp32: scaricabile e verificabile con hash (E-A1, E-A2).
3. Soglia coseno ufficiale nota (0,363) come punto di partenza per la calibrazione.

Debolezze note: le sole cifre ricavate sono su LFW, che non misura profili ed età (CFP-FP e AgeDB-30 non trovati) [V: assenti]. Il test sul set di famiglia della 0.4 è quindi la vera decisione.

Alternativa 1: **AuraFace-v1 (solo `glintr100.onnx`)**, se si prevede una pubblicazione larga e se i test mostrano che un ResNet100 da 261 MB è sostenibile come tempo e memoria sugli indicizzatori. Alternativa 2: **MobileFaceNet (foamliu/Qualcomm AI Hub)**, 4 MB, se serve velocità/NPU e SFace risultasse troppo lento; stessa avvertenza dataset. **Non distribuire** InsightFace, EdgeFace né GhostFaceNets.

Riferimento: i hash SHA-256 (oid Git LFS) per il controllo di integrità (E-A2) [V]: SFace fp32 `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`; SFace int8 `2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a`; YuNet 2023mar `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`; YuNet 2026may `ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0`.

Runtime da usare: ONNX Runtime Android (MIT) per YuNet e SFace con i file originali, oppure LiteRT dopo una conversione da validare [D]. Evitare di basarsi su NNAPI (deprecata da Android 15) [D, da riverificare].

---

## 4. Pipeline consigliata per i volti [D]

1. Dall'anteprima di analisi (o da una anteprima più grande se serve): YuNet, soglia di punteggio da calibrare (Immich consiglia di non scendere sotto 0,5 e non usare valori molto alti come 0,9 [V] ([Immich](https://docs.immich.app/features/facial-recognition))).
2. Filtro qualità: riquadro del volto sopra una dimensione minima (partire da circa 48-64 px sul lato), nitidezza, posa ricavata dai 5 punti. I volti scartati restano registrati come "non raggruppati".
3. Allineamento a 112x112 con trasformazione di similarità sui 5 punti, SFace, normalizzazione L2.
4. Archiviazione: ID del volto, ID media, riquadro, punteggio, qualità, vettore, **ID e hash del modello** (serve a E-A10 per la reindicizzazione graduale). In fp16, 128 dimensioni sono 256 byte a volto: 50.000 volti sono circa 12,8 MB; con 512 dimensioni circa 51 MB [D, calcolo].

---

## 5. Raggruppamento incrementale (punto 5)

### 5.1 Scala

Decine di migliaia di volti con vettori di 128 dimensioni stanno in memoria (circa 13-26 MB). Una ricerca esatta per prodotto scalare contro 50.000 vettori è nell'ordine di pochi milioni di moltiplicazioni per volto [D], quindi **non serve un indice approssimato (HNSW)** finché si resta sotto le centinaia di migliaia di volti; basta una matrice contigua scandita in blocchi. Da verificare con una misura (vedi sotto).

### 5.2 Confronto

| Metodo | Pro | Contro | Riferimento |
|---|---|---|---|
| Soglia coseno + union-find (= single-linkage) | Semplicissimo, incrementale, componenti connesse | **Effetto catena**: un solo volto ambiguo (sfocato, di profilo) può unire due persone diverse. Incompatibile con l'obiettivo "97% dei gruppi senza persone sbagliate" (8.1) se usato da solo [D] | Classico; il problema è descritto nella letteratura sul clustering gerarchico [D] |
| DBSCAN incrementale | Distingue punti centrali e rumore; il raggruppamento di Immich ne è una derivazione, con confronto sui soli vicini più prossimi, `min faces` = 3 di default e distanza consigliata tra 0,3 e 0,7 [V] ([Immich](https://docs.immich.app/features/facial-recognition)) | Sensibile a densità diverse tra persone; il DBSCAN incrementale esatto (Ester et al., VLDB 1998) tratta inserimenti e cancellazioni con complessità elevata se fatto alla lettera [D] | [Ester et al. 1998](https://www.vldb.org/conf/1998/p323.pdf) [D sul contenuto, non letto] |
| Chinese Whispers | Veloce (lineare negli archi), senza numero di cluster da fissare; implementato in dlib | Non deterministico; non nato per aggiornamenti incrementali: va rieseguito su un grafo di vicini oltre soglia [D] | [Biemann 2006](https://aclanthology.org/W06-3812/) [V per titolo e venue] |
| Rank-order clustering | Scala a milioni di volti | Eccessivo per una galleria familiare [D] | [Otto, Wang, Jain](https://arxiv.org/abs/1604.00989): 123 milioni di volti [V] |
| Agglomerativo a due passate (Apple Photos) | Prima passata conservativa (precisione), media corrente per cluster, seconda passata gerarchica per fondere cluster; esecuzione periodica, tipicamente di notte in carica [V] ([Apple ML Research](https://machinelearning.apple.com/research/recognizing-people-photos)) | Descritto, non rilasciato come libreria; usa anche il busto, che qui non abbiamo | idem |

### 5.3 Raccomandazione

Un **DBSCAN incrementale in stile Immich con passata di fusione notturna in stile Apple**, con alcune protezioni mie [D]:

1. **Assegnazione**: un nuovo volto di qualità sufficiente viene confrontato con gli *esemplari* di ogni persona (fino a circa 10-20 volti diversificati e nitidi, più la media del cluster), non con tutti i membri. Entra nella persona migliore solo se la similarità supera la soglia stretta *e* con un margine rispetto alla seconda persona.
2. **Nascita di una persona**: solo se il volto ha almeno `m` vicini oltre la soglia (partire da `m` = 3, come Immich); altrimenti resta "in attesa" e viene riprovato la notte dopo [V per il comportamento di Immich].
3. **Vincolo di non-collegamento**: due volti nella stessa foto non possono essere la stessa persona (blocca le fusioni sbagliate, utile per gemelli e fratelli, E-F1).
4. **Fusione notturna**: due persone si uniscono solo se la similarità *media* (non il massimo) tra i loro esemplari supera una soglia; nessun passaggio per catene di singoli volti.
5. **Correzioni manuali come vincoli permanenti**: "unisci" e "dividi" diventano vincoli *must-link* e *cannot-link* conservati e riapplicati a ogni ricalcolo (E-F1, E-F2). Il nome vive sul cluster, non sul volto.
6. **Soglie**: non trasferibili dai numeri pubblicati. La 0,363 del demo SFace è una soglia di *verifica* coppia a coppia. Per il raggruppamento servirà una soglia più severa, da calibrare sul set di famiglia con curva coppie vere/false [D]; la scelta della soglia è quindi un risultato della 0.4, non un dato in ingresso.
7. **Chinese Whispers** resta un'opzione per il ricalcolo completo dopo un cambio modello (E-A10), da confrontare con il DBSCAN su purezza e tempo [D].

---

## 6. Note GDPR e privacy per dati biometrici in ambito domestico (punto 6)

Non è consulenza legale; sono i punti tecnici da portare a chi deve decidere, e quelli già coerenti con il piano.

- **Cosa è dato biometrico.** Un dato biometrico è un dato personale ottenuto da un trattamento tecnico specifico delle caratteristiche fisiche di una persona, che ne consente l'identificazione univoca (art. 4, punto 14) [V] ([art. 4](https://gdpr-info.eu/art-4-gdpr/)). Il considerando 51 dice che le fotografie non sono sistematicamente categorie particolari: lo diventano quando trattate con mezzi tecnici che permettono l'identificazione univoca [V] ([cons. 51](https://gdpr-info.eu/recitals/no-51/)). Quindi la foto in sé no; **gli embedding facciali usati per raggruppare per persona probabilmente sì** [D, interpretazione]. Per i dati di categoria particolare vige un divieto di base (art. 9, par. 1) con eccezioni, tra cui il consenso esplicito (par. 2, lett. a) [V] ([art. 9](https://gdpr-info.eu/art-9-gdpr/)).
- **Esenzione domestica.** Il GDPR non si applica al trattamento effettuato da una persona fisica per attività esclusivamente personali o domestiche (art. 2, par. 2, lett. c; considerando 18). Chi fornisce i mezzi per tali attività resta soggetto al regolamento [V] ([cons. 18](https://gdpr-info.eu/recitals/no-18/)). Per questa app, con elaborazione solo sui dispositivi e sul NAS di casa, senza che lo sviluppatore riceva alcun dato, è ragionevole ritenere che non ci sia un titolare esterno [D]; se però l'app venisse pubblicata, resta la domanda su chi "fornisce i mezzi", da far valutare a un legale prima della pubblicazione [D].
- **Familiari e terzi nelle foto.** L'esenzione copre l'uso personale della famiglia; non copre pubblicare o condividere all'esterno [D]. Nel piano i dati dei volti sono già personali, nell'area privata di chi li attiva, e non vengono condivisi tra familiari (proposta 13, E-F4): questa è la scelta giusta e va scritta in `docs/sicurezza.md`.
- **Misure pratiche, la maggior parte già nel piano** [D come raccomandazione]:
  - attivazione **opt-in** per account, con schermata chiara (cosa viene calcolato, dove resta, come si cancella) (8.1);
  - **cancellazione completa** ("Disattiva e cancella"): embedding, gruppi, nomi, miniature dei volti, cache e backup applicativi, su NAS e su tutti i dispositivi dell'utente (E-F6);
  - cifratura dei dati dei volti a riposo (chiave nel Keystore) e permessi dell'area privata del NAS;
  - **nessun dato dei volti esportato** né inviato a servizi esterni, nessuna telemetria sui volti;
  - **nessuna inferenza di attributi** (età, genere, etnia, emozioni): sono categorie particolari o ad alto rischio e non servono al raggruppamento;
  - minimizzare: conservare vettore, riquadro e punteggio; evitare di salvare ritagli dei volti se la miniatura può essere ricavata al momento;
  - registro del modello usato (ID e hash) per poter ricalcolare o cancellare per versione.
- **Telemetria ML Kit.** I termini rimettono allo sviluppatore l'obbligo di informare gli utenti sul trattamento effettuato dall'SDK [V] ([termini](https://developers.google.com/ml-kit/terms)). Se l'app venisse pubblicata, la nota sulla privacy dovrebbe citare queste metriche, oppure si evita ML Kit (YuNet + PaddleOCR).
- **Altre giurisdizioni.** Altri ordinamenti (per esempio leggi statali USA sui dati biometrici) hanno regole proprie; non le ho verificate [D] e valgono solo se l'app venisse distribuita lì.

---

## Fonti principali

- ML Kit: [Text Recognition v2 Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [lingue](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages), [Face Detection Android](https://developers.google.com/ml-kit/vision/face-detection/android), [concetti](https://developers.google.com/ml-kit/vision/face-detection/face-detection-concepts), [release notes](https://developers.google.com/ml-kit/release-notes), [termini](https://developers.google.com/ml-kit/terms), [dati raccolti](https://developers.google.com/ml-kit/android-data-disclosure).
- Maven Google: [text-recognition](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition/maven-metadata.xml), [face-detection](https://dl.google.com/dl/android/maven2/com/google/mlkit/face-detection/maven-metadata.xml), [tasks-vision](https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/maven-metadata.xml), [litert](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml); Maven Central: [onnxruntime-android](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml).
- MediaPipe: [Face Detector](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector), [guida Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector/android).
- OpenCV Zoo: [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet), [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface).
- PaddleOCR: [repository](https://github.com/PaddlePaddle/PaddleOCR), [latin rec](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec), [mobile det](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det), [RapidOCR](https://github.com/RapidAI/RapidOCR).
- Modelli volti: [InsightFace](https://github.com/deepinsight/insightface), [EdgeFace](https://github.com/otroshi/edgeface), [EdgeFace-XS-GAMMA](https://huggingface.co/Idiap/EdgeFace-XS-GAMMA), [GhostFaceNets](https://github.com/HamadYA/GhostFaceNets), [AuraFace](https://huggingface.co/fal/AuraFace-v1), [foamliu MobileFaceNet](https://github.com/foamliu/MobileFaceNet), [Qualcomm AI Hub MobileFaceNet](https://aihub.qualcomm.com/mobile/models/mobile_facenet), [dlib-models](https://github.com/davisking/dlib-models), [FaceNet](https://github.com/davidsandberg/facenet).
- Raggruppamento: [Immich](https://docs.immich.app/features/facial-recognition), [Apple](https://machinelearning.apple.com/research/recognizing-people-photos), [Otto et al.](https://arxiv.org/abs/1604.00989), [Biemann 2006](https://aclanthology.org/W06-3812/), [Ester et al. 1998](https://www.vldb.org/conf/1998/p323.pdf).
- GDPR: [art. 4](https://gdpr-info.eu/art-4-gdpr/), [art. 9](https://gdpr-info.eu/art-9-gdpr/), [considerando 18](https://gdpr-info.eu/recitals/no-18/), [considerando 51](https://gdpr-info.eu/recitals/no-51/).

---

## Raccomandazione

| Funzione | Scelta | Licenza | Alternativa |
|---|---|---|---|
| **OCR** | **ML Kit Text Recognition v2, Latin, bundled** `com.google.mlkit:text-recognition:16.0.1` | Termini ML Kit / Google APIs ToS | PaddleOCR **PP-OCRv5 mobile** (rilevatore + riconoscitore latino) via ONNX Runtime/LiteRT: **Apache-2.0**. Diventa principale se il traffico di ML Kit non è accettabile o se il test su scontrini/testo piccolo va male |
| **Rilevatore volti** | **YuNet** (OpenCV Zoo, `2026may` o `2023mar`), 5 landmark | **MIT** | ML Kit Face Detection bundled `16.1.7` (termini ML Kit) |
| **Embedder volti** | **SFace 2021dec** (OpenCV Zoo), allineamento a 5 punti, 112x112, cosine | Pesi **Apache-2.0**, ma addestrato su CASIA-WebFace, VGGFace2, MS-Celeb-1M: rischio dataset da dichiarare | **AuraFace-v1** (`glintr100.onnx`, 261 MB) se si pubblica largamente e i tempi sono sostenibili; **MobileFaceNet** (foamliu / Qualcomm AI Hub, Apache-2.0, 4 MB) se serve più velocità. **Non distribuire** InsightFace (non commerciale), EdgeFace (CC BY-NC-SA), GhostFaceNets (dati non commerciali) |
| **Raggruppamento** | DBSCAN incrementale a esemplari + vincolo "stessa foto" + passata notturna di fusione + correzioni come vincoli | n/a | Chinese Whispers per il ricalcolo completo |

Stato delle decisioni: la 0.4 è "Fatto quando: modelli scelti e licenze documentate". Le **licenze** sono documentate sopra; i **modelli** sono scelti in via provvisoria perché la qualità reale (italiano su scontrini, profili, bambini, occhiali) si decide solo sul set di prova.

## Da verificare sul dispositivo

Su Galaxy S25 e Galaxy Tab S9, con un set in italiano di circa 100+ immagini di testo (screenshot, scontrini, documenti, cartelli) e un set di famiglia per i volti (età diverse, occhiali, profili, bambini, gemelli o fratelli simili):

1. **Traffico di ML Kit**: catturare con un firewall locale o PCAPdroid cosa invia l'SDK bundled (a quali host, quando, con quale frequenza, anche con l'app senza Internet). Se qualcosa esce, decidere se è accettabile rispetto a "nessuna telemetria".
2. **OCR e risoluzione**: tasso di errore sui caratteri e righe lette a 1024, 1280, 1600, 2048 px e sull'originale; tiling con sovrapposizione contro immagine intera; tempo e memoria per immagine. Obiettivo: decidere la risoluzione dell'anteprima di analisi **e** se serve un'anteprima separata per le immagini testuali (screenshot, documenti).
3. **OCR su italiano**: accenti, apostrofi, `€`, maiuscolo degli scontrini termici, testo curvo o inclinato dei cartelli, lingue straniere (E-A8), testo minuto (E-A7). Confronto con PaddleOCR PP-OCRv5.
4. **Rilevatore**: richiamo e falsi positivi di YuNet contro ML Kit (e BlazeFace come riferimento) per classi di dimensione del volto (32, 48, 64, 100+ px nell'anteprima), profili, occhiali da sole, mascherine, cappelli, bambini, e volti in poster/TV (E-F3); tempo per immagine.
5. **Dimensione minima del volto**: nelle foto di gruppo, con l'anteprima di analisi di 1024-1280 px, quanti volti restano sopra 48-64 px? Se troppi vengono persi, valutare una passata dedicata a risoluzione maggiore solo per le foto con più volti piccoli.
6. **Embedder**: curve coppie vere/false e soglia di verifica su coppie del set di famiglia, inclusi fratelli simili (E-F1) e persone ritratte da bambini e da adulti (E-F2); SFace fp32 contro int8; tempo per volto su CPU, GPU e (se possibile) NPU; memoria; temperatura sotto carico (E-A5); confronto con MobileFaceNet e, se si pensa di pubblicare, con AuraFace.
7. **Raggruppamento**: purezza dei gruppi (obiettivo di 8.1: almeno 97% senza persone sbagliate), frammentazione, effetto del vincolo "stessa foto", durata della passata notturna e memoria con almeno 50.000 volti sintetici o reali; verifica che "Disattiva e cancella" non lasci tracce.
8. **Runtime**: ONNX Runtime contro LiteRT dopo conversione; dimensione dell'APK aggiunta (ORT, ML Kit); allineamento a 16 KB delle librerie native incluse (ML Kit bundled è dichiarato compatibile; per ONNX Runtime, LiteRT e MediaPipe non verificato).
9. **Integrità e versioni**: download dei modelli con hash SHA-256 (E-A1, E-A2) e salvataggio dell'ID/hash del modello con ogni vettore per la reindicizzazione graduale (E-A10).
10. **Licenza dei dati di addestramento**: prima di una pubblicazione, far leggere a chi può decidere i termini di CASIA-WebFace, VGGFace2 e MS-Celeb-1M e decidere se tenere SFace, passare ad AuraFace o addestrare/distillare un proprio modello.
