package app.daiyu.android.model.usage

import app.daiyu.android.domain.ModelPort
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.UsageStatisticsStore

/** Installed once by the Application; test transports retain their existing constructor seams. */
internal object UsageRecorder {
    @Volatile private var database: ConversationDatabase? = null
    fun install(store: ConversationDatabase) { database = store }
    fun wrap(model: ModelPort, snapshot: ProviderSnapshot, conversation: String? = null, inferConversation: Boolean = true): ModelPort {
        val store = database ?: return model
        val identity = snapshot.usageIdentity ?: return model
        val options = snapshot.requestOptions.customBody
        val wireModel = if (options.isEmpty()) snapshot.model else
            app.daiyu.android.model.MessagesRequest.jsonObject(options).opt("model") as? String ?: snapshot.model
        val modelIdentity = UsageStatisticsStore.ModelIdentity(identity.providerId, identity.providerName, wireModel, wireModel)
        return UsageRecordingModel(model, { id, owner, at, fields ->
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                UsageStatisticsStore.record(db, id, owner, modelIdentity, at, fields, snapshot.contextWindowOverride?.toLong())
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }, conversation, inferConversation = inferConversation)
    }
}
