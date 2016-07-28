# InfoQ Presentations

Video is in an HTML5 `video` element (at least when serving to Chrome
on macOS). Link for video probably uses referrer for access (nope, it
uses cookies too, it points at CloudFront). Also, the video URL
appears to be in an inline `script` element, where it assigns `P.s` to
the video URL.

Slide sync and slide image URLs are inside an inline `script` element
that has the time (in seconds, it looks like) in the `TIMES` var, and
the slide images in the `slides` var.

So. Fetch the main presentation page, and parse the HTML. Iterate over
`script` elements, and run the contents if they don't have `src`
attributes in them. For scanning the video block, we just need to set
up a `P` object in the context, and then execute the script (possibly
just prepend something dumb like `var P = {};` to the script
body). For the slide setup, just run the script and pull out the
`slides` and `TIMES` variables.

Generate a video from the slide images, which switches slides based on
the `TIMES` array. Then, do a PinP combination of the main video, and
the generated slide video. Done.

## Video cookie

```
Cookie:
__gads=ID=a35759ebcf9e9e61:T=1468520390:S=ALNI_MZ-1J00uNgkFnXYKz_VRffXel1c3Q;
_ceg.s=oabhl4;
_ceg.u=oabhl4;
__utma=213602508.829564730.1468520392.1468520392.1468520392.1;
__utmz=213602508.1468520393.1.1.utmcsr=google|utmccn=(organic)|utmcmd=organic|utmctr=(not%20provided);
CloudFront-Signature=UjNopIX4IvmyqNYqY-E~eXxe8Kr-d9lO35rIavBf5pjSmeTY91ZiaO8g1JG1T6jNiQgk1IIoVKAEdooHYI~dshR6G0K6w9OWuDYWVpsh9mZP5nJFl-QHGR3J8uXl69IzSgawiSCXTTr1RvmcNf2rceiQA3PP9QZ2owFigwqhQHM_;
CloudFront-Policy=eyJTdGF0ZW1lbnQiOiBbeyJSZXNvdXJjZSI6IioiLCJDb25kaXRpb24iOnsiRGF0ZUxlc3NUaGFuIjp7IkFXUzpFcG9jaFRpbWUiOjE0Njk2OTA4ODl9LCJJcEFkZHJlc3MiOnsiQVdTOlNvdXJjZUlwIjoiMC4wLjAuMC8wIn19fV19;
CloudFront-Key-Pair-Id=APKAIMZVI7QH4C5YKH6Q
```

## Slide videos

Right now I'm just generating a video of the appropriate length for
each slide, then concatenating them all. The generation of static
images into videos (at the right frame rate) takes a long time, and it
seems like that could be sped up.

Note that I think we need to have the slide video framerate match
(close enough) the speaker video framerate, because if we use say a
very low framerate (one per second?) for the slides, the speaker video
seems to get downsampled to that rate. I don't know. Ffmpeg is weird.
