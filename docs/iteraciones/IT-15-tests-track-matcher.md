# IT-15 · Tests unitarios de `TrackMatcher`

| Campo | Valor |
|---|---|
| Tipo | Código (solo tests) |
| Depende de | IT-14 |
| Estado | ⏳ Pendiente |

## 1. Qué hacer
Crear `app/src/test/java/com/music/spotui/di/TrackMatcherTest.kt` con casos que fijen el comportamiento **actual**:
- `normalizedForMatch` / `foldLatinDiacritics`: `"Beyoncé"` ≈ `"Beyonce"`.
- `bigramSimilarity`: título idéntico → alto; título distinto → bajo.
- `versionMarkers`: detecta "Live", "Remix", "Acoustic"… en el título.
- `ytmusicTransferScore` + `isAcceptableMatch`: acepta la canción correcta y rechaza
  un "(Live)" o un artista distinto con mismo título.

Nota: en tests JVM `android.icu.Transliterator` no existe; `anyLatinTransliterator` ya lo envuelve
en `runCatching` y devuelve `null`, así que no se prueban casos de transliteración (cirílico/japonés).

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Algún caso real de "sonó la canción equivocada" que quieras fijar como test?

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
`testDebugUnitTest` → `BUILD SUCCESSFUL` con `TrackMatcherTest` en verde.
Si un caso falla porque el comportamiento actual es distinto al esperado: **no se cambia el código**;
se ajusta el test al comportamiento real o se anota como iteración nueva.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `app/src/test/java/com/music/spotui/di/TrackMatcherTest.kt` | Nuevo |

## 5. Fuera de alcance
Cambiar `TrackMatcher`.

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
