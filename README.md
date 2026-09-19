# SoloLatino CloudStream Extension

Extensión de [CloudStream](https://github.com/recloudstream/cloudstream) para **https://sololatino.net** — películas, series, animes y doramas en español latino.

## Estructura

```
sololatino-ext/
├── build.gradle.kts              # Config raíz (plugins CloudStream + repos)
├── settings.gradle.kts           # Incluye automáticamente cada plugin
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── gradle-daemon-jvm.properties
│   └── wrapper/
├── .github/workflows/build.yml   # CI: compila y publica en la rama `builds`
└── SoloLatinoProvider/
    ├── build.gradle.kts          # Metadatos del provider (version, status, tvTypes)
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/sololatino/ext/
            ├── SoloLatinoProvider.kt       # Provider principal
            ├── Embed69Extractor.kt         # Extractor embed69 (POW + AES)
            └── SoloLatinoProviderPlugin.kt # Registro del provider
```

## Selectores usados (verificados contra el sitio actual)

| Función | Selector | Nota |
|---|---|---|
| Catálogo / búsqueda | `div.card` → `a[href]`, `p.card__title`, `img.card__poster`, `span.card__year` | Igual en portada, listados y `/buscar?q=` |
| Paginación | `link[rel=next]` | El sitio usa `<link rel="next">` en `/peliculas?page=N` |
| Ficha | `h1`, `img.w-44`, `.detail-hero__bg`, `p.text-sm.leading-relaxed` | título, poster, fondo, sinopsis |
| Géneros | `a[href*='/genero/']` | deduplicados |
| Episodios | `div[data-season-panel] > a.ep-item`, `p.ep-num` → `E12` | temporada desde el atributo |
| Reproductores | `button.server-btn[data-player-token]` → `POST /api/player-url` con `X-CSRF-TOKEN` → `{url, type}` | |
| Embeds | `embed69.org` (POW+AES), `xupalace.org`, mp4 directo, iframe genérico | con normalización de hosts (streamwish, vidhidepro, etc.) |

## Antes de publicar (2 cambios obligatorios)

1. En `build.gradle.kts` (raíz) y en `SoloLatinoProvider/build.gradle.kts`, reemplaza `municipalidad1998` por tu usuario de GitHub.
2. Sube el repo a GitHub (rama `master` o `main`). El workflow:
   - compila con `./gradlew make makePluginsJson`,
   - sube `SoloLatinoProvider.cs3` + `plugins.json` a la rama **`builds`**,
   - y ahí queda la URL de repositorio para la app:

   ```
   https://raw.githubusercontent.com/municipalidad1998/sololatino-ext/builds/plugins.json
   ```

> Nota: CloudStream usa `plugins.json` como manifiesto (el `repo.json` clásico es el formato antiguo; la app actual consume `plugins.json` con los mismos campos).

## Build local (opcional)

Requiere JDK 17 y Android SDK:

```bash
./gradlew make makePluginsJson
# salida: SoloLatinoProvider/build/SoloLatinoProvider.cs3 y build/plugins.json
```

## Bump de versión

Cada vez que cambies el código, sube `version = N` en `SoloLatinoProvider/build.gradle.kts` para que la app detecte la actualización.
