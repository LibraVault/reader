package xyz.libravault.core.tts.pocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the JNI contract between [SherpaGenerationCallback] and sherpa-onnx.
 *
 * The native side resolves the streaming callback with
 * `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")`. If that method
 * stops being emitted - because someone "simplifies" the class back into a
 * lambda, or a future Kotlin release changes how it compiles function types -
 * every Pocket TTS utterance aborts the process inside a native frame.
 *
 * This is a JVM test on purpose. The on-device test that would also catch it
 * only runs on arm64 hardware via manual dispatch, whereas this runs on every
 * push, which is where a Kotlin version bump would first show up.
 */
class SherpaGenerationCallbackTest {

    @Test
    fun `emits the specialized invoke method sherpa-onnx looks up by descriptor`() {
        val method = SherpaGenerationCallback::class.java
            .methods
            .singleOrNull { it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) }

        assertTrue(
            method != null,
            "No invoke(float[]) on SherpaGenerationCallback - sherpa-onnx's " +
                "GetMethodID(\"invoke\", \"${SherpaGenerationCallback.REQUIRED_JNI_DESCRIPTOR}\") will fail",
        )

        // Boxed Integer, not primitive int: the descriptor the native code
        // hardcodes is ([F)Ljava/lang/Integer;.
        assertEquals(
            java.lang.Integer::class.java,
            method!!.returnType,
            "invoke(float[]) must return a boxed Integer to match ${SherpaGenerationCallback.REQUIRED_JNI_DESCRIPTOR}",
        )
    }

    @Test
    fun `is a real class rather than a synthetic lambda`() {
        val type = SherpaGenerationCallback::class.java

        // D8 desugars invokedynamic lambdas into synthetic classes that carry
        // only the erased invoke(Object)Object, which is precisely the failure
        // this class exists to avoid.
        assertTrue(!type.isSynthetic, "SherpaGenerationCallback must not be a synthetic lambda class")
        assertTrue(
            Function1::class.java.isAssignableFrom(type),
            "SherpaGenerationCallback must still be a Function1 for the Tts.kt signature",
        )
    }

    @Test
    fun `forwards chunks to the consumer and asks sherpa-onnx to continue`() {
        val received = mutableListOf<FloatArray>()
        val callback = SherpaGenerationCallback { received += it }

        val first = floatArrayOf(0.1f, 0.2f)
        val second = floatArrayOf(0.3f)

        assertEquals(SherpaGenerationCallback.CONTINUE, callback.invoke(first))
        assertEquals(SherpaGenerationCallback.CONTINUE, callback.invoke(second))

        assertEquals(2, received.size)
        assertTrue(first.contentEquals(received[0]))
        assertTrue(second.contentEquals(received[1]))
    }

    @Test
    fun `continue and stop use sherpa-onnx's expected sentinel values`() {
        // Upstream treats non-zero as "keep going" and 0 as "stop".
        assertEquals(1, SherpaGenerationCallback.CONTINUE)
        assertEquals(0, SherpaGenerationCallback.STOP)
    }
}
