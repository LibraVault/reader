# Mamba-based TTS v2 Enhancement Plan

**Status**: Planned for post-v1.0 release  
**Priority**: Medium (quality/features enhancement)  
**Complexity**: High (ML training required)

## Overview

Explore training a lightweight, specialized Mamba-based TTS model (10-30M params) that natively handles e-reader metadata skipping (page numbers, footnotes, chapter markers) without post-processing. This complements or potentially replaces sherpa-onnx for higher quality narration on supported devices.

## Motivation

**Current Implementation (v1.0 - Pocket-TTS/sherpa-onnx):**
- ✅ Works, proven in testing
- ✅ Local-only, privacy-first
- ⚠️ Generic TTS, not optimized for e-reader clutter
- ⚠️ Requires separate text preprocessing for metadata

**Proposed v2 (Mamba):**
- 🎯 Specialized for e-reader workflows
- 🎯 Special tokens (<skip>...</skip>) learned during training
- 🎯 Seamless, human-like narration (no pauses on skipped text)
- 🎯 Smaller model footprint (10-30M vs. larger TTS engines)
- 🎯 Potential higher quality with discrete audio tokens

## Architecture

### Pipeline
```
Text Input → Phoneme Converter → Mamba Backbone (12-30M) 
→ Discrete Audio Tokens → Lightweight Vocoder (HiFi-GAN/Mimic) 
→ Audio Output
```

### Key Components
1. **Mamba Backbone**: Replace Transformer blocks with Mamba (state-space model) for efficiency
   - Base: `mamba-ssm-macos` or `state-spaces/mamba1`
   - Compress to 12-30M parameters for mobile deployment
   
2. **Audio Tokenization**: Use SpeechTokenizer to discretize audio
   - Maps continuous wav → discrete token sequence
   - Model learns to predict next audio token given text

3. **Vocoder**: Lightweight neural vocoder to convert tokens → audio
   - HiFi-GAN (small variant) or Mimic
   - Runs on-device with minimal overhead

4. **Metadata Handling**: Special token learning
   - Training data includes `<skip>Page 42</skip>`, `<skip>Ch. 3 Footnote</skip>`
   - Target audio ignores these (continuous playback)
   - Model learns zero-audio mapping for skip tokens

## Data Preparation

### Dataset Strategy
- **Size**: 3-5 hours clean audio (3,000-5,000 sentences)
- **Base**: Open audiobook dataset (LJSpeech, VCTK, or similar)
- **Augmentation**: Programmatically inject e-reader metadata

### Preparation Steps
1. Source clean audiobook data with transcripts
2. Parse transcripts and audio into sentence pairs
3. Inject fake e-reader clutter:
   ```
   Original: "She opened the door. The room was empty."
   Augmented: "She opened the door. <skip>Page 42</skip> The room was empty."
   ```
   - Keep audio unchanged (no pauses)
   - Model learns to ignore skip tokens
4. Convert audio to discrete tokens via SpeechTokenizer
5. Organize into train/val/test splits

### Data Preprocessing Script
```python
# Pseudocode for data injection
def augment_with_metadata(text, audio_path, skip_probability=0.3):
    # Randomly insert <skip>Page X</skip>, <skip>Ch. Y</skip>, etc.
    # Keep audio file unchanged
    # Return (augmented_text, audio_tokens)
```

## Training Plan

### Environment Setup (Mac + MLX/PyTorch MPS)
```bash
conda create -n ereader_tts python=3.10
conda activate ereader_tts
pip install torch torchaudio mamba-ssm-macos speechtokenizer huggingface-hub
```

### Training Loop
1. Initialize Mamba backbone (12-30M params, custom config)
2. Tokenize audio training data with SpeechTokenizer
3. Train to predict next audio token given:
   - Text tokens (including <skip> markers)
   - Previous audio tokens (autoregressive)
4. Loss: Cross-entropy on audio token prediction
5. Validation: Inference quality on held-out sentences

### Key Hyperparameters (TBD during experimentation)
- Model size: 12M-30M parameters
- Batch size: 16-32 (Mac GPU memory)
- Learning rate: 1e-4 to 1e-3
- Warmup: 1000 steps
- Total epochs: 20-50 (depends on convergence)
- Gradient checkpointing: Yes (memory efficiency)

### Expected Duration
- Data prep: 2-3 days
- Training (on Mac M1/M2): 3-7 days (continuous)
- Iteration + tuning: 2-3 weeks
- **Total**: 3-4 weeks of elapsed time (parallel work possible)

## Integration & Deployment

### Phase 1: Experimentation (Local)
- Train on Mac, validate inference quality
- Test on MacBook App (if Electron port exists)
- Collect metrics: latency, quality, VRAM usage

### Phase 2: Android Integration
- Export to ONNX (TorchScript → ONNX)
- Test with Android NDK build pipeline (like sherpa-onnx)
- Create AAR library with Mamba model + vocoder
- Integrate via TtsEngineFactory (swap backends)

### Phase 3: iOS Integration (Future)
- Export to CoreML (TorchScript → CoreML)
- Integrate into Swift/SwiftUI app
- Use Metal Performance Shaders (MPS) for inference
- Package as XCFramework

### Build Variant Control (Android)
```kotlin
// In TtsEngineFactory
if (BuildConfig.FLAVOR == "play" && mambaTtsEnabled) {
    return MambaTtsEngine(context)  // v2: Mamba
} else if (BuildConfig.FLAVOR == "play") {
    return PocketTtsEngine(context)  // v1: Pocket/sherpa-onnx (fallback)
} else {
    return AndroidTtsEngine(context) // F-Droid: Android TTS
}
```

### Export Targets
- **ONNX** (Android): `model.onnx` (NDK integration)
- **CoreML** (iOS): `model.mlmodel` (Swift integration)
- **Quantized**: INT8 quantization for smaller size

## Success Criteria

### Quality
- [ ] Inference quality as good as or better than sherpa-onnx (subjective + BLIP scoring)
- [ ] Metadata skipping seamless (no audio artifacts)
- [ ] Audio latency <500ms for typical sentence

### Performance
- [ ] Model size <50MB (quantized)
- [ ] Inference latency <100ms per second of audio
- [ ] Memory footprint <200MB peak on Android

### Coverage
- [ ] Works on Android API 30+
- [ ] Tested on ARM64, ARM32 (if feasible)
- [ ] Battery consumption reasonable (no regression vs. v1)
- [ ] iOS: Works on iPhone 12+ (simulator + real device)

## F-Droid Distribution Feasibility (Researched 2026-07-28)

Pocket-TTS (v1.0, sherpa-onnx) is currently Play-flavor only. Two objections were raised against bringing it — or a future Mamba v2 model — to the F-Droid flavor. Research into F-Droid's actual policy and a real precedent app softened both:

### Objection 1: Prebuilt native binary (`sherpa-onnx-android.aar`)

`core/tts` currently references a **committed, pre-compiled AAR** (`third-party/sherpa-onnx/sherpa-onnx-android.aar`, ~9.6MB, contains compiled `.so` libraries) rather than compiling it from source as part of the Gradle build graph. F-Droid's build servers compile from source with no network access, and generally reject prebuilt binary blobs.

**Precedent found**: [SherpaTTS](https://f-droid.org/packages/org.woheller69.ttsengine/) ([source](https://github.com/woheller69/ttsengine)) — an F-Droid-listed app built on the *same* sherpa-onnx/Next-gen Kaldi engine — ships an F-Droid build recipe with a `scanignore` directive on `app/src/main/assets`. That directive is how maintainers tell F-Droid's automated binary scanner "this has been manually reviewed, don't flag it" — strong evidence F-Droid tolerates a prebuilt-binary-with-review approach for native TTS libraries in practice, not just a from-scratch NDK/CMake compile. (Not fully confirmed: couldn't verify from public metadata alone whether they compile from source anyway — worth direct confirmation before relying on this.)

### Objection 2: Model download requires network access

The fdroid flavor manifest explicitly strips `INTERNET` permission (`tools:node="remove"`) as a self-imposed "zero network, ever" guarantee. Pocket-TTS's first-run model download needs one real network fetch.

**Precedent found**: SherpaTTS requests `INTERNET` permission and downloads its voice model from Hugging Face on first launch — F-Droid does **not** reject this. It surfaces an **Anti-Feature: NonFreeNet** badge ("depends on a non-free network service") in the client, and the app runs fully offline after that one-time download. This is exactly Pocket-TTS's existing behavior.

**Conclusion**: This is not an F-Droid policy blocker — it's our own product choice. The "no network, ever" invariant is something we set for ourselves, not something F-Droid requires.

### Implication for this plan

If/when a Mamba v2 model ships, F-Droid distribution (with an `Anti-Feature: NonFreeNet` flag for the initial model fetch, and a `scanignore`-style native library exception) is more realistic than originally assessed. Options going forward:

1. **Keep current split** (Play: Pocket-TTS, F-Droid: system TTS only) — status quo, zero-network purity preserved on F-Droid.
2. **Offer Mamba v2 (or sherpa-onnx) on F-Droid too**, accepting the `NonFreeNet` anti-feature flag, following SherpaTTS's precedent. Would need:
   - Wiring the AAR/native library build into the actual Gradle build graph (or confirming `scanignore` + manual review is sufficient, per SherpaTTS precedent)
   - Re-adding `INTERNET` permission to the fdroid manifest (scoped to model-download use only)
   - Deciding whether Mamba v2's smaller model size (10-30M params vs. sherpa-onnx's ~120MB) makes bundling the model **directly in the APK** feasible instead of downloading — this would avoid the network permission entirely, sidestepping objection 2 altogether. Still would need the model committed into the git tree at the tagged release commit (F-Droid never fetches GitHub Release assets), with the practical repo-size tradeoff that implies.

This is a product decision, not yet made — captured here for the v2 planning discussion, not committed to.

## Open Questions & Research

1. **Model architecture**: How to best compress Mamba for 12-30M params without quality loss?
2. **Audio tokenization**: Is SpeechTokenizer best, or are there better alternatives?
3. **Vocoder choice**: HiFi-GAN vs. Mimic vs. lightweight custom neural vocoder?
4. **Skip token effectiveness**: How well does the model learn to ignore skip tokens? Measure with ablation studies.
5. **Multiple languages**: How to adapt for non-English e-books?
6. **Fine-tuning**: Can we fine-tune a pre-trained Mamba model vs. training from scratch?
7. **iOS CoreML**: Does CoreML export preserve model quality? What's the inference speed on iPhone?
8. **F-Droid distribution**: Confirm directly with F-Droid (forum/IRC) whether SherpaTTS compiles its native library from source or relies on `scanignore` for a prebuilt binary — determines how much Gradle build-graph work is needed before F-Droid inclusion is viable. See "F-Droid Distribution Feasibility" section above.

## Risks & Mitigation

| Risk | Mitigation |
|------|-----------|
| Training takes too long | Use smaller model (10M), consider pre-trained base |
| Audio quality poor | Collect higher-quality audiobook data, iterate vocoder |
| Android integration fails | Prototype ONNX export early, test on real devices |
| iOS CoreML export fails | Early prototyping, test on simulator first |
| Model too large for mobile | Quantize aggressively (INT8), profile memory usage |
| Skip token learning fails | Use curriculum learning, explicit loss weighting |

## Timeline (Post-v1.0)

- **Week 1**: Data collection & preprocessing (LJSpeech augmentation)
- **Week 2-3**: Model training on Mac (parallel with other work)
- **Week 4**: Training iteration + quality evaluation
- **Week 5**: ONNX export + Android NDK integration
- **Week 6**: Testing & profiling on real devices
- **Week 7**: CoreML export + iOS integration
- **Week 8**: Documentation & merge prep

**Realistic**: 6-8 weeks of calendar time with focused effort.

## References & Resources

- **Mamba**: https://github.com/state-spaces/mamba
- **SpeechTokenizer**: https://github.com/zhangxinyu-xyz/SpeechTokenizer
- **HiFi-GAN**: https://github.com/jik876/hifi-gan
- **MeloVC (Community TTS)**: https://huggingface.co/shichaog/MeloVC
- **PyTorch MPS (Mac)**: https://pytorch.org/docs/stable/notes/mps.html
- **CoreML Export**: https://pytorch.org/docs/stable/generated/torch.jit.trace_module.html

## Next Steps (Post-v1.0)

1. Revisit this plan after Pocket-TTS v1.0 ships
2. Gather user feedback on current narration quality
3. If quality/metadata handling is a blocker, prioritize Mamba v2
4. Otherwise, consider it for v1.1 or later release cycle
5. Prototype data augmentation script (low-risk first step)

---

**Created**: 2026-07-21  
**Last Updated**: 2026-07-28  
**Owner**: @Rob  
**Status**: Backlog (post-v1.0)
