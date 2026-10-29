# IT-10 · Adaptar `UpdateCheckerTest` al checker que se queda

| Campo | Valor |
|---|---|
| Tipo | Código (tests) |
| Depende de | IT-03 |
| Estado | ⏳ Pendiente |

## 1. Qué hacer
Hacer que `UpdateCheckerTest` pruebe `com.music.spotui.data.update.UpdateChecker.isNewer` en lugar
del de `data/api`:

1. Mover el test de `app/src/test/java/com/music/spotui/data/api/` a `app/src/test/java/com/music/spotui/data/update/` y cambiarle el `package`.
2. Cambiar `isNewer` en `data/update/UpdateChecker.kt` de `private` a `internal`, para que el test lo vea.

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿De acuerdo con cambiar la visibilidad de `isNewer` a `internal`?
- [ ] Puede pasar que el `isNewer` de `data/update` no entienda `"v1.7"` o `"Release"` (el de `data/api` sí lo hacía). Si algún caso falla, ¿lo dejo anotado como iteración nueva, sin arreglarlo aquí?

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL` con `data.update.UpdateCheckerTest` en verde.
- Si falla algún caso: **parar**, registrar qué entrada falla y proponer una iteración "Robustecer `isNewer`".

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `app/src/test/.../data/api/UpdateCheckerTest.kt` | Movido a `data/update/`, nuevo `package` |
| `app/src/main/.../data/update/UpdateChecker.kt` | `private fun isNewer` → `internal fun isNewer` |

## 5. Fuera de alcance
Cambiar la lógica de `isNewer`. Borrar `data/api/UpdateChecker.kt` (→ IT-04).

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
