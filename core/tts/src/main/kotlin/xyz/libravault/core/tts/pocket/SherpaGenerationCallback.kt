package xyz.libravault.core.tts.pocket

/**
 * The per-chunk callback sherpa-onnx invokes while synthesizing.
 *
 * This exists as a named class rather than a plain lambda for a JNI reason
 * that is invisible at the Kotlin level. `generateWithConfigAndCallback` takes
 * a `(FloatArray) -> Int`, and the native side resolves it with
 *
 *     GetMethodID(callbackClass, "invoke", "([F)Ljava/lang/Integer;")
 *
 * - the *specialized* `invoke`, not the erased `invoke(Object)Object` of the
 * `Function1` interface. Kotlin 1.x compiled every lambda to a real class
 * carrying both, so upstream's sample works. Kotlin 2.x compiles lambdas via
 * `invokedynamic`, which D8 desugars into `$$ExternalSyntheticLambda` classes
 * that implement **only** the erased method. Passing a lambda from Kotlin 2.x
 * therefore fails that lookup at the first generated chunk with
 *
 *     NoSuchMethodError: no non-static method "...$$ExternalSyntheticLambda0
 *         .invoke([F)Ljava/lang/Integer;"
 *
 * which, being raised inside a native frame, aborts the process outright under
 * CheckJNI. This project is on Kotlin 2.2, so every Pocket TTS utterance hit
 * it - the reason Read Aloud never produced audio (issue #107).
 *
 * An explicitly declared class is compiled the old way regardless of the
 * compiler's lambda strategy, so this is stable against future Kotlin
 * versions in a way that a `-Xlambdas=class` flag would not be. The
 * `PocketTtsEngineJniContractTest` unit test pins the emitted descriptor.
 */
internal class SherpaGenerationCallback(
    private val onChunk: (FloatArray) -> Unit,
) : Function1<FloatArray, Int> {

    override fun invoke(samples: FloatArray): Int {
        onChunk(samples)
        return CONTINUE
    }

    companion object {
        /** Return value telling sherpa-onnx to keep synthesizing. */
        const val CONTINUE = 1

        /** Return value telling sherpa-onnx to stop early. */
        const val STOP = 0

        /** The exact descriptor sherpa-onnx's JNI looks up. */
        const val REQUIRED_JNI_DESCRIPTOR = "([F)Ljava/lang/Integer;"
    }
}
