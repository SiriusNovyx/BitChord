# Windows ARM64 native components

BitChord remains GPLv3. Its portable package includes the project license and
component notices; dependency JARs retain their own metadata and license files.
The ARM64 native dependency preparation scripts preserve upstream inputs and
record binary hashes rather than changing architecture labels.

| Component | ARM64 source/artifact | License and provenance |
|---|---|---|
| Java runtime | Azul Zulu 21.52.203, OpenJDK 21.0.12.1+1, Windows AArch64 | Runtime `legal/` directory, GPLv2 with Classpath Exception. Official archive `zulu21.52.203-ca-jdk21.0.12.1-win_aarch64.zip`, SHA256 `f7b84d12d3ea269595de00dfbd8b9ec83b60cd9324efa265a0bc85466dd41a50`; [vendor metadata](https://api.azul.com/metadata/v1/zulu/packages/03f2d71b-86e0-4b3c-aa74-1bf9bccb965e). |
| Java 25 runtime candidate | Azul Zulu 25.36.205, OpenJDK 25.0.4.1+1, Windows AArch64 | Runtime `legal/` directory, GPLv2 with Classpath Exception. Official archive `zulu25.36.205-ca-jdk25.0.4.1-win_aarch64.zip`, SHA256 `654b92047fcc4e5106f7b73a2f93d9c5fcb399f4658ce4d693228c7acb52e4ad`; [vendor metadata](https://api.azul.com/metadata/v1/zulu/packages/1b39f50f-d426-4b04-a4ba-bd7f05f4a7d5). Java 21 remains the fallback; see [migration validation](desktop-java25.md). |
| Graphics | Compose 1.10.3 / Skiko 0.9.37.4 | Unmodified Maven artifacts and their notices |
| FFmpeg | BtbN `ffmpeg-n8.1-latest-winarm64-lgpl-shared-8.1.zip`, SHA256 `8b647ad0a5a5e12e9da5cd6b6600cce9e58c50430114d76185f5533bb451b713` | LGPLv3; archive license embedded in native JAR. Runtime reports `n8.1.3-18-g46d899d7a0-20261014`. Build project: <https://github.com/BtbN/FFmpeg-Builds>. FFmpeg source: <https://github.com/FFmpeg/FFmpeg/commit/46d899d7a0>. Java API is 8.1.2-1.5.14, the same 8.1 ABI; decoder regressions verify this pairing. |
| JavaCPP JNI | JavaCPP 1.5.14, locally compiled FFmpeg wrappers | Apache-2.0 or GPL with Classpath Exception; generated source retains copyright/license headers. <https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg> |
| ONNX Runtime | v1.29.0 commit `2e2543fbe9fae542f921d47a72d21d5a4ef0b710`, MSVC ARM64 Java/shared build | MIT and upstream ThirdPartyNotices. <https://github.com/microsoft/onnxruntime/tree/v1.29.0> |
| QuickJS JNI | quickjs-kt v1.0.14 commit `6e51ae878be54f22272381e62118535790ba9940` with its exact engine/c-vector submodules | Apache-2.0 wrapper, MIT QuickJS/c-vector and winpthreads notices. Compiled with LLVM-MinGW 20261006 UCRT ARM64, statically linked pthread support. <https://github.com/dokar3/quickjs-kt/tree/v1.0.14> |
| Browser host | Microsoft.Web.WebView2 SDK 1.0.4258.31 static ARM64 loader | SDK LICENSE.txt and NOTICE.txt in `licenses/webview2/`. The Edge WebView2 Runtime is a separately installed prerequisite; it is not redistributed in this ZIP. <https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4258.31> |

Keep the native source revisions, build scripts, generated JNI sources and
corresponding component source available when distributing a release. This
local build has not been published. The existing release workflow remains x64.
