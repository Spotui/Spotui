# Traceroute de iteraciones

Cada iteración es **un solo cambio atómico** con su propio fichero `IT-XX-*.md`, que funciona
como traza (traceroute) de lo que se pidió, se preguntó, se hizo y salió.

Contexto de la app: [`../CONTEXTO.md`](../CONTEXTO.md). Plantilla: [`PLANTILLA.md`](PLANTILLA.md).

## Reglas (obligatorias)

1. **No salir de esta carpeta** (`Spotui/`). Nada se lee ni escribe fuera del repo.
2. **Preguntar siempre al usuario** antes de tocar nada: se presenta el *qué hacer* y se espera
   un "sí". Si la respuesta es "no" o "cambia X", se actualiza el fichero y se vuelve a preguntar.
3. **Un cambio por iteración, sin subcambios.** Si al hacerla aparece otro cambio necesario,
   **no se hace**: se apunta en *Sugerencias* como nueva iteración candidata y se cierra la actual.
   Esto evita la iteración infinita.
4. **Si el cambio no cabe en una iteración, se parte** en varias antes de empezar, nunca durante.
5. **Criterio de parada**: la iteración termina cuando se obtiene la *Salida esperada* o cuando
   falla una vez. Un fallo no se arregla dentro de la misma iteración: se registra y se propone
   una nueva.
6. Al terminar, se rellena el *Registro* (estado, fecha, resultado real, commit si lo hay).

## Estados

`⏳ Pendiente` · `⏸️ Aplazada` · `❓ Esperando usuario` · `🔧 En curso` · `✅ Hecha` · `❌ Fallida` · `🚫 Descartada`

## Índice

| ID | Título | Tipo | Depende de | Estado |
|---|---|---|---|---|
| [IT-01](IT-01-baseline-build-tests.md) | Comprobar que compila y pasan los tests | Verificación | — | ✅ |
| [IT-02](IT-02-decidir-update-checker.md) | Decidir qué `UpdateChecker` se queda | Decisión | IT-01 | ✅ |
| [IT-09](IT-09-update-checker-releases-latest.md) | Apuntar `data/update` a `Nonit0/Spotui` `releases/latest` | Código | IT-02 + 1ª release en tu fork | ⏸️ aplazada |
| [IT-03](IT-03-migrar-badge-home.md) | Migrar el badge de Home a `data/update` | Código | IT-09 | ⏸️ (espera a IT-09) |
| [IT-10](IT-10-adaptar-update-checker-test.md) | Adaptar `UpdateCheckerTest` a `data/update` | Código | IT-03 | ⏳ |
| [IT-04](IT-04-borrar-update-checker-sobrante.md) | Borrar el `UpdateChecker` que sobra | Código | IT-10 | ⏳ |
| [IT-05](IT-05-renombrar-library-screen.md) | Renombrar `LibraryScreeen.kt` → `LibraryScreen.kt` | Código | IT-01 | ✅ |
| [IT-06](IT-06-borrar-tests-plantilla.md) | Borrar los tests de plantilla | Código | IT-01 | ✅ |
| [IT-07](IT-07-actualizar-readme.md) | Actualizar `README.md` con las funciones reales | Docs | — | ✅ |
| [IT-08](IT-08-analizar-runblocking-potoken.md) | Analizar el `runBlocking` del PoToken (sin cambiar código) | Análisis | — | ✅ |
| [IT-11](IT-11-potoken-suspend.md) | Quitar `runBlocking` de `PoTokenGenerator` (hacerlo `suspend`) | Código | IT-08 | ✅ (falta prueba manual) |
| [IT-12](IT-12-tests-modulo-spotify.md) | Ejecutar los tests de `:spotify` | Verificación | IT-01 | ✅ |
| [IT-13](IT-13-badge-home-a-nonit0.md) | Apuntar el aviso de Home a `Nonit0/Spotui` | Código | — | ✅ |
| [IT-14](IT-14-extraer-track-matcher.md) | Sacar la comparación de canciones de `SongPlayer` a `TrackMatcher.kt` | Código | — | ✅ (falta prueba manual) |
| [IT-15](IT-15-tests-track-matcher.md) | Tests unitarios de `TrackMatcher` | Código | IT-14 | ⏳ **siguiente** |
| [IT-16](IT-16-credenciales-fuera-del-backup.md) | Sacar la cookie de YouTube y Soulseek del backup de Google | Código | — | ⏳ |

## Backlog de sugerencias (aún no son iteraciones)

Se convierten en iteración solo si el usuario lo pide. Los IDs no se renumeran: el orden lo marca *Depende de*.

| ID | Sugerencia | Estado |
|---|---|---|
| S1 | Trocear `SongPlayer.kt` | Primer trozo → IT-14. Más trozos solo si hace falta |
| S2 | Tests del matching | → IT-15 |
| S3 | Quitar `SpotifyWebPlayer` (desactivado) | Pendiente: preguntar si se piensa recuperar |
| S4 | Activar `isMinifyEnabled` | Pendiente: necesita reglas ProGuard |
| S5 | Subir dependencias / AGP (quita avisos de D8) | Pendiente: una dependencia por iteración |
| S6 | Cifrar credenciales | 🚫 Descartada en favor de IT-16 (ver su §5) |
| S7 | Caché de 6 h / `force` en `data/update` | Pendiente, ligada a IT-09 |
| S9 | Aviso de Home apunta a `Spotui/Spotui` | ✅ Resuelta en IT-13 |
