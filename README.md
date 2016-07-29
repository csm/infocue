# infocue

Hacky clojure app to download the video and slides from an InfoQ
presentation, and combine the two into a single picture-in-picture
video.

Usage:

    lein run <url>

Requires `ffmpeg`, `exiftool`, and `swfrender` installed and in your
PATH. Using Homebrew:

    brew install ffmpeg
    brew install exiftool
    brew install swftools

For other systems perform whichever other incantation manifests the
magic for you.
