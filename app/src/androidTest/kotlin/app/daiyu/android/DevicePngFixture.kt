package app.daiyu.android

/** Static 1x1 RGBA PNG; each caller receives its own bytes for mutation tests. */
internal fun validDeviceTestPng(): ByteArray = java.util.Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQckn7DwADJAHMYmdIlAAAAABJRU5ErkJggg==")
