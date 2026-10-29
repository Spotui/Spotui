# IT-09 · Apuntar `data/update/UpdateChecker` a `Nonit0/Spotui` `releases/latest`

| Campo | Valor |
|---|---|
| Tipo | Código (arregla un bug) |
| Depende de | IT-02 · **primera release publicada en `Nonit0/Spotui`** |
| Estado | ⏸️ Aplazada (2026-10-28) |

## 1. Qué hacer
En `app/src/main/java/com/music/spotui/data/update/UpdateChecker.kt`, pasar del repo original y el
tag fijo `Release` (congelado en 1.2.1) **al repo del usuario** con el endpoint estándar:

| Línea | Ahora | Después |
|---|---|---|
| 20 `RELEASE_API` | `https://api.github.com/repos/Spotui/Spotui/releases/tags/Release` | `https://api.github.com/repos/Nonit0/Spotui/releases/latest` |
| 75 respaldo `html_url` | `https://github.com/Spotui/Spotui/releases/tag/Release` | `https://github.com/Nonit0/Spotui/releases/latest` |
| 13 y 63 comentarios | Hablan del tag fijo `"Release"` | "latest GitHub release (tagged vX.Y)" / "version read from the release title" |

La versión se saca del título de la release (`"Spotui 1.7"` → `1.7`).

## 2. Preguntar al usuario (antes de empezar)
- [x] ¿Confirmas que solo toco ese fichero? → Sí.
- [x] ¿Seguirás publicando releases con tag `vX.Y` y título "Spotui X.Y"? → Sí.
- [x] ¿A qué repo apunta? → **`Nonit0/Spotui`** (el `origin` de este clon), no `Spotui/Spotui`.
- [ ] ¿Ya hay al menos una release publicada en `Nonit0/Spotui`? (condición para retomarla)

> Respuestas del usuario:
> 1. "Sí, hazlo, y seguiré publicando así."
> 2. "Quiero que IT-09 lo hagas después, que compruebe mi repo, que va a ser el que voy a usar; si no, déjalo igual o deshabilítalo, como veas mejor, pero déjalo documentado."

## 3. Salida esperada
- `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
- `grep -rn "Spotui/Spotui" app/src/main/java/com/music/spotui/data/update/` → sin resultados.
- Prueba manual: con `versionName` por debajo de tu última release (sin guardarlo), al arrancar sale el diálogo.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `app/src/main/java/com/music/spotui/data/update/UpdateChecker.kt` | 2 URLs + 2 comentarios |

## 5. Fuera de alcance
- El badge de Home (`data/api/UpdateChecker.kt`), que sigue apuntando a `Spotui/Spotui` (→ IT-03 / IT-04).
- Publicar la release en GitHub (lo hace el usuario).

## 6. Sugerencias
- Para publicar la primera release en tu fork: tag `v1.6` (o el siguiente), título "Spotui 1.6" y adjuntar el `.apk`. Así el botón "Upgrade" lleva directo a la descarga.

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | 🔧→↩️ Revertida | Se apuntó a `Spotui/Spotui/releases/latest`. Compiló (`BUILD SUCCESSFUL`), pero el usuario quiere su repo | — (`git checkout` del fichero, sin commit) |
| 2026-10-28 | ⏸️ Aplazada | `GET api.github.com/repos/Nonit0/Spotui/releases/latest` → **404**: el fork (`fork: true`, padre `Spotui/Spotui`) no tiene releases. Código **dejado igual que el original** | — |

### Por qué se deja igual y no se deshabilita
- **Tal como está, el diálogo ya es inofensivo.** Consulta el tag `Release` de `Spotui/Spotui`, que está en la 1.2.1. Como la versión instalada (1.6) es mayor, **nunca aparece**. Deshabilitarlo a mano sería tocar código para conseguir lo mismo.
- **Apuntarlo ya a tu repo también lo deja "apagado"** (la API devuelve 404 → `check()` devuelve `null` sin errores). Aun así se aplaza, porque así lo pediste y porque sin una release no se puede hacer la prueba manual de la *Salida esperada*.

### ⚠️ Riesgo que sigue abierto (no se toca aquí)
El **badge de Home** (`data/api/UpdateChecker.kt:10-11`) sí está activo y mira `Spotui/Spotui/releases/latest`.
Cuando el proyecto original publique una 1.7, tus usuarios verán "hay actualización" y les llevará al APK
**del original**, no al tuyo. Ese APK está firmado con otra clave, así que no se instalará encima del tuyo.
Se resuelve con la cadena IT-09 → IT-03 → IT-10 → IT-04. Si se necesita antes, la opción
atómica sería una iteración nueva: "Cambiar `data/api/UpdateChecker` a `Nonit0/Spotui`" (2 constantes).
