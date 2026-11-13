# Audio corrections

Three narrow corrections make track boundaries exact where Media3 or a device decoder gets them wrong. Each one applies only when the file proves it qualifies. None inspects audio amplitude, and none changes encoded media bytes. Any layout outside the listed evidence keeps native behaviour.

- Legacy MP3: on Android 8.0, defer the transition notification for one software decoder.
- AAC shortened final sample: supply missing end padding from the edit list.
- AAC rounded edit endpoint: clamp an edit that a coarse movie clock rounded past the media.
- Status: Implemented. Physical-speaker continuity is not established by decoded-sample tests.

## Legacy MP3 transitions

On API 26, `LegacyMp3AudioRenderer` defers transition discontinuities only for `OMX.google.mp3.decoder`. Its final decoded buffer follows the stream-end timestamp. Notifying the sink earlier drains encoder padding before reconfiguration ([#30](https://github.com/anastaci1a/localica/issues/30)).

The next output-format configuration releases the deferred notification. Position resets and disposal clear it at once. Delay and padding still come from Media3 metadata. No fixed sample count is subtracted. Other decoders and other API levels keep the native renderer.

## AAC edit lists

`PlaybackExtractors` keeps Media3 extraction and reads bounded MP4 metadata through the registered source. There are two cases ([#28](https://github.com/anastaci1a/localica/issues/28)).

### Shortened final sample

This correction supplies missing end padding. Packet timestamps, edit metadata and intentional silence are unchanged.

| Required evidence | Constraint |
| --- | --- |
| Container | Known length, unfragmented MP4, one audio track, metadata within the inspection bound. |
| Codec | Unencrypted AAC-LC, 1,024 samples per access unit, explicit SBR-absent sync extension, no alternate frame length. |
| Timing | Media clock equals sample rate. Frame durations are constant except the final one. Counts and media duration agree. |
| Edit | One rate-one edit with an exact integral audible length ending at the declared media endpoint. |
| Existing trim | Delay equals the edit start; end padding is zero. |

The edit owns audible duration. Padding is the decoded frame extent beyond that endpoint:

$$
N_{\mathrm{audible}} = \frac{D_{\mathrm{edit}}\, f_{\mathrm{sample}}}{T_{\mathrm{movie}}},
\qquad
N_{\mathrm{padding}} = N_{\mathrm{frames}}\, N_{\mathrm{frame}} - N_{\mathrm{delay}} - N_{\mathrm{audible}}.
$$

The correction requires an exact integer result smaller than one frame. Fixture provenance lives in `app/src/androidTest/assets/README.txt`.

### Rounded edit endpoint

A coarse movie clock can round a sole audio edit beyond the declared media endpoint. Media3's two-tick tolerance can then discard preroll packets. This case preserves all declared access units and clamps the rounded endpoint to the existing media extent.

| Required evidence | Constraint |
| --- | --- |
| Container | Known length, bounded ordinary moov header, one unencrypted AAC audio track and one rate-one edit. |
| Headers | Version zero. Movie, track and edit durations agree before correction. |
| Sample tables | Constant positive packet durations. Size and chunk tables agree with media duration and actual `mdat` bounds. |
| Rounding | The endpoint exceeds native tolerance but stays within half a movie-clock tick of the media extent. |
| Existing trim | No conflicting gapless metadata. Native corrected end padding stays zero. |
| Output | Paired `PlaybackRenderersFactory` and `MediaEditAudioSink`. Marked encoded bypass or offload is rejected. |

A same-width metadata overlay expresses movie, track and edit durations in media ticks. Source files are untouched. An unknown-length source bypasses inspection and adds no reopen.

The corrected format carries media-clock units through `Format.customData`. The sink binds them to the actual decoded PCM rate and the first buffer's timestamp. After a reconfiguration or seek it recomputes the remaining leading trim, and never applies the full edit again:

$$
N_{\mathrm{remaining}} = \max\left(0,
\left\lfloor\frac{s_{\mathrm{edit}}\,f_{\mathrm{PCM}}}{T_{\mathrm{media}}}\right\rfloor
-\left\lceil\frac{\max(0,p_{\mathrm{PCM}}-o_{\mathrm{stream}})\,f_{\mathrm{PCM}}}{10^{6}}\right\rceil\right).
$$

PTS and stream offset use microseconds. Flooring the edit permits less than one output frame of rounding. Ceiling the truncated PTS recovers its represented frame. Invalid units, unsupported decoded formats and overflow fail through the sink's error contract. Foreign custom data passes through unchanged.

## Tests

`LegacyMp3BoundaryTest`, `AacEditListExtractionTest`, `MediaEditSinkLifecycleTest`, `PrivateQueueBoundaryTest`. Boundary tests compare decoded samples and waveform alignment; sample counts alone are insufficient.
