# Translations of the presentation site

`index.html` is written in English. `fr.js` and `es.js` map each English text block of the page — its HTML,
with whitespace collapsed to single spaces — to its translation:

```js
window.KOCKPIT_I18N.fr = {
  "Get a console running in minutes.": "Une console qui tourne en quelques minutes.",
  ...
};
```

A block is the innermost element matched by the `SEL` list of the i18n script at the bottom of `index.html`
(headings, paragraphs, list items, card titles and descriptions, ...). A block with no entry stays in English, so
the page never breaks, but **changing an English block also means updating its key in both files**, or it falls
back to English.

Keep the markup of a translation identical to its English block (same `<code>`, `<b>`, `<a href>`, ... with the
same attributes); only the text changes. Code, commands and identifiers are never translated.

The language comes from `?lang=fr|es`, then from the visitor's last choice (`localStorage`), and is English by default.
