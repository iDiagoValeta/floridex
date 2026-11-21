# Floridex cards: writing spec (version 2)

Floridex identifies flowers from a photo and shows a card in Spanish from Spain. This spec is given to whoever writes the cards (a person or a language model). The card text itself is Spanish; this document is not.

Register: informative and precise, with a touch of poetry but no sentimentality. Never address the reader (the card talks about the flower). Full Spanish spelling (accents, ñ). Never use em dashes (—), en dashes (–) or middle dots (·). Do not make facts up: if unsure about a fact, pick another you know is true or leave the field as null.

## Full card (new species)

One JSON object per species, in the SAME order as the input, with exactly these fields:
- `key`: exact copy of the input.
- `nombre`: the most common Spanish (Spain) common name. If there is none, the most common one in Spanish anywhere; if there is none either, the genus name made Spanish (for example «Ofris abejera»). Two species cannot share exactly the same name: tell them apart («Lavanda de hoja dentada», «Lavanda fina»).
- `otros_nombres`: array of 0 to 3 alternative Spanish common names.
- `cientifico`: scientific name without authorship (the input's, unless clearly wrong).
- `familia`: botanical family.
- `descripcion`: 2 or 3 sentences to recognise it, FOCUSED ON THE FLOWER (shape, colour, size, inflorescence), then leaves or habit.
- `habitat`: 1 sentence on origin and where it grows.
- `floracion`: when it flowers in the northern hemisphere.
- `curiosidad`: 1 or 2 sentences with a true cultural, historical, etymological or ecological fact.
- `toxicidad`: null if not known to be toxic; otherwise 1 short warning sentence (people or pets).
- `cuidados`: if grown as an ornamental, 1 or 2 practical sentences (light, water, soil, pruning); null if wild and not grown.
- `simbolismo`: its meaning in the language of flowers or folk tradition, 1 sentence; null if it has no known one. Do not invent it.
- `etimologia`: 1 sentence on the origin of the scientific or common name.
- `usos`: traditional medicinal, culinary, melliferous or ornamental uses, 1 sentence; null if none. Medicinal uses always as «tradicionalmente», never recommending consumption.
- `grupo`: "jardin" if commonly grown as an ornamental; "silvestre" otherwise.
- `colores`: array of 2 hex colours of the real flower (dominant petal and accent).

## Extension (species that already have a card)

For each input (key, nombre, cientifico, familia) return ONLY `key`, `cuidados`, `simbolismo`, `etimologia`, `usos`, `grupo`, with the rules above.

## Delivery

Write the JSON array to the given output file. When done, check that it is valid JSON with as many items as the input, the same keys and the same order:

    python3 -c "import json;a=json.load(open('INPUT'));b=json.load(open('OUTPUT'));assert [x['key'] for x in a]==[x['key'] for x in b];print(len(b))"

Answer only with the number of cards and the species whose identification or common name is doubtful.
