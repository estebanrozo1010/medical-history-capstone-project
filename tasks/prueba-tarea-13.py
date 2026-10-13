"""Prueba real de la Tarea 13 con curl: python tasks/prueba-tarea-13.py (--rapido se salta el link vencido).

Requiere mh-token.txt y mh-token-b.txt en %TEMP% (infra/id-token.mjs) y 3 archivos CLEAN de A. Las rutas /s/* van
SIN Authorization. Imprime solo 6 caracteres de los tokens; los completos quedan en %TEMP%/mh-13-tokens.txt para
buscarlos en CloudWatch (borrarlo después).
"""
import json
import os
import subprocess
import sys
import tempfile
import time

API = "https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1"
TMP = os.environ["TEMP"]
TA = open(os.path.join(TMP, "mh-token.txt"), encoding="ascii").read().strip()
TB = open(os.path.join(TMP, "mh-token-b.txt"), encoding="ascii").read().strip()
SALTAR_VENCIDO = "--rapido" in sys.argv
fallas = []


def curl(metodo, url, token=None, body=None, archivo=None, headers=None):
    with tempfile.TemporaryDirectory() as d:
        cab, cuerpo = os.path.join(d, "h"), os.path.join(d, "b")
        cmd = ["curl", "-s", "-X", metodo, "-D", cab, "-o", cuerpo, "-w", "%{http_code}", url]
        if token:
            cmd += ["-H", "Authorization: Bearer " + token]
        if body is not None:
            cmd += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(body)]
        if archivo:
            cmd += ["--upload-file", archivo]
        for k, v in (headers or {}).items():
            cmd += ["-H", f"{k}: {v}"]
        status = int(subprocess.run(cmd, capture_output=True, text=True, check=True).stdout)
        h = {}
        for linea in open(cab, encoding="latin-1").read().splitlines():
            if ":" in linea:
                k, v = linea.split(":", 1)
                h[k.strip().lower()] = v.strip()
        return status, h, open(cuerpo, "rb").read()


def verifica(nombre, cond, detalle=""):
    print(("OK   " if cond else "FALLA") + " " + nombre + ("" if cond else f"  -> {detalle}"))
    if not cond:
        fallas.append(nombre)


def archivos(token):
    return json.loads(curl("GET", API + "/files", token)[2])


def crear_link(ids, ttl):
    s, _, b = curl("POST", API + "/links", TA, {"fileIds": ids, "ttlMinutos": ttl})
    assert s == 201, (s, b)
    return json.loads(b)["token"]


# --- datos: 2 CLEAN de A que ya existen + 1 PDF nuevo (para eliminarlo luego) ---
limpios_a = [a for a in archivos(TA) if a["status"] == "CLEAN"]
assert len(limpios_a) >= 3, "A necesita al menos 3 CLEAN"
f1, f2, fuera = limpios_a[0], limpios_a[1], limpios_a[2]
b_pdf = next(a for a in archivos(TB) if a["nombre"] == "prueba-12-b.pdf")

pdf = os.path.join(TMP, "prueba-13.pdf")
open(pdf, "wb").write(b"%PDF-1.4\n% prueba Tarea 13\n%%EOF\n")
s, _, b = curl("POST", API + "/files/upload-url", TA,
               {"carpeta": "otros", "nombre": "Exámen 13 ñ.pdf", "contentType": "application/pdf",
                "size": os.path.getsize(pdf)})
sub = json.loads(b)
s, _, _ = curl("PUT", sub["url"], archivo=pdf, headers=sub["headers"])
assert s == 200, s
f3 = None
for _ in range(30):
    f3 = next(a for a in archivos(TA) if a["fileId"] == sub["fileId"])
    if f3["status"] == "CLEAN":
        break
    time.sleep(2)
verifica("PDF nuevo queda CLEAN", f3["status"] == "CLEAN", f3["status"])

token = crear_link([f1["fileId"], f2["fileId"], f3["fileId"]], 60)
print("link", token[:6], "con", f1["nombre"], "|", f2["nombre"], "|", f3["nombre"])

# --- resolver ---
s, h, b = curl("GET", f"{API}/s/{token}")
lista = json.loads(b)
verifica("GET /s/{token} 200 sin Authorization", s == 200, (s, b))
# fileIds es un SS: DynamoDB no conserva el orden, se compara como conjunto
nuevo = next((a for a in lista["archivos"] if a["fileId"] == f3["fileId"]), None)
verifica("lista los 3 con nombre, tipo y tamaño",
         {a["fileId"] for a in lista["archivos"]} == {f1["fileId"], f2["fileId"], f3["fileId"]}
         and nuevo == {"fileId": f3["fileId"], "nombre": "Exámen 13 ñ.pdf",
                                      "contentType": "application/pdf", "size": os.path.getsize(pdf)}, lista)
verifica("no-store y CORS en el 200", h.get("cache-control") == "no-store"
         and h.get("access-control-allow-origin") == "http://localhost:4200", h)

# --- descargar y ver cada uno siguiendo el 302 ---
for a in (f1, f2, f3):
    for modo, esperado in (("", "attachment"), ("?modo=ver", "inline")):
        s, h, _ = curl("GET", f"{API}/s/{token}/files/{a['fileId']}{modo}")
        ok = s == 302 and "location" in h and h.get("cache-control") == "no-store"
        verifica(f"302 {a['nombre']} {esperado}", ok, (s, h))
        if ok:
            s2, h2, cuerpo = curl("GET", h["location"])
            verifica(f"  S3 200, {len(cuerpo)} bytes = size, Content-Disposition {esperado}",
                     s2 == 200 and len(cuerpo) == a["size"]
                     and h2.get("content-disposition", "").startswith(esperado), (s2, len(cuerpo), h2))
            verifica("  firma de 60 s", "X-Amz-Expires=60" in h["location"], h["location"][:200])
print("  filename de f3:", h2.get("content-disposition"))

# --- fileId fuera del link ---
for nombre, fid in (("de A fuera del link", fuera["fileId"]), ("de B", b_pdf["fileId"]), ("inventado", "no-existe")):
    s, _, b = curl("GET", f"{API}/s/{token}/files/{fid}")
    verifica(f"fileId {nombre} -> 404", s == 404 and b"NO_ENCONTRADO" in b, (s, b))

# --- eliminar un archivo del link ---
# 409 = la réplica al respaldo sigue en curso justo después de subir (Tarea 10): se reintenta
for _ in range(6):
    s, _, _ = curl("DELETE", f"{API}/files/{f3['fileId']}", TA)
    if s != 409:
        break
    time.sleep(5)
verifica("DELETE del PDF nuevo 204", s == 204, s)
lista = json.loads(curl("GET", f"{API}/s/{token}")[2])
verifica("el eliminado desaparece de la lista", {a["fileId"] for a in lista["archivos"]} == {f1["fileId"], f2["fileId"]},
         lista)
s, _, _ = curl("GET", f"{API}/s/{token}/files/{f3['fileId']}")
verifica("descargar el eliminado -> 404", s == 404, s)

# --- 410: inventado, apagado, vencido ---
_, _, b410 = curl("GET", f"{API}/s/{'Z' * 43}")
s, _, _ = curl("PATCH", f"{API}/links/{token}", TA, {"activo": False})
verifica("PATCH apagar 200", s == 200, s)
for ruta in (f"/s/{token}", f"/s/{token}/files/{f1['fileId']}"):
    s, h, b = curl("GET", API + ruta)
    verifica(f"apagado {ruta.replace(token, token[:6] + '…')} -> 410 igual al inventado",
             s == 410 and b == b410 and h.get("cache-control") == "no-store", (s, b))
s, _, _ = curl("PATCH", f"{API}/links/{token}", TA, {"activo": True})
verifica("encender de nuevo -> GET 200", s == 200 and curl("GET", f"{API}/s/{token}")[0] == 200)

if not SALTAR_VENCIDO:
    corto = crear_link([f1["fileId"]], 1)
    verifica("link de 1 min vigente -> 200", curl("GET", f"{API}/s/{corto}")[0] == 200)
    print("esperando 65 s a que venza", corto[:6], "...")
    time.sleep(65)
    for ruta in (f"/s/{corto}", f"/s/{corto}/files/{f1['fileId']}"):
        s, _, b = curl("GET", API + ruta)
        verifica("vencido -> 410 igual al inventado", s == 410 and b == b410, (s, b))
    print("TOKENS_PARA_BUSCAR", token, corto, file=open(os.path.join(TMP, "mh-13-tokens.txt"), "w"))
else:
    print("TOKENS_PARA_BUSCAR", token, file=open(os.path.join(TMP, "mh-13-tokens.txt"), "w"))

print("\nfallas:", fallas or "ninguna")
sys.exit(1 if fallas else 0)
