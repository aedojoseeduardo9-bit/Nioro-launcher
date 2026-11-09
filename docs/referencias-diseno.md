# Referencias de diseño (GitHub)

38 repositorios para inspirarse al diseñar Nioro Launcher: sistemas de diseño, cristal líquido,
formas de dibujar, animación, iconos y *skills* para Claude. Estrellas aproximadas a noviembre de 2026.
Son referencias para **leer e inspirarse**: Nioro Launcher no usa Compose ni AndroidX, así que las
ideas se trasladan a `Canvas` a mano (no se añaden como dependencias).

## 1. Cristal líquido y desenfoque (Android)
| Repo | ★ | Para qué sirve |
|---|---|---|
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | 4.4k | El efecto Liquid Glass más completo en Android: refracción, lente y brillo. Ideal para mejorar `Glass.kt`. |
| [skydoves/Cloudy](https://github.com/skydoves/Cloudy) | 1.3k | Desenfoque y cristal acelerados por GPU; buena idea para sustituir el desenfoque en CPU. |
| [chrisbanes/haze](https://github.com/chrisbanes/haze) | 2.7k | Desenfoque de fondo "en vivo" detrás de paneles (como el dock de iOS). |
| [styropyr0/PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) | 43 | Cristal con muestreo del fondo, refracción de borde y reflejos especulares en AGSL. |
| [Mortd3kay/liquid-glass-android](https://github.com/Mortd3kay/liquid-glass-android) | 187 | Glassmorphism con shaders AGSL (Android 13+): desenfoque, distorsión, brillo. |
| [NEORUAA/Glasmio](https://github.com/NEORUAA/Glasmio) | 80 | Recreación de Liquid Glass de iOS con animaciones y controles. |

## 2. Shaders: otras formas de dibujar
| Repo | ★ | Para qué sirve |
|---|---|---|
| [mejdi14/Android-AGSL-Shader-Playground](https://github.com/mejdi14/Android-AGSL-Shader-Playground) | 308 | Colección de efectos AGSL listos para copiar (ondas, ruido, degradados). |
| [drinkthestars/shady](https://github.com/drinkthestars/shady) | 170 | Muchos shaders AGSL de ejemplo; útil para fondos animados. |
| [vishal2376/vortex](https://github.com/vishal2376/vortex) | 25 | Experimentos con `RenderShader` y `RenderEffect` (las APIs que usaríamos sin Compose). |
| [google/skia](https://github.com/google/skia) | 11k | El motor 2D que hay debajo de `Canvas` en Android: entender cómo dibuja de verdad. |

## 3. Launchers de código abierto (cómo lo resuelven otros)
| Repo | ★ | Para qué sirve |
|---|---|---|
| [LawnchairLauncher/lawnchair](https://github.com/LawnchairLauncher/lawnchair) | 13.9k | Basado en Launcher3 (Pixel): carpetas, widgets, iconos temáticos, gestos. |
| [MM2-0/Kvaesitso](https://github.com/MM2-0/Kvaesitso) | 5.3k | Launcher centrado en la búsqueda; buen diseño de buscador y widgets. |
| [NeoApplications/Neo-Launcher](https://github.com/NeoApplications/Neo-Launcher) | 2.2k | Launcher moderno y muy configurable. |
| [McCal-Codes/folio](https://github.com/McCal-Codes/folio) | 114 | Pantalla de inicio estilo iPhone para Android. |
| [lesnolie/Tigris](https://github.com/lesnolie/Tigris) | 592 | Launcher con dock Liquid Glass y centro de control de cristal (Android TV). |

## 4. Estilo One UI / Material
| Repo | ★ | Para qué sirve |
|---|---|---|
| [OneUIProject/oneui-design](https://github.com/OneUIProject/oneui-design) | 220 | Componentes One UI de Samsung (cabecera grande, interruptores, listas). Referencia para Ajustes. |
| [tribalfs/sesl-material-components-android](https://github.com/tribalfs/sesl-material-components-android) | 36 | Componentes Material modificados al estilo One UI. |
| [material-components/material-components-android](https://github.com/material-components/material-components-android) | 17k | Medidas, movimiento y accesibilidad de Material Design. |

## 5. Sistemas de diseño y *tokens*
| Repo | ★ | Para qué sirve |
|---|---|---|
| [alexpate/awesome-design-systems](https://github.com/alexpate/awesome-design-systems) | 26k | Lista de sistemas de diseño de grandes empresas para estudiar. |
| [VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md) | 125k | Archivos DESIGN.md de marcas conocidas: idea para escribir el `DESIGN.md` de Nioro. |
| [style-dictionary/style-dictionary](https://github.com/style-dictionary/style-dictionary) | 4.9k | Genera colores/tamaños para varias plataformas desde un solo archivo (*tokens*). |
| [argyleink/open-props](https://github.com/argyleink/open-props) | 5.6k | Escalas de tamaños, sombras, curvas de animación y colores bien pensadas. |
| [shadcn-ui/ui](https://github.com/shadcn-ui/ui) | 127k | Componentes con valores por defecto muy cuidados; buena guía de espaciados y estados. |
| [DouyinFE/semi-design](https://github.com/DouyinFE/semi-design) | 10k | Sistema de diseño completo con miles de *tokens*. |

## 6. Animación y física
| Repo | ★ | Para qué sirve |
|---|---|---|
| [pmndrs/react-spring](https://github.com/pmndrs/react-spring) | 29k | Referencia de animaciones con resortes (la misma idea que `Spring.kt`). |
| [skevy/wobble](https://github.com/skevy/wobble) | 865 | Resorte amortiguado exacto en ~1,7 KB: comparar con `SpringValue`. |
| [airbnb/lottie-android](https://github.com/airbnb/lottie-android) | 36k | Animaciones de After Effects en Android (iconos animados, pantallas de bienvenida). |
| [rive-app/rive-android](https://github.com/rive-app/rive-android) | 547 | Gráficos interactivos con estados; alternativa más ligera a Lottie. |

## 7. Iconos y dibujo a mano
| Repo | ★ | Para qué sirve |
|---|---|---|
| [lucide-icons/lucide](https://github.com/lucide-icons/lucide) | 25k | Iconos lineales coherentes en rejilla de 24, como `Glyphs` en `Ui.kt`. |
| [excalidraw/excalidraw](https://github.com/excalidraw/excalidraw) | 135k | Estilo "dibujado a mano"; útil para bocetar pantallas antes de programarlas. |
| [tldraw/tldraw](https://github.com/tldraw/tldraw) | 51k | Lienzo infinito; ideas de gestos, zoom y selección. |
| [terkelg/awesome-creative-coding](https://github.com/terkelg/awesome-creative-coding) | 15k | Arte generativo y programación creativa: ideas para fondos y efectos. |

## 8. *Skills* de diseño para Claude
| Repo | ★ | Para qué sirve |
|---|---|---|
| [anthropics/skills](https://github.com/anthropics/skills) | 187k | Las *skills* oficiales de Anthropic (incluye diseño de interfaces y arte con código). |
| [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | 145k | *Skill* con conocimiento de UI/UX para varias plataformas. |
| [plugin87/ux-ui-agent-skills](https://github.com/plugin87/ux-ui-agent-skills) | 3.1k | Tokens, 52 componentes y accesibilidad WCAG como *skills*. |
| [oil-oil/oil-ui](https://github.com/oil-oil/oil-ui) | 2.7k | *Skill* para explorar y comparar varias direcciones de diseño antes de elegir. |
| [superdesigndev/superdesign-skill](https://github.com/superdesigndev/superdesign-skill) | 0.9k | *Skill* para evitar interfaces genéricas ("AI-slop"). |
| [Yang-Ya-Chao/android-design-system-skills](https://github.com/Yang-Ya-Chao/android-design-system-skills) | 16 | *Skills* de sistema de diseño Android: neumorfismo y glassmorphism. |

## 9. Bordes y movimiento estilo Apple (usados en el launcher)
| Repo | ★ | Para qué sirve |
|---|---|---|
| [phamfoo/figma-squircle](https://github.com/phamfoo/figma-squircle) | 0.3k | Geometría de las esquinas continuas ("corner smoothing" de Figma, como iOS). **Se usa en `Glass.kt` (`Squircle`)**. |
| [MartinRGB/Figma_Squircles_Approximation](https://github.com/MartinRGB/Figma_Squircles_Approximation) | — | El código original de esa aproximación. |
| [lapfelix/UIView-SmoothCorners](https://github.com/lapfelix/UIView-SmoothCorners) | 89 | Cómo activa iOS las esquinas continuas en sus vistas. |
| [racra/smooth-corner-rect-android-compose](https://github.com/racra/smooth-corner-rect-android-compose) | 74 | Squircles matemáticamente exactos en Android. |
| [stoyan-vuchev/squircle-shape](https://github.com/stoyan-vuchev/squircle-shape) | 98 | Otra implementación de squircle para Android. |
