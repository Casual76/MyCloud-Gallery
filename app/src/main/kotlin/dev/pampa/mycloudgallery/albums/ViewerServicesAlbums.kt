package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumActions

// Fase 7 (P13): i servizi del viewer con le voci ⋮ degli album di un contesto (aperto da un album, dai Nascosti). `ViewerServices` non ha una copia: la
// fa questa funzione, campo per campo. `ViewerServicesAlbumsTest` legge il costruttore di feature-viewer e controlla che **ogni** campo sia copiato
// (un campo nuovo dimenticato qui sparirebbe in silenzio dal viewer aperto da un album).

/** Gli stessi servizi, con [albums] al posto delle voci degli album. */
fun ViewerServices.withAlbums(albums: ViewerAlbumActions): ViewerServices = ViewerServices(
  images = images,
  thumbnails = thumbnails,
  placeholders = placeholders,
  details = details,
  exif = exif,
  bytes = bytes,
  motion = motion,
  places = places,
  geocoder = geocoder,
  actions = actions,
  intents = intents,
  player = player,
  animated = animated,
  onConfirmMobileData = onConfirmMobileData,
  originals = originals,
  nasVideo = nasVideo,
  download = download,
  nasActions = nasActions,
  dispatchers = dispatchers,
  backup = backup,
  similarCopies = similarCopies,
  outsideCopy = outsideCopy,
  backupActions = backupActions,
  link = link,
  motionAnimation = motionAnimation,
  powerSaving = powerSaving,
  nasMotion = nasMotion,
  textSource = textSource,
  textReader = textReader,
  subjectPress = subjectPress,
  similarSearch = similarSearch,
  analysis = analysis,
  subjectCutter = subjectCutter,
  cutoutSaver = cutoutSaver,
  people = people,
  placeMap = placeMap,
  albums = albums,
  mediaAlbums = mediaAlbums,
)
