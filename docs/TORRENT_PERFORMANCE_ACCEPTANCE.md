# CloudStreamHub high-bitrate torrent stabilization (PR #21)

## Why this change exists
A valid magnet, a Torrentio stream entry, successful HTTP 206 or passing unit tests **do not** demonstrate 4K Blu-ray/REMUX playback. The reference investigation in `research/reports/TORRENT_4K_PLAYBACK_COMPARISON.md` documents failed Android TV MX/VLC playback, slow final-file ranges, and a separate Media3/Matroska stack failure.

### Protocol facts verified from pinned upstream TorrServer source
- Version `recloudstream/torrentserver@7861970` serves `/stream/<name>?link=...&index=N&preload&stat`.
- Preload is synchronous and prepares beginning/end file pieces before responding.
- The JSON `file_stats` contains zero-based `id`, `path`, `length`. File ID 0 may be omitted from JSON because `omitempty`.
- `/settings` with `action=set` drops all running torrents and restarts the connection. This PR **does not** change global server cache settings.
- Byte-range support comes from `http.ServeContent`; malformed range or wrong selected file must not be presented as playback-ready.

## Scope of runtime changes
1. Check the server-reported file metadata before handing off to external players. If preload HTTP times out or returns a transient error, follow up with a **stat-only** request; media probes are forbidden until selected file identity is verified. Reject missing/ambiguous index, non-video selection and inconsistent file size. Recognize `.m2ts` for Blu-ray transport streams. Do **not** treat `.iso` as directly playable.
2. Retain bounded head/tail byte probes and preserve cancellation. Transient server responses (408/425/429/5xx) retry; invalid/missing range protocol and file identity mismatches fail early.
3. Keep different file indices of a multi-file torrent distinct during source selection (same info hash is not the same episode).
4. Deliver Torrentio/YTS discoveries independently instead of awaiting both before emitting; failures of one source do not cancel valid other results. Emit elapsed milliseconds per batch for benchmarking.
5. Bound aggregation to a 45-second total federation window, plus independent 20-second torrent/direct discovery ceilings; already-emitted links survive expiration. Cap provider callback queues at 128 candidates with a reserved high-quality 32-candidate lane, so late-arriving 2160p/REMUX results are not excluded solely because low-resolution links arrived first.
6. Prevent caching resolved short-lived HTTP media URLs from Hub (torrent hash/link candidates only). Only emit cached torrents through the torrent policy **after** fresh sources, never through the direct-media collector (which deliberately rejects magnets).

## Performance methodology
Record p50 and p95 across repeated runs, for *each* host/version/content type, and include both cold and warm states:
- Time to first **source**; time to first **decoded frame**; time to audible output.
- Selected torrent file hash and index (redact private info), peers/seeders, download speed, time to head/tail byte range.
- Seek time at ~25%, ~75% of duration; playback stalls over a 10-minute run; cancellation latency.
- Memory peak and device temperature/thermal throttling, if available.
- 1080p single-file MKV; 2160p HEVC; 2160p Blu-ray REMUX with TrueHD/DTS-HD; multi-file series torrent; zero/low peer negative case.
- CloudStream built-in, VLC and MX Player on **actual Android TV**, separately. Never infer codec support from the torrent title alone.

## Obtaining a candidate for device verification
- Open the **Validate** GitHub Actions run from this PR. Once checks complete successfully, download the `CloudStreamHub-pr-...` workflow artifact, unzip it, and verify it contains `CloudStreamHub.cs3`.
- This is **not** a production release. The workflow does not push to `main` or `builds`; the artifact is intended for controlled manual installation according to the CloudStream host's supported local testing method. If local plugin installation is unavailable, do not publish the unverified candidate through the production repository; use an isolated test distribution.
- Run the same device/content cases with both the current production plugin and the PR candidate; record CloudStream version, TV model, player, first frame, audio, 25%/75% seek and 10-minute buffering.
- Artifact success proves packaging, **not** runtime 4K decode or torrent swarm reliability.

## Mandatory manual checks before marking playback resolved
- [ ] On Android TV, selected movie/episode matches intended file and stream index.
- [ ] 2160p MKV/Remux first frame and audio work on real device, not just HTTP probes.
- [ ] Seek to middle and late part works without indefinite buffering.
- [ ] No false ready state for 404/403/500, malformed response, wrong file, subtitle/sample, or expired local server.
- [ ] Repeated runs show stable memory and no growth from duplicate resolver calls.
- [ ] The user's torrent consent and cancellation paths remain intact.
- [ ] GitHub CI `Validate` passes; no unrelated provider version bumps.

## Explicitly NOT claimed
No device verification was performed by this patch. Peer availability, network throughput, native decoder support, and external player bugs can still prevent playback. This does not patch the CloudStream application, change TorrServer internals or globally force cache sizes.
