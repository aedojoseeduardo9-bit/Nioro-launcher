# Nioro Launcher

Launcher para Android (nativo, Kotlin, sin HTML) con estética **liquid glass** estilo Apple:
dock de macOS con magnificación + organización tipo One UI.

## Descargar
Cada push a `main` compila el APK en GitHub Actions y lo publica en la release **latest**:

https://github.com/aedojoseeduardo9-bit/Nioro-Launcher/releases/download/latest/Nioro-Launcher.apk

(Escanea un QR de ese enlace para instalarlo desde el móvil.)

Después de instalar: abre **Ajustes de Nioro Launcher → Establecer como launcher predeterminado**.

## Qué trae
- Dock de cristal con magnificación al deslizar el dedo, puntitos de apps abiertas, botón Launchpad y botón Recientes.
- Usa las apps del sistema (Teléfono, Mensajes, Cámara, Navegador, Ajustes) en el dock por defecto.
- **5 vistas de apps**: Launchpad · Abanico (pila del dock) · Rejilla flotante · Lista A‑Z · Gravedad (física Box2D).
- **5 estilos** por categoría: abrir apps, volver al inicio, apps en segundo plano, magnificación del dock, rebote del dock y cambio de página — con vista previa en Ajustes.
- Física de resortes en todas las animaciones; el bucle de dibujo solo corre mientras algo se mueve (0 % CPU en reposo) y hay modo ahorro.

## Gestos
- Deslizar arriba: todas las apps · Deslizar abajo: notificaciones
- Mantener un icono: menú / moverlo (arrástralo al dock o arriba para quitarlo)
- Mantener el fondo: modo edición (fondo, vista de apps, animaciones y ajustes)
- Todos los gestos se cambian en **Ajustes → Gestos y vibración**; la acción «Menú del inicio» abre el menú con Ajustes del launcher, Fondo de pantalla y Siguiente vista de apps
