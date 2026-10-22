package app.daiyu.android.pet

internal fun logPetError(message: String) {
    android.util.Log.e("DaiyuPet", message)
}

internal class PetErrorLog(private val write: (String) -> Unit = ::logPetError) {
    private var previous: String? = null

    @Synchronized fun update(error: String?) {
        if (error == previous) return
        previous = error
        if (error != null) write(error)
    }
}
