# IT-13 · Apuntar el aviso de Home a `Nonit0/Spotui`

| Campo | Valor |
|---|---|
| Tipo | Código |
| Depende de | — |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
En `app/src/main/java/com/music/spotui/data/api/UpdateChecker.kt`, cambiar las 2 constantes de
`Spotui/Spotui` a `Nonit0/Spotui`:
- l.10 `LATEST` → `https://api.github.com/repos/Nonit0/Spotui/releases/latest`
- l.11 `RELEASES_PAGE` → `https://github.com/Nonit0/Spotui/releases/latest`

Motivo: ahora mismo, si el original publica una 1.7, tu app avisa y manda a **su** APK, que va firmado con otra clave
(riesgo de IT-09 / S9). Mientras tu fork no tenga releases, la API devuelve 404 y el aviso simplemente no sale.
Es el mismo cambio de repo que se hará en IT-09, pero en el otro fichero: no se solapan.

## 2. Preguntar al usuario (antes de empezar)
- [x] ¿Lo cambio? (hasta tu primera release, el aviso de Home quedará en silencio)

> Respuesta del usuario: "Realmente deberíamos cambiarlo, no por nada; al final es código open source, si instalan mi versión es porque les ha gustado, nada más."

## 3. Salida esperada
- `assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL` (`UpdateCheckerTest` no depende de la URL).
- `grep -rn "Spotui/Spotui" app/src/main/java/com/music/spotui/data/api/` → sin resultados.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `data/api/UpdateChecker.kt` | 2 constantes |

## 5. Fuera de alcance
`data/update/UpdateChecker.kt` (→ IT-09).

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | `assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`, 20 tests, 0 fallos. `grep "Spotui/Spotui" data/api/` → sin resultados. Diff: 1 fichero, 2+/2− | `e663ac5` |

**Efecto:** hasta que publiques tu primera release en `Nonit0/Spotui`, el aviso de Home no muestra nada (la API devuelve 404 y se ignora). Después avisará de **tus** versiones (tag `vX.Y`).
**Queda apuntando al original:** solo `data/update/UpdateChecker.kt` (el diálogo del arranque), que ya no avisa nunca (tag `Release` parado en 1.2.1) → IT-09.
