## What's new

**Windows**
- New link grabber, like JDownloader's: a batch paste (a list of links, or ranges like `file[001-120].zip`) and several downloads caught from the browser at once now gather in one list. Each link is checked for name and size, you tick what you want, pick a folder and download them together.
- Fixed browser downloads going missing: when several downloads were caught at once, each replaced the one before it. They now collect in the link grabber, and a magnet or torrent waits its turn.

**Browser extension**
- Files are handed to the app as soon as the browser sees the response, before its own download starts. On Firefox the browser's download is blocked completely; on Chrome and Edge it is cancelled almost instantly, with the download bubble hidden.
- The browser's cancelled copy is now always removed from its downloads list, and a copy that already finished is deleted.
