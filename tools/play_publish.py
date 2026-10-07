#!/usr/bin/env python3
"""
Sube la ficha de Google Play (textos e imágenes de fastlane/metadata/android) y, opcionalmente, un AAB.

    uvx --with google-api-python-client --with google-auth python tools/play_publish.py listing
    uvx --with google-api-python-client --with google-auth python tools/play_publish.py bundle app-release.aab --track alpha

Correo de contacto público: PLAY_CONTACT_EMAIL (Play lo exige).
Credenciales: cuenta de servicio en ~/keystores/play-publisher.json (o PLAY_CREDENTIALS). Nunca en el repo.
Tracks: internal, alpha (prueba cerrada), beta (prueba abierta), production.
"""
import argparse
import os
import pathlib

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PKG = "com.gmoqa.shusher"
ROOT = pathlib.Path(__file__).resolve().parent.parent
META = ROOT / "fastlane" / "metadata" / "android"
CREDS = os.environ.get("PLAY_CREDENTIALS", str(pathlib.Path.home() / "keystores" / "play-publisher.json"))
WEBSITE = "https://gmoqa.github.io/shusher/"
IMAGES = {  # tipo de imagen de la API -> archivo o carpeta en images/
    "icon": "icon.png",
    "featureGraphic": "featureGraphic.jpg",
    "phoneScreenshots": "phoneScreenshots",
    "tenInchScreenshots": "tenInchScreenshots",
}


def api():
    creds = service_account.Credentials.from_service_account_file(
        CREDS, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False)


def read(path):
    return path.read_text(encoding="utf-8").strip()


def upload_listing(play, edit):
    for loc in sorted(p for p in META.iterdir() if p.is_dir()):
        lang = loc.name
        play.edits().listings().update(packageName=PKG, editId=edit, language=lang, body={
            "language": lang,
            "title": read(loc / "title.txt"),
            "shortDescription": read(loc / "short_description.txt"),
            "fullDescription": read(loc / "full_description.txt"),
        }).execute(num_retries=5)
        for kind, name in IMAGES.items():
            src = loc / "images" / name
            files = sorted(src.iterdir()) if src.is_dir() else [src] if src.exists() else []
            if not files:
                continue
            # Reemplaza las imágenes de ese tipo: así subir dos veces no las duplica.
            # (num_retries: la API responde 503 de vez en cuando.)
            play.edits().images().deleteall(packageName=PKG, editId=edit, language=lang, imageType=kind).execute(num_retries=5)
            for f in files:
                play.edits().images().upload(packageName=PKG, editId=edit, language=lang, imageType=kind,
                                             media_body=MediaFileUpload(str(f))).execute(num_retries=5)
        print(f"{lang}: textos e imágenes")
    play.edits().details().update(packageName=PKG, editId=edit, body={
        **play.edits().details().get(packageName=PKG, editId=edit).execute(num_retries=5), "contactWebsite": WEBSITE,
        # Play exige un correo de contacto y lo muestra públicamente en la ficha.
        **({"contactEmail": os.environ["PLAY_CONTACT_EMAIL"]} if os.environ.get("PLAY_CONTACT_EMAIL") else {}),
    }).execute(num_retries=5)


def upload_bundle(play, edit, aab, track):
    bundle = play.edits().bundles().upload(packageName=PKG, editId=edit, media_body=MediaFileUpload(
        aab, mimetype="application/octet-stream", resumable=True)).execute(num_retries=5)
    code = bundle["versionCode"]
    notes = [{"language": loc.name, "text": read(loc / "changelogs" / f"{code}.txt")}
             for loc in sorted(META.iterdir()) if (loc / "changelogs" / f"{code}.txt").exists()]
    play.edits().tracks().update(packageName=PKG, editId=edit, track=track, body={
        "track": track,
        "releases": [{"versionCodes": [str(code)], "status": "completed", "releaseNotes": notes}],
    }).execute(num_retries=5)
    print(f"versionCode {code} -> {track}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["listing", "bundle"])
    ap.add_argument("aab", nargs="?")
    ap.add_argument("--track", default="alpha")
    args = ap.parse_args()
    play = api()
    edit = play.edits().insert(packageName=PKG, body={}).execute(num_retries=5)["id"]
    try:
        if args.what == "listing":
            upload_listing(play, edit)
        else:
            upload_bundle(play, edit, args.aab, args.track)
        play.edits().commit(packageName=PKG, editId=edit).execute(num_retries=5)
        print("cambios guardados en Play Console")
    except Exception:
        play.edits().delete(packageName=PKG, editId=edit).execute(num_retries=5)  # nada a medias
        raise


if __name__ == "__main__":
    main()
