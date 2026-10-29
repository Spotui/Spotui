# IT-05 · Renombrar `LibraryScreeen.kt` → `LibraryScreen.kt`

| Campo | Valor |
|---|---|
| Tipo | Código |
| Depende de | IT-01 |
| Estado | ✅ Hecha (2026-10-28) · pendiente de prueba manual |

## 1. Qué hacer
`git mv app/src/main/java/com/music/spotui/ui/screens/LibraryScreeen.kt app/src/main/java/com/music/spotui/ui/screens/LibraryScreen.kt`.
En Kotlin el nombre de fichero no afecta a los imports, así que no debería hacer falta tocar nada más.

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Confirmas el renombrado? (puede generar conflictos con ramas abiertas que toquen ese fichero)

> Respuesta del usuario: "Sí, corrige el nombre, creo que no hay más ramas, sigue adelante."

## 3. Salida esperada
- `git status` muestra un `renamed:`.
- `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `ui/screens/LibraryScreeen.kt` | Renombrado a `LibraryScreen.kt` |

## 5. Fuera de alcance
Renombrar funciones o composables de dentro del fichero.

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | `git status` → `R LibraryScreeen.kt -> LibraryScreen.kt` (en stage). `assembleDebug` → `BUILD SUCCESSFUL in 13s`. Ninguna otra referencia a `LibraryScreeen` en `.kt/.kts/.xml/.pro` | `4a271af` |

**Ramas:** `git ls-remote --heads origin` → solo `main`. No hay ramas que puedan dar conflicto.
**Pendiente (usuario):** abrir la pestaña Biblioteca en Android Studio y comprobar que se ve igual.
