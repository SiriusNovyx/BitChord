`red.mp4` is a synthetic six-frame, 64x64 MPEG-4 video with a red background.
It contains no audio or third-party media. It was generated with:

```text
ffmpeg -f lavfi -i color=c=red:s=64x64:r=25 -frames:v 6 -c:v mpeg4 -pix_fmt yuv420p red.mp4
```

The decoder regression checks native video decoding, BGRA channel order and
looping across EOF on both Windows architectures.
