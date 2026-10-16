# Spec UI Emby → PinerTV (actualizado)

Referencia: Emby Android TV 2.x / APK Google arm64 (WebView+Leanback).
PinerTV recrea el look en Leanback nativo.

## Look aplicado

- Fondo oscuro `#101014` / deep `#0A0A0C`
- Acento verde Emby-like `#52B54B`
- Home: cabecera lateral + filas horizontales + **fondo dinámico** al seleccionar card
- Seguir viendo: cards **landscape** (thumbs)
- Catálogo: posters 2:3, rating badge, scale-on-focus, borde verde
- Detalle: backdrop a pantalla completa + scrim + poster elevado + CTAs estilo Emby
- Splash: gradiente + tipografía marca PinerTV

## Pantallas

| Emby | PinerTV |
|--|--|
| Splash / connecting | SplashActivity |
| Home rows + artwork bg | HomeFragment + BackgroundManager |
| Library grid | LibraryActivity |
| Details + backdrop | DetailsActivity |
| Player | PlayerActivity |
| Settings | SettingsActivity (búsqueda / long-press logo) |
