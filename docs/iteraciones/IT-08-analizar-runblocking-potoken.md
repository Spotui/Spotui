# IT-08 · Analizar el `runBlocking` del PoToken (sin cambiar código)

| Campo | Valor |
|---|---|
| Tipo | Análisis (no modifica código) |
| Depende de | — |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
Averiguar desde qué hilos se llama a `PoTokenGenerator.getWebClientPoToken(videoId, sessionId)`
(`app/src/main/java/com/metrolist/music/utils/potoken/PoTokenGenerator.kt:33`, usa `runBlocking`)
y escribir el resultado en el Registro: ¿puede llegar a ejecutarse en el hilo principal?

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Has notado congelaciones/ANR al empezar a reproducir desde YouTube?
- [ ] ¿Solo análisis, de acuerdo? (la corrección, si hace falta, será otra iteración)

> Respuesta del usuario: "No lo he notado, haz solo el análisis."

## 3. Salida esperada
Lista de llamadores con su contexto (`Dispatchers.IO`, Main, etc.) y conclusión:
"riesgo real" o "sin riesgo".

## 4. Cambios
Solo este fichero (Registro).

## 5. Fuera de alcance
Convertir la función a `suspend` o cambiar llamadores.

## 6. Sugerencias
- Si hay riesgo real → iteración nueva "Hacer `suspend` la API de PoToken" (probablemente varias, un llamador por iteración).

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | **Sin riesgo de congelar la pantalla (ANR).** Hay un riesgo menor: el `runBlocking` anula los tiempos límite de YouTube (ver abajo) | — (sin cambios de código) |

### Cadena de llamadas
```
PoTokenGenerator.getWebClientPoToken(videoId, sessionId)      ← runBlocking (PoTokenGenerator.kt:33)
  ← YTPlayerUtils.playerResponseForPlayback (suspend)          ← YTPlayerUtils.kt:115 y :192
    ← SongPlayer.resolveYtPlaybackUnbounded → tryIds           ← withTimeoutOrNull(15 s) por vídeo
      ← SongPlayer.resolveYtPlayback                           ← withTimeoutOrNull(45 s) total
          ← SongPlayer.resolveStreamUrl (:809, :664) / quickStartThenUpgrade (:389)
          ← Downloads (:264)
```

### ¿Desde qué hilo se llama?
| Punto de entrada | Contexto |
|---|---|
| `playSong` (:278-284) | `scope.launch`, con `scope = Dispatchers.IO` |
| Cambio de pista, badge (:1363) | `scope.launch` → IO |
| Error en mitad de la reproducción (:1402-1408) | `scope.launch` → IO |
| Precarga / crossfade (`managePreload` :1647, desde `startPositionWatch` :1585) | `scope.launch` → IO (los `withContext(Main)` previos ya han terminado) |
| Crossfade (:1671-1673) | `scope.launch` → IO |
| Descargas (`Downloads.kt:264`) | `Downloads.scope = Dispatchers.IO` |

**Ninguna ruta llega desde el hilo principal.** El `runBlocking` bloquea un hilo de IO, nunca el de la UI. Por eso no se nota y no puede causar un ANR.
Si algún día se llamara desde Main habría un *deadlock*, porque la WebView necesita Main (`withContext(Dispatchers.Main)` en `PoTokenWebView.kt:197` y `:335`). Hoy no pasa.

### El riesgo real (menor)
`runBlocking` crea su propio bucle de coroutines, que **no recibe la cancelación** del llamador.
`SongPlayer.resolveYtPlayback` pone tiempos límite de 15 s y 45 s justamente para que una WebView colgada no deje la reproducción en 0:00 para siempre (comentario de las incidencias #95 y #79). Pero:
- `generatePoToken` espera la respuesta de JavaScript con `suspendCancellableCoroutine`, **sin tiempo límite propio** (`PoTokenWebView.kt:196-215`).
- Si esa respuesta no llega nunca, el hilo se queda bloqueado dentro de `runBlocking`. `withTimeoutOrNull` cancela la coroutine, pero no puede interrumpir un hilo bloqueado, así que **el tiempo límite no actúa** hasta que la WebView conteste.
- Además, el `Mutex` (`webPoTokenGenLock`) queda tomado, y las siguientes resoluciones de YouTube esperan detrás.

Solo ocurre si la WebView de BotGuard se cuelga. Encaja con que el usuario no lo haya notado.

### Arreglo propuesto (iteración aparte, no hecha)
Hacer `suspend` la función pública y quitar el `runBlocking`. Los dos llamadores (`YTPlayerUtils.kt:115` y `:192`) ya están dentro de la función `suspend` `playerResponseForPlayback` (dentro de `runCatching`, que es *inline*), así que no hace falta tocarlos.
Es **1 fichero, ~2 líneas**: `PoTokenGenerator.kt:23` (`fun` → `suspend fun`) y `:33` (quitar `runBlocking { }`). Así la cancelación llegaría a `suspendCancellableCoroutine` y los tiempos límite volverían a funcionar.
