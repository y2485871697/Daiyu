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

    @Suppress("UNCHECKED_CAST")
    fun testRemoteFinalCallbackPreservesCapturedReverseSelectionWithoutNetworking() {
        setDraft("leftRIGHTtail")
        setSelection(9, 4)
        mainThread {
            val target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", target)
            val host = (value("remoteVoice\$delegate") as Lazy<*>).value!!
            val apply = host.javaClass.getDeclaredField("applyDraft").apply { isAccessible = true }
                .get(host) as (app.daiyu.android.voice.DictationDraftTarget, String) -> Unit
            apply(target, "middle")
        }
        assertDraft("leftmiddletail")
        assertSelection(10, 10)
        assertEquals(0, fixture.opens.get())
    }

    fun testRecognizedTextKeepsReferencesOutOfTheEditableBody() {
        val reference = "路径引用（未读取）：/workspace/keep.txt"
        mainThread {
            retainedControls(activity).onTextChange(reference)
            val target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", target)
            invoke("applyRecognizedDraft", target, "听写正文")
        }
        assertDraft("听写正文")
        assertSelection(4, 4)
        assertEquals("听写正文\n$reference", fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    @Suppress("UNCHECKED_CAST")
    fun testOfflineCallbackUsesVisibleBodyAndRetainsLiteralReferences() {
        val reference = "文件引用（未读取）：content://never-read/document/one"
        mainThread {
            retainedControls(activity).onTextChange("旧正文\n$reference")
            val host = (value("offlineHost\$delegate") as Lazy<*>).value!!
            val current = host.javaClass.getDeclaredField("currentText").apply { isAccessible = true }.get(host) as () -> String
            assertEquals("旧正文", current())
            val apply = host.javaClass.getDeclaredField("applyDraft").apply { isAccessible = true }.get(host)
                as (app.daiyu.android.voice.DictationDraftTarget, String, Int) -> Unit
            val target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            apply(target, "新正文", 3)
        }
        assertDraft("新正文")
        assertSelection(3, 3)
        assertEquals("新正文\n$reference", fixture.database.draft())
        activity = recreateActivity(activity)
        assertDraft("新正文")
        assertEquals("新正文\n$reference", fixture.database.draft())
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
