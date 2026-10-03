# CloudStreamHub reliability implementation

- [x] Inspect source, open issues, provider inventory and playback contracts.
- [x] Correct torrent metadata, YTS API identity and three-per-quality selection.
- [x] Expand Hub integration, category/episode handling and progressive timeouts.
- [x] Repair runtime domains and tier-specific issue lifecycle.
- [x] Audit enabled providers with live layer-specific evidence; repair JetFilmIzle's verified search/player routes and retire the closed TurkAnime upstream.
- [x] Run independent tests, version/artifact gates and final review; correct the verified issue-recovery finding.

## Review

Automated gates passed: 68 targeted Kotlin tests, 44 targeted Python tests, all 23 generated artifact hashes/versions, and the shared-core version rule for 32 modules. Hub version 27 packages 20 federation providers and TauVideo. Independent review's premature issue-closure finding was corrected and independently verified.

Device follow-up: CloudStream 4.8.0 rejected the compiled `SendChannel.close$default` call, cancelling HTTP federation after torrent callbacks. Explicit `channel.close(null)` removes that host ABI dependency. All 30 Hub tests, actual bytecode inspection, version/hash gates and independent review passed. Version 27 was transferred by wireless ADB with an identical device hash; the Saplantı retry emitted six preflight-valid HTTP callbacks across FilmMakinesi, HDFilmCehennemi, HDFilmDelisi, KultFilmler and JetFilmIzle without the previous ABI error. Callback evidence does not establish sustained playback.

The user subsequently reported decoder initialization error 4001 across site alternatives. A fresh single-source FilmMakinesi retry played successfully without code or codec-setting changes; device logs showed `c2.mtk.avc.decoder` and AAC decoder initialization. The earlier decoder failure remains unreproduced, with its original detailed logs unavailable. FilmMakinesi startup has user/device evidence; sustained playback, seeking and other providers remain pending.

Bridgerton S04E06: first/third 2160p Torrentio alternatives reached the local media server but failed with error 2000: host `UpdatedMatroskaExtractor` committed sample metadata into Media3 `SampleQueue` with no upstream format (NullPointerException). The second 2160p alternative timed out awaiting torrent/bootstrap response headers. The first 1080p candidate (409 seeds, 2.59 GB) played after automatic fallback, confirmed by the user. No speculative index/codec filtering change was made; host-parser failures and torrent bootstrap timeout remain separate unresolved layers. Redacted evidence: ignored `build/device-bridgerton-4x6-errors.log`.

Device playback on GM 24 PRO / CloudStream 4.8.0 remains UNVERIFIED_MANUAL: torrent startup/file selection, ten-minute playback and seeking, plus representative series/anime/documentary playback. Seven live Python media-path probes are not Android playback proof. No commit, push, publishing or GitHub issue mutation was performed.

Hub v28 external torrent follow-up: added the explicit Torrent → VLC action using the existing host torrent-to-HTTP conversion, with session consent and cancellation preserved. All 34 Hub tests, version/artifact gates (23 plugins), packaged DEX/4.8 ABI audit and independent review passed. The matching v28 artifact was installed locally; device logs/menu confirmed registration and handoff to VLC. VLC sustained playback remains unverified. Manual HTTP handoff to MX Player produced 3840×2160 HEVC Main 10 video, but EAC3 was unsupported and playback stuttered. Device vendor codec metadata limits hardware HEVC/AVC/VP9 decoding to 2560×1440; MX logged rejected hardware video and software HEVC decoding. The sampled torrent rate was about 196 KB/s. These are separate codec/device/peer limitations, not proof that every 4K source is unusable. No CloudStream APK change or GitHub publication was performed.

Hub v29 adds the separate Torrent → MX Player action, including its documented subtitle/resume/result contract. All 35 Hub tests, 23 artifact hashes, version/4.8 ABI gates and independent review passed. The matching local artifact was hot-reloaded without restarting the host process; both actions appeared in the menu. Selecting Bridgerton S04E06's first 2160p candidate through the new MX action opened its explicit playback activity and produced video, confirmed by a device screenshot and the user. The user explicitly accepted 4K picture startup as this phone test's criterion, despite stuttering/audio limitations. This startup criterion passed; smooth playback, EAC3 audio and TV playback are not claimed. TV was not operated. No commit, push, upstream PR or APK modification was performed.
