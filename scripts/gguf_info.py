"""Inspecciona la metadata KV de un GGUF sin cargar los pesos.

Uso: python scripts/gguf_info.py <archivo.gguf> [--plantilla]

Los mismos campos que Nodo leerá en la Fase 3 para la tarjeta de capacidad
(contexto, capas, cabezas, cuantización, plantilla de chat).
"""
import struct
import sys

TIPOS_SIMPLES = {
    0: ("<B", 1), 1: ("<b", 1), 2: ("<H", 2), 3: ("<h", 2),
    4: ("<I", 4), 5: ("<i", 4), 6: ("<f", 4), 7: ("<?", 1),
    10: ("<Q", 8), 11: ("<q", 8), 12: ("<d", 8),
}
TIPO_STRING = 8
TIPO_ARRAY = 9

CAMPOS_INTERES = (
    ".context_length", ".block_count", ".attention.head_count",
    ".attention.head_count_kv", ".embedding_length",
)


def _leer_string(f):
    n = struct.unpack("<Q", f.read(8))[0]
    return f.read(n).decode("utf-8", errors="replace")


def _leer_valor(f, tipo):
    if tipo in TIPOS_SIMPLES:
        fmt, tam = TIPOS_SIMPLES[tipo]
        return struct.unpack(fmt, f.read(tam))[0]
    if tipo == TIPO_STRING:
        return _leer_string(f)
    if tipo == TIPO_ARRAY:
        tipo_elem = struct.unpack("<I", f.read(4))[0]
        n = struct.unpack("<Q", f.read(8))[0]
        return [_leer_valor(f, tipo_elem) for _ in range(n)]
    raise ValueError(f"tipo GGUF desconocido: {tipo}")


def leer_metadata(ruta):
    with open(ruta, "rb") as f:
        if f.read(4) != b"GGUF":
            raise ValueError("no es un archivo GGUF")
        struct.unpack("<I", f.read(4))[0]   # versión
        struct.unpack("<Q", f.read(8))[0]   # n_tensors
        n_kv = struct.unpack("<Q", f.read(8))[0]
        metadata = {}
        for _ in range(n_kv):
            clave = _leer_string(f)
            tipo = struct.unpack("<I", f.read(4))[0]
            metadata[clave] = _leer_valor(f, tipo)
        return metadata


def resumir(ruta, mostrar_plantilla=False):
    md = leer_metadata(ruta)
    print(f"== {ruta}")
    print("  arquitectura:", md.get("general.architecture"))
    print("  nombre:", md.get("general.name"))
    for sufijo in CAMPOS_INTERES:
        for clave, valor in md.items():
            if clave.endswith(sufijo) and not clave.startswith("tokenizer"):
                print(f"  {clave}: {valor}")
    plantilla = md.get("tokenizer.chat_template")
    if plantilla is None:
        print("  chat_template: NO TIENE (Nodo usará el fallback ChatML)")
        return
    print(f"  chat_template: sí ({len(plantilla)} chars)")
    print("    soporta tools:", "tools" in plantilla)
    print("    emite tool_call:", "tool_call" in plantilla)
    if mostrar_plantilla:
        print("--- plantilla ---")
        print(plantilla)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    resumir(sys.argv[1], "--plantilla" in sys.argv)
