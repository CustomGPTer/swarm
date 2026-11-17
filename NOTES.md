Fixes the DHT crawler, the sort buttons and website sources.

- The DHT crawler could stop collecting after Swarm was sent to the background and brought back. It now keeps going, and fetches four times as many torrents at once
- The Newest, Seeders and Biggest buttons on the DHT tab now work without a search, and a change of sort order goes back to the top of the list
- Internet Archive results say they are web seeded, not "0 seeders"; a tracker that has never heard of a torrent no longer counts as zero
- Website sources that list results without download links now work: Swarm gets the link from the result's own page when you tap Download, or opens the page for you to tap it

Android 10 or later, 64-bit. Install `Swarm.apk` below, over the version you have. Full notes: https://customgpter.github.io/swarm/releases.html
