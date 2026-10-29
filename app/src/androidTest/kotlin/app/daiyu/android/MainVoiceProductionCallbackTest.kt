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

    @Suppress("UNCHECKED_CAST")
    fun testRemotePartialsPersistAndFinalPreservesReferencesWithoutNetworking() {
        val reference = "路径引用（未读取）：/workspace/keep.txt"
        lateinit var target: app.daiyu.android.voice.DictationDraftTarget
        lateinit var preview: kotlinx.coroutines.flow.MutableStateFlow<Pair<app.daiyu.android.voice.DictationDraftTarget, String>?>
        mainThread { retainedControls(activity).onTextChange("旧正文\n$reference") }
        setSelection(0, 3)
        mainThread {
            target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", target)
            val host = (value("remoteVoice\$delegate") as Lazy<*>).value!!
            preview = host.javaClass.getDeclaredField("mutablePreview").apply { isAccessible = true }.get(host)
                as kotlinx.coroutines.flow.MutableStateFlow<Pair<app.daiyu.android.voice.DictationDraftTarget, String>?>
            invoke("applyRecognizedPartial", target, "语音")
            preview.value = target to "语音"
        }
        assertDraft("语音")
        assertSelection(2, 2)
        assertEquals("语音\n$reference", fixture.database.draft())
        mainThread { invoke("applyRecognizedPartial", target, "语音修订"); preview.value = target to "语音修订" }
        assertDraft("语音修订")
        assertEquals("语音修订\n$reference", fixture.database.draft())
        mainThread { invoke("applyRecognizedDraft", target, "语音终稿") }
        assertDraft("语音终稿")
        assertEquals("语音终稿\n$reference", fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    @Suppress("UNCHECKED_CAST")
    fun testRemotePartialEditBackToOriginalImmediatelyRetiresItsPreview() {
        setDraft("original")
        setSelection(0, 8)
        lateinit var target: app.daiyu.android.voice.DictationDraftTarget
        lateinit var preview: kotlinx.coroutines.flow.MutableStateFlow<Pair<app.daiyu.android.voice.DictationDraftTarget, String>?>
        mainThread {
            target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", target)
            val host = (value("remoteVoice\$delegate") as Lazy<*>).value!!
            preview = host.javaClass.getDeclaredField("mutablePreview").apply { isAccessible = true }.get(host)
                as kotlinx.coroutines.flow.MutableStateFlow<Pair<app.daiyu.android.voice.DictationDraftTarget, String>?>
            invoke("applyRecognizedPartial", target, "临时识别")
            preview.value = target to "临时识别"
        }
        assertDraft("临时识别")
        setDraft("original")
        mainThread {
            assertNull(preview.value)
            assertNull(value("dictationDraft"))
            try { invoke("applyRecognizedDraft", target, "late final"); fail("retired target accepted") }
            catch (_: IllegalStateException) { }
        }
        assertDraft("original")
        assertEquals("original", fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    @Suppress("UNCHECKED_CAST")
    fun testSystemPartialPersistsAndSurvivesEditorDisposalWithoutSending() {
        setDraft("leftOLDtail")
        setSelection(4, 7)
        mainThread {
            val target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", target)
            val host = (value("dictationHost\$delegate") as Lazy<*>).value!!
            val state = host.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }.get(host)
                as kotlinx.coroutines.flow.MutableStateFlow<app.daiyu.android.voice.ComposerDictationState>
            invoke("applyRecognizedPartial", target, "系统识别")
            state.value = app.daiyu.android.voice.ComposerDictationState(target, preview = "系统识别")
        }
        assertDraft("left系统识别tail")
        assertSelection(8, 8)
        assertEquals("left系统识别tail", fixture.database.draft())
        mainThread { activity.select(null) }
        assertDraft("left系统识别tail")
        assertEquals(0, fixture.opens.get())
    }

    fun testOwnedPartialsAdvanceRevisionButManualEditsRetireEveryOldCallback() {
        setDraft("leftOLDtail")
        setSelection(7, 4)
        lateinit var target: app.daiyu.android.voice.DictationDraftTarget
        mainThread {
            target = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", target)
            invoke("applyRecognizedPartial", target, "你")
            assertTrue((value("draftRevision") as Long) > target.draftRevision)
            invoke("applyRecognizedPartial", target, "你好")
            invoke("applyRecognizedPartial", target, "您好")
        }
        assertDraft("left您好tail")
        assertSelection(6, 6)
        assertEquals("left您好tail", fixture.database.draft())
        mainThread { invoke("invalidateConfiguredVoice") }
        assertDraft("left您好tail")
        mainThread {
            val next = app.daiyu.android.voice.DictationDraftTarget(null,
                value("editorGeneration") as Long, value("draftRevision") as Long)
            invoke("captureDictationDraft", next)
            invoke("applyRecognizedPartial", next, "世界")
        }
        assertDraft("left您好世界tail")
        setDraft("manual edit")
        mainThread {
            try { invoke("applyRecognizedPartial", target, "late"); fail("retired partial accepted") }
            catch (_: IllegalStateException) { }
            try { invoke("applyRecognizedDraft", target, "late final"); fail("retired final accepted") }
            catch (_: IllegalStateException) { }
        }
        assertDraft("manual edit")
        assertEquals("manual edit", fixture.database.draft())
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
