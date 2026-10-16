package app.daiyu.android

/** Production Main callbacks only. No microphone permission, speech synthesis or paid request. */
class MainVoiceProductionCallbackTest : WiredActivityTestCase() {
    private fun value(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun invoke(name: String, vararg arguments: Any?) {
        val method = MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == arguments.size
        }
        try { method.apply { isAccessible = true }.invoke(activity, *arguments) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }

    fun testRejectedSpeechCallbackCannotInvalidateNewVoiceRequest() {
        mainThread {
            val before = value("voiceRequestEpoch")
            invoke("requestConfiguredSpeech", "retired-owner", "missing-answer", "not displayed")
            assertEquals(before, value("voiceRequestEpoch"))
            assertNull(value("voiceConsent"))
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testStaleDictationCallbackCannotInvalidateCurrentEditor() {
        lateinit var oldVoice: () -> Unit
        mainThread {
            oldVoice = retainedControls(activity).onVoice
            activity.select(null)
        }
        mainThread {
            val before = value("voiceRequestEpoch")
            oldVoice()
            assertEquals(before, value("voiceRequestEpoch"))
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testNavigationRetiresCascadeAndPreparedSpeechEpochs() {
        var before = 0L
        var cascadeBefore = 0L
        lateinit var cascade: ActivityCascadeVoiceHost
        mainThread {
            val field = MainActivity::class.java.getDeclaredField("cascadeVoice\$delegate").apply { isAccessible = true }
            cascade = (field.get(activity) as Lazy<*>).value as ActivityCascadeVoiceHost
            before = value("voiceRequestEpoch") as Long
            cascadeBefore = cascade.operationToken
            activity.select(null)
            assertTrue((value("voiceRequestEpoch") as Long) > before)
            assertTrue(cascade.operationToken > cascadeBefore)
        }
        assertEquals(0, fixture.opens.get())
    }
}
