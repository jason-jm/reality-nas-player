# Film matching in Reality NAS Player 1.1.0

Add a NAS folder and scan it. The player reads any Kodi movie information and artwork already in the folder. For a bare video file it searches public catalogues by title and release year. It starts with every.film, then uses NeoDB, Wikidata and Open Film Database when necessary. Film lookup works without a database account, an API key or a server run by the app’s developer. The first online scan needs an internet connection. Each provider can limit requests, and results are cached on the device. [every.film API terms](https://every.film/api-terms-of-use) · [NeoDB terms](https://neodb.social/pages/terms/) · [Wikidata](https://www.wikidata.org/wiki/Wikidata:Data_access) · [Open Film Database API](https://ofdb.cc/api-info)

## What the release tests measured

On November 19, 2026, we checked 402 real video files across two mounted NAS shares. Different encodes of the same movie counted once. We also included five television or broadcast files to check whether the player would give them an unrelated film poster.

| Test set | Films | Correct film | Wrong film | Awaiting identification | Correct films with a poster | Broadcast files incorrectly given a film |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Existing reference library | 251 | 250 | 0 | 1 | 250 | — |
| Documentary shelf used during development | 4 | 4 | 0 | 0 | 4 | 0 of 2 |
| Reserved titles, first run | 12 | 12 | 0 | 0 | 11 | 0 of 3 |
| Same reserved titles, release build logic | 12 | 12 | 0 | 0 | 12 | 0 of 3 |

The twelve reserved titles and their expected years were recorded before their first run. At that point, eleven returned the expected year. After that first run we changed the resolver and used the same titles for regression. All twelve then returned the expected year, and the missing poster was recovered. Counting the reserved titles once, 266 of 267 movie works were identified, all 266 had a poster and 264 had a Chinese synopsis. One shortened title remained available for manual matching. These figures describe this library.

The reference library was replayed against recorded anonymous catalogue responses using the shared Kotlin engine. Each pass read the actual media paths, local metadata and artwork from the mounted NAS. The new test sets also used live catalogue requests. The matching code received filenames, folders and eligible local metadata. The scorer separately read the expected titles, verified identities and release years. It accepted a one-year difference for regional release dates in the original reference library. One multi-part file contains both halves of a film and is scored as one work. Its local NFO describes the first half. Correct identity counts measure recognition of the work. They do not measure complete metadata for every edition inside a file. Individual library paths and raw responses stay private.

## Changes verified against these files

- A feature film in its own documentary folder can acquire a poster. Broadcaster folders and numbered episodes retain their existing treatment.
- A catalogue may list a movie under its full subtitle while a filename uses the shorter name. The player can retrieve the illustrated record when the catalogues supply the same IMDb film ID. It keeps the original identification when the IDs disagree.
- For a film whose filename omits the year, a matching public identifier can confirm a premiere a year before a regional release. The test also checks that a different film ID cannot change that year.
- The Chinese interface skips Japanese plot text mislabeled as a Chinese translation. A downloaded gateway error can be retried, and a failed image replacement preserves the last good poster.
