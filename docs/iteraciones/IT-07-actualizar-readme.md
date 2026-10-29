# IT-07 · Actualizar `README.md` con las funciones reales

| Campo | Valor |
|---|---|
| Tipo | Docs |
| Depende de | — |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
Añadir a la lista de funciones del `README.md` lo que existe en 1.6 y no aparece:
fuentes de audio (Deezer, Lossless/SpotiFLAC, Soulseek, YouTube Music), archivos locales,
traducción de letras, Android Auto, aviso de actualización, *swipe to queue*.

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿El README va en inglés (como ahora) o en español?
- [ ] ¿Se menciona Soulseek públicamente o se prefiere no destacarlo?
- [ ] ¿Añado los créditos a Metrolist (origen de `com.metrolist.*`)?

> Respuesta del usuario: "En inglés, menciona Soulseek y añade Metrolist."

## 3. Salida esperada
`README.md` con la sección de funciones ampliada; el resto del fichero intacto.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `README.md` | Ampliada la lista de funciones (+6 puntos) y añadido Metrolist a *Credits* (lo pidió el usuario) |

## 5. Fuera de alcance
Instrucciones de build, capturas nuevas, traducir el resto del README.

## 6. Sugerencias
- Iteración nueva: "Añadir sección *Build* al README" (JDK 17, `keystore.properties`, `scripts/sign-release.sh`).

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | `README.md`: 6 funciones nuevas (fuentes de audio con Soulseek, archivos locales, traducción de letras, Android Auto, *swipe to queue*, avisos de actualización) + crédito a [Metrolist](https://github.com/MetrolistGroup/Metrolist). El resto del fichero, intacto. 7 líneas añadidas | `6bca01d` |

**Nota:** la URL de Metrolist se comprobó en GitHub (`mostafaalagamy/Metrolist` redirige a `MetrolistGroup/Metrolist`).
**Nota:** el README dice "Update checker… on GitHub", pero hasta que se haga IT-09 los avisos miran el repo original `Spotui/Spotui`, no `Nonit0/Spotui`.
