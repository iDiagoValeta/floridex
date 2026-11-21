# Fichas de Florilegio (versión 2)

Florilegio es una app que identifica flores con una foto y muestra una ficha en castellano de España.
Registro divulgativo, preciso, con algo de poesía pero sin cursilería. Tuteo nunca (la ficha habla de la flor).
Ortografía española completa (tildes, eñes). Prohibido usar rayas (—), guiones medios (–) y puntos medios (·).
No inventes datos: si dudas de un dato concreto, elige otro que sepas cierto o deja el campo en null.

## Ficha completa (para especies nuevas)
Un objeto JSON por especie, en el MISMO orden que la entrada, con exactamente estos campos:
- "key": copia exacta de la entrada.
- "nombre": nombre común en castellano de España más usado. Si no hay ninguno extendido, el más usado en
  castellano en general; y si tampoco existe, el nombre del género castellanizado (p. ej. «Ofris abejera»).
  Dos especies distintas no pueden compartir exactamente el mismo nombre: si dos se llaman igual, distínguelas
  («Lavanda de hoja dentada», «Lavanda fina»).
- "otros_nombres": array de 0 a 3 nombres comunes alternativos en castellano.
- "cientifico": nombre científico sin autoría (el de la entrada salvo error evidente).
- "familia": familia botánica.
- "descripcion": 2 o 3 frases para reconocerla, CENTRADAS EN LA FLOR (forma, color, tamaño, inflorescencia),
  y luego hojas o porte.
- "habitat": 1 frase sobre origen y dónde crece.
- "floracion": cuándo florece en el hemisferio norte.
- "curiosidad": 1 o 2 frases con un dato cultural, histórico, etimológico o ecológico cierto.
- "toxicidad": null si no es tóxica conocida; si lo es, 1 frase breve de aviso (personas o mascotas).
- "cuidados": si se cultiva como ornamental, 1 o 2 frases prácticas (luz, riego, suelo, poda); si es
  silvestre y no se cultiva, null.
- "simbolismo": su significado en el lenguaje de las flores o en tradiciones populares, 1 frase; null si no
  tiene uno conocido. No lo inventes.
- "etimologia": 1 frase sobre el origen del nombre científico o del común.
- "usos": usos medicinales tradicionales, culinarios, melíferos u ornamentales, 1 frase; null si no tiene.
  Los medicinales, siempre como «tradicionalmente» y sin recomendar su consumo.
- "grupo": "jardin" si se cultiva habitualmente como ornamental; "silvestre" en caso contrario.
- "colores": array de 2 colores hex de la flor real (pétalo dominante y acento).

## Ampliación (para especies que ya tienen ficha)
Para cada entrada (key, nombre, cientifico, familia) devuelve SOLO:
"key", "cuidados", "simbolismo", "etimologia", "usos", "grupo", con las mismas reglas de arriba.

## Entrega
Escribe el array JSON en el fichero de salida indicado. Comprueba al terminar que es JSON válido y que tiene
tantos elementos como la entrada, con las mismas key y en el mismo orden:
python3 -c "import json;a=json.load(open('ENTRADA'));b=json.load(open('SALIDA'));assert [x['key'] for x in a]==[x['key'] for x in b];print(len(b))"
Responde solo con el número de fichas y las especies cuya identificación o nombre común sea dudoso.
