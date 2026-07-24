# vLLM-Omni TTS Insights for LibraVault

**Source**: vLLM Engineering TTS Inference (June 2026)  
**Relevance**: Medium (cloud serving vs. on-device, but optimization principles transfer)

## Executive Summary

vLLM-Omni's approach to TTS optimization reveals that **different TTS architectures have fundamentally different bottlenecks**. Their solutions—streaming chunking, state management, torch.compile, model-specific kernels—are tailored per-model rather than one-size-fits-all.

For LibraVault:
- ✅ **Applicable**: Streaming chunk decoupling, state batching, per-request overhead reduction
- ⚠️ **Context-dependent**: torch.compile (needs Python runtime), GPU-resident state (limited mobile VRAM)
- ❌ **Not applicable**: Cloud serving throughput, high-concurrency batching (single user)

## Key Insights

### 1. TTS Differs Fundamentally from LLM Inference

**vLLM's Finding**: TTS has multiple pipeline stages with different bottlenecks:
- **Talker** (text → codec tokens): Latency-bound, single-token decode
- **Code2Wav** (codec → audio): Throughput-bound, parallel decode

**LibraVault Application**:
- sherpa-onnx OfflineTts is a **black-box** single stage (no visibility into Talker/vocoder split)
- Pocket-TTS model download + inference is latency-sensitive (user expects audio <1s)
- No concurrency requirements (single reader per device)

**Implication**: Focus on **latency and memory**, not throughput or batching.

### 2. Streaming Chunk Decoupling (Most Relevant)

**vLLM's Solution for Qwen3-TTS**:
```
Problem: Small streaming chunks hurt audio quality (no context)
         Large streaming chunks hurt TTFP (first packet delay)

Solution: Separate parameters:
  - codec_chunk_frames: connector chunk size (small → low latency)
  - decode_chunk_frames: vocoder context (large → better quality)
  - initial_codec_chunk_frames: first chunk smaller (start sooner)
```

**LibraVault Relevance**: ⭐⭐⭐ (HIGH)

Our current architecture (PocketPlayback → AudioTrack streaming) already does this implicitly:
- Model generates speech in chunks
- We stream audio to AudioTrack while continuing generation
- Could formalize chunk sizes for optimal TTFP vs. quality tradeoff

**Recommended**: Document and tune:
```kotlin
// In PocketTtsEngine config
val FIRST_AUDIO_CHUNK_MS = 100       // Reduce TTFP
val AUDIO_CONTEXT_FRAMES = 2048      // Maintain quality
val STREAMING_CHUNK_SIZE = 4096       // Balance both
```

### 3. Per-Request Overhead Reduction (Relevant for JNI)

**vLLM's Problem**: Python preprocessing loops dominated latency at c=64
- Speaker embedding extraction on CPU → GPU transfers
- Trailing text window updates with repeated tensor allocation
- req_id lookup using O(N) list scan

**Solutions Applied**:
- Batch GPU preprocessing (remove CPU-to-GPU transfers)
- Offset-based indexing instead of tensor slicing (avoid allocation)
- Dictionary lookup instead of list scan (O(N) → O(1))

**LibraVault Relevance**: ⭐⭐ (MEDIUM)

JNI boundary is our equivalent overhead:
- Kotlin → C++ calls
- State management (generation counter, audio buffer)
- Request context passing

**Applicable Tactics**:
```kotlin
// Batch preprocessing before JNI call (avoid repeated calls)
fun generateSpeech(texts: List<String>) {
    // Preprocess all at once
    val processedInputs = texts.map { preprocess(it) }
    
    // Single JNI call with batch
    val audioChunks = sherpaOnnx.generateBatch(processedInputs)
    
    // Stream results incrementally
    audioChunks.forEach { chunk -> 
        audioTrack.write(chunk)
    }
}
```

### 4. State Management: GPU vs. Python Loops

**vLLM's Finding (Higgs Audio V3)**:
- Moving decode state from Python dict to GPU tensors **reduced sync overhead**
- State includes: last_codes, delay_count, EOC flags, generation_done
- Challenge: scheduler reorders requests → CPU override must match GPU state

**LibraVault Relevance**: ⭐ (LOW-MEDIUM)

We have minimal state:
- `generationCounter` (prevent stale callbacks)
- `currentAudioBuffer` (playback position)
- `isPlaying` (engine state)

These are already efficiently managed. **Not a bottleneck** on mobile.

### 5. Model-Specific Attention Kernels (Fish Speech Example)

**vLLM's Finding**: Generic paged/varlen attention has overhead for decode-only shapes
- Fish Speech pure q_len=1 decode: write specialized Triton kernel
- Removed shape checks, branches, prefill handling
- Result: **Significant speedup** on q_len=1 workload

**LibraVault Relevance**: ⭐ (LOW)

We use sherpa-onnx (compiled ONNX), not raw Attention layers. Optimization must happen at **model level**, not kernel level.

**Could explore**: Ask if sherpa-onnx has decode-specific optimizations or if we should consider switching TTS models.

### 6. torch.compile and Graph Capture (VoxCPM2, Higgs)

**vLLM's Finding**:
- Per-layer compile had many Python-to-compiled boundaries
- Whole-model compile (fullgraph=False) reduced dispatch overhead
- CUDA Graph capture with dynamic batch shapes requires special handling

**LibraVault Relevance**: ⭐⭐ (MEDIUM)

**Future Mamba v2 consideration**:
If we train and deploy a custom Mamba TTS model:
- Use torch.compile whole-forward (not per-layer)
- Capture CUDA Graphs for fixed input shapes (on-device fixed batch=1)
- Export to ONNX with torch.onnx.export (handles compilation)

## Applicable Optimizations for LibraVault v1.1+

### Priority 1: Streaming Chunk Tuning (Easy, High Impact)
```
Current: Model generates → audio buffered → played on demand
Target: Formalize chunk strategy for TTFP vs. quality
Impact: Improve perceived latency, better audio continuity
```

### Priority 2: JNI Batching Strategy
```
Current: One text → one JNI call
Target: Batch multiple paragraphs, stream results
Impact: Amortize JNI overhead, reduce context switches
```

### Priority 3: Mamba v2 Model-Specific Optimizations
```
Target: Custom Mamba model trained with torch.compile + ONNX export
Optimizations: Whole-model compile, CUDA Graph for decode
Impact: Better quality, smaller model, faster inference
```

## Not Applicable to LibraVault

❌ **Cloud serving throughput** (vLLM's main use case)
- We serve single user, not c=64 concurrent requests
- Throughput is irrelevant; latency and memory matter

❌ **High-concurrency batching** (VoxCPM2, Higgs optimizations)
- No multi-user scenario on mobile device
- Batch size = 1 always

❌ **Adaptive graph capture with dynamic batch shapes**
- We have fixed batch=1, so CUDA Graphs just work (if we used CUDA)
- ONNX export handles compilation automatically

## Recommendations

### Short-term (v1.0 release, current)
- ✅ Keep current sherpa-onnx approach
- ✅ Document streaming chunk strategy in code
- ✅ Profile TTFP vs. quality tradeoff

### Medium-term (v1.1, Pocket-TTS optimization)
1. Implement structured chunk-size tuning
2. Profile JNI overhead, consider batching paragraphs
3. Measure TTFP with different chunk sizes
4. Document optimal settings for different devices

### Long-term (Mamba v2)
1. Train lightweight Mamba model with torch.compile
2. Export to ONNX with fullgraph=False (let Dynamo compile edges)
3. Compare inference speed, quality, model size vs. current sherpa-onnx
4. Swap via TtsEngineFactory build variant if better

## References

- **vLLM-Omni TTS**: Engineering challenges, stage separation, torch.compile benefits
- **Applicable models**: Qwen3-TTS (streaming chunk tuning), Fish Speech (model-specific kernels)
- **Not applicable models**: VoxCPM2 (high-concurrency CFM batching—cloud-only)

---

**Date**: 2026-07-21  
**Status**: Design reference for v1.1+ planning
