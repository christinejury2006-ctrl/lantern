# Where Lore's Explore tab gets books

Written after the round in which Explore was rebuilt, so the reasoning lives next to the code that
depends on it. Everything below was checked against the live service in that round unless it says
otherwise.

## The question this answers

"Search the internet for a book, download it into the library" splits into two very different
problems, and conflating them is what made the old screen feel broken:

1. **A file this app can actually keep and open.** Only works for books that are free to
   redistribute: public domain, or published open access.
2. **A book that is still in copyright.** No legal file exists to download. The useful answer is
   where it can be read: a library loan, a borrow, a shop.

Explore now answers both, in two separate sections ("Download free" and "Read or borrow").

## Sources implemented

| Source | Endpoint | What it gives | Licence | Reaches |
| --- | --- | --- | --- | --- |
| **Gutendex** (Project Gutenberg mirror) | `gutendex.com/books?search=…` | Direct `application/epub+zip` and `application/pdf` links | None needed, no key | 70,000+ public-domain titles |
| **Internet Archive** | `archive.org/advancedsearch.php`, then `archive.org/metadata/{id}` | EPUB / "Text PDF" files of items that are not access-restricted | Open items only | Scanned public-domain books and some modern open items |
| **Open Library** | `openlibrary.org/search.json` | Work records, covers, `ebook_access`, Archive identifiers | None needed | 40M+ records — *every* modern book, usually as borrow or record only |
| **OpenAlex** | `api.openalex.org/works?filter=type:book,is_oa:true` | Direct `pdf_url` for books published open access | None needed (free daily budget) | Academic books, including books published this year |

Borrow links come from the Internet Archive's own lending (`archive.org/details/{id}`, one copy at a
time, free account) and the Open Library record. That is the honest, legal route to a 2019 novel.

## Sources researched and **not** wired in, with reasons

| Source | Verdict |
| --- | --- |
| **Standard Ebooks** | Excellent public-domain EPUBs, but their OPDS/Atom feeds are Patrons-Circle-only since 2024 (the public feed URL returns a donation page). Individual downloads remain free; adding a title only works if we scrape their HTML search, which breaks whenever they redesign. Left out rather than shipped fragile. |
| **ManyBooks / Feedbooks** | Both publish OPDS feeds (`manybooks.net/opds/index.php`, `catalog.feedbooks.com/catalog/index.atom`). Most of their catalogue is the same public-domain texts Gutenberg already supplies, and feed availability has been intermittent. Candidates for a future source, not worth a third duplicate list now. |
| **OAPEN / DOAB** (open-access books) | The right idea — current scholarly books, CC-licensed, free — but `library.oapen.org/rest/search` answered *"Your address is not allowed to access this API"* in this round, and DOAB's DSpace REST is similarly gated. OpenAlex already indexes the same books with working `pdf_url`s, so it covers this ground. |
| **Wikisource** | Public domain, huge, and has a clean API (`/w/api.php`). Not wired in because its texts are wiki pages rather than EPUBs; producing a book means an HTML→EPUB pipeline that the app no longer has (the Studio writer was deleted). |
| **Google Books** | Metadata only (descriptions, covers, store links). Used for the "More books" section and the details sheet, never for files. |
| **HathiTrust** | Full view only for public domain, and their API is for bibliographic data; downloads are page images. Redundant with Gutendex + Archive. |
| **Z-Library, Anna's Archive, Library Genesis, Ocean of PDF, PDF Drive** | **Not used, deliberately.** They host in-copyright books without permission. Shipping a scraper for them means: infringement in most jurisdictions, downloading unverifiable files from frequently-seized hosts, and an app that cannot vouch for what it put on the reader's phone. The owner's `fetchScrapedBooks` function is preserved in `ExploreScreens.kt` exactly as written, but nothing calls it. |

## Practical notes for whoever touches this next

- **Parallel is per source.** Every lookup is wrapped in `runCatching`: one catalogue being down
  costs its own results, never the whole search.
- **The Archive is the slow one** (two requests per item to learn the real file name), so it is
  skipped when another source already produced an exact title match.
- **Downloads are validated before they are kept.** `BookIo.downloadEdition` writes to a `.part`
  file, checks it with `BookFiles` (real ZIP/PDF containers, not just size), and only then lets it
  replace anything. A login wall or a 404 page never becomes a book.
- **Covers are best-effort** and never block a download.
- **Rate limits**: Gutendex is best-effort with no published limit — cache and don't crawl. Open
  Library asks for roughly 100 requests per 5 minutes per IP. OpenAlex shares a free daily budget
  per IP, which is why the query asks for five results and no more.
