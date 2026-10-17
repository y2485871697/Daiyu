package app.daiyu.android.platform

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class PlatformManifestBoundaryTest {
    @Test fun readPermissionsAreMinimalVersionScopedAndNoPrivilegedSubstituteIsDeclared() {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val android = "http://schemas.android.com/apk/res/android"
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml"))
        val nodes = document.getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        val names = permissions.map { it.getAttributeNS(android, "name") }.toSet()
        assertTrue(names.containsAll(setOf("android.permission.ACCESS_NETWORK_STATE", "android.permission.READ_CONTACTS",
            "android.permission.READ_CALENDAR", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")))
        assertEquals("32", permissions.single { it.getAttributeNS(android, "name") == "android.permission.READ_EXTERNAL_STORAGE" }
            .getAttributeNS(android, "maxSdkVersion"))
        assertEquals("30", permissions.single { it.getAttributeNS(android, "name") == "android.permission.BLUETOOTH" }
            .getAttributeNS(android, "maxSdkVersion"))
        assertTrue(names.containsAll(setOf("android.permission.ACCESS_WIFI_STATE", "android.permission.BLUETOOTH_CONNECT")))
        assertFalse(names.any { it in setOf("android.permission.QUERY_ALL_PACKAGES", "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.WRITE_CONTACTS", "android.permission.WRITE_CALENDAR",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "android.permission.POST_NOTIFICATIONS") })
        assertEquals(20, permissions.size)
        assertTrue(names.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertTrue(names.containsAll(setOf("com.android.alarm.permission.SET_ALARM", "android.permission.MODIFY_AUDIO_SETTINGS")))
        assertEquals("28", permissions.single { it.getAttributeNS(android, "name") == "android.permission.CHANGE_WIFI_STATE" }
            .getAttributeNS(android, "maxSdkVersion"))
        assertFalse(names.any { it in setOf("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM",
            "android.permission.MEDIA_CONTENT_CONTROL", "android.permission.ACCESS_NOTIFICATION_POLICY") })
        val queries = document.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val visibleActions = (queries.item(0) as org.w3c.dom.Element).getElementsByTagName("action")
        assertEquals(setOf("android.intent.action.SET_ALARM", "android.intent.action.SET_TIMER",
            "android.intent.action.TTS_SERVICE", "android.speech.RecognitionService"),
            (0 until visibleActions.length).map { (visibleActions.item(it) as org.w3c.dom.Element)
                .getAttributeNS(android, "name") }.toSet())
        val services = document.getElementsByTagName("service")
        val listener = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".platform.CrossAppNotificationListener" }
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.getAttributeNS(android, "permission"))
        assertEquals("true", listener.getAttributeNS(android, "exported"))
        assertEquals("", listener.getAttributeNS(android, "process"))
        val actions = listener.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals("android.service.notification.NotificationListenerService",
            (actions.item(0) as org.w3c.dom.Element).getAttributeNS(android, "name"))
    }
}
