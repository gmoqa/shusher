#!/usr/bin/env python3
"""
Genera la landing (docs/, servida por GitHub Pages) desde site/template.html y site/strings.json:
una página estática por idioma, con hreflang, URL canónica, datos estructurados y sitemap.

    python3 tools/build_site.py          # páginas y sitemap
    python3 tools/build_site.py --og     # además, las imágenes para compartir (requiere google-chrome e ImageMagick)
"""
import html
import json
import pathlib
import re
import subprocess
import sys
import tempfile

BASE = "https://gmoqa.github.io/shusher/"
LANGS = ["en", "es", "pt", "fr"]  # el primero es la portada (x-default)
FLAGS = {"en": "gb", "es": "es", "pt": "br", "fr": "fr"}  # las mismas banderas que la app
ICONS = ["battery_charging_full", "bolt", "check", "code", "expand_more", "hearing", "language", "nightlight"]

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE, DOCS = ROOT / "site", ROOT / "docs"
STRINGS = json.loads((SITE / "strings.json").read_text(encoding="utf-8"))


def path(lang):
    return "" if lang == LANGS[0] else f"{lang}/"


def icon_sprite():
    # Íconos Material Symbols Rounded (Apache 2.0) en línea: sin fuente de íconos externa que bloquee el render.
    out = []
    for name in ICONS:
        svg = (SITE / "icons" / f"{name}.svg").read_text()
        d = re.search(r'<path d="([^"]+)"', svg)[1]
        out.append(f'<symbol id="i-{name}" viewBox="0 -960 960 960"><path d="{d}"/></symbol>')
    return '<svg width="0" height="0" style="position:absolute" aria-hidden="true">' + "".join(out) + "</svg>"


def render(lang, template, sprite):
    t = STRINGS[lang]
    root = "" if lang == LANGS[0] else "../"
    url = BASE + path(lang)
    alternates = "\n".join(
        [f'<link rel="alternate" hreflang="{l}" href="{BASE + path(l)}">' for l in LANGS]
        + [f'<link rel="alternate" hreflang="x-default" href="{BASE}">']
    )
    menu = "\n".join(
        f'          <li><a href="{root}{path(l)}" hreflang="{l}" lang="{l}"'
        + (' aria-current="page"' if l == lang else "")
        + f'><img class="flag" src="{root}img/flags/{FLAGS[l]}.webp" width="24" height="18" alt="" loading="lazy">'
        + f"{html.escape(STRINGS[l]['name'])}</a></li>"
        for l in LANGS
    )
    jsonld = {
        "@context": "https://schema.org",
        "@type": "SoftwareApplication",
        "name": "Shusher",
        "description": t["desc"],
        "url": url,
        "inLanguage": lang,
        "image": BASE + "img/icon.png",
        "operatingSystem": "Android 8.0+",
        "applicationCategory": "LifestyleApplication",
        "isAccessibleForFree": True,
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        "downloadUrl": "https://github.com/gmoqa/shusher/releases/latest",
        "codeRepository": "https://github.com/gmoqa/shusher",
    }
    values = {
        "lang": lang, "root": root, "path": path(lang), "url": url, "alternates": alternates,
        "og_image": BASE + f"img/og-{lang}.jpg", "jsonld": json.dumps(jsonld, ensure_ascii=False),
        "icons": sprite, "lang_menu": menu, "flag": FLAGS[lang], "code": lang.upper(),
        "others": json.dumps(LANGS[1:]),
    }

    def sub(m):
        kind, key = m[1], m[2]
        if kind == "t":
            return html.escape(t[key])  # texto: escapado (sirve en contenido y en atributos)
        if kind == "h":
            return t[key]  # HTML propio y confiable de strings.json
        return values[key]

    page = re.sub(r"\{\{(?:(t|h):)?([\w.]+)\}\}", sub, template)
    missing = re.findall(r"\{\{[^}]*\}\}", page)
    assert not missing, f"{lang}: marcadores sin reemplazar {missing}"
    return page


def sitemap():
    urls = []
    for lang in LANGS:
        alts = "".join(f'<xhtml:link rel="alternate" hreflang="{l}" href="{BASE + path(l)}"/>' for l in LANGS)
        urls.append(f"  <url><loc>{BASE + path(lang)}</loc>{alts}</url>")
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
            + "\n".join(urls) + "\n</urlset>\n")


def og_images():
    # Imagen para compartir (1200x630) por idioma: se dibuja site/og.html con Chrome sin interfaz.
    og = (SITE / "og.html").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        for lang in LANGS:
            page = pathlib.Path(tmp) / f"og-{lang}.html"
            page.write_text(og.replace("{{h1}}", html.escape(STRINGS[lang]["hero.h1"]))
                              .replace("{{bubble}}", html.escape(STRINGS[lang]["bubble"]))
                              .replace("{{docs}}", DOCS.as_uri() + "/"), encoding="utf-8")
            shot = pathlib.Path(tmp) / f"{lang}.png"
            subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars",
                            f"--user-data-dir={tmp}/chrome", "--window-size=1200,630", "--virtual-time-budget=3000",
                            f"--screenshot={shot}", page.as_uri()], check=True, capture_output=True)
            # JPEG: la mitad de peso que PNG; WhatsApp y otras redes la cargan más rápido.
            subprocess.run(["magick", shot, "-strip", "-quality", "85", "-sampling-factor", "4:2:0",
                            DOCS / "img" / f"og-{lang}.jpg"], check=True)
            print(f"docs/img/og-{lang}.jpg")


def main():
    template = (SITE / "template.html").read_text(encoding="utf-8")
    sprite = icon_sprite()
    for lang in LANGS:
        out = DOCS / path(lang) / "index.html"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render(lang, template, sprite), encoding="utf-8")
        print(out.relative_to(ROOT))
    (DOCS / "sitemap.xml").write_text(sitemap(), encoding="utf-8")
    (DOCS / ".nojekyll").write_text("")  # GitHub Pages sirve los archivos tal cual, sin procesarlos con Jekyll
    print("docs/sitemap.xml")
    if "--og" in sys.argv:
        og_images()


if __name__ == "__main__":
    main()
