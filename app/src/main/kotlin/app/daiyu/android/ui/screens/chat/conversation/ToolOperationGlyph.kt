package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONObject

/** Match protocol identifiers, never translated titles or result prose. */
internal fun toolOperationGlyph(toolName: String, arguments: String): String {
    val args = runCatching { JSONObject(arguments) }.getOrNull()
    return when (toolName) {
        "assistant_memory_search", "skills_list" -> "search"
        "assistant_memory_read", "skills_read" -> "book"
        "skills_read_resource" -> "resource"
        "run_child_models" -> "batch"
        "start_child_tasks" -> "launchBackground"
        "direct_child_task" -> when (args?.optString("kind")) {
            "APPEND" -> "add"; "REPLACE" -> "swap"; "REDISPATCH" -> "retry"; else -> "generic"
        }
        "terminal_exec", "terminal_pty_step", "shizuku_execute" -> "terminal"
        "terminal_read", "terminal_pty_read" -> "output"
        "terminal_job_start" -> "launchBackground"
        "terminal_job_read" -> "receive"
        "list_files" -> "list"
        "file_metadata" -> "inspect"
        "read_text_file" -> "file"
        "browser_read", "browser_write" -> when (args?.optString("action")) {
            "list_tabs", "new_tab", "close_tab" -> "tabs"
            "get_readable", "get_text" -> "article"
            "find_elements", "get_backbone" -> "element"
            "get_page_info", "navigate" -> "webpage"
            "screenshot" -> "webCamera"
            "click", "hover" -> "cursor"
            "type" -> "webInput"
            "scroll", "scroll_and_collect" -> "webScroll"
            "execute_js" -> "code"
            "fetch" -> "download"
            "get_cookies", "set_cookies" -> "cookie"
            "set_user_agent", "set_viewport" -> "settings"
            "wait_for_selector", "wait_for_dom_stable" -> "waitPage"
            "reload" -> "refresh"; "go_back" -> "history"; "go_forward" -> "send"
            else -> "generic"
        }
        "screen", "screen_action" -> when (args?.optString("operation")) {
            "observe" -> "screen"; "screenshot" -> "screenShot"
            "tap" -> "tap"; "long_press" -> "longPress"
            "scroll_forward", "scroll_backward" -> "scroll"
            "input" -> "keyboard"; "back", "home", "recents", "notifications" -> "systemKey"
            else -> "generic"
        }
        "r08_virtual_display_step" -> "device"
        "r08_virtual_display_read" -> "screen"
        "search_apps" -> "appSearch"
        "get_battery_status" -> "battery"
        "device_status" -> "chip"
        "network_status", "platform_set_wifi" -> "wifi"
        "device_time" -> "clock"
        "device_audio", "platform_set_volume" -> "volume"
        "device_environment" -> "device"
        "platform_permissions" -> "lock"
        "last_known_location" -> "pin"
        "search_media" -> if (args?.optString("kind") == "audio") "music" else "album"
        "calendar_events" -> "calendar"
        "search_contacts" -> "contacts"
        "app_notifications", "current_notifications" -> "bell"
        "notification_history", "context_archive_page" -> "history"
        "platform_set_alarm" -> "alarm"
        "platform_set_timer" -> "timer"
        "platform_media_key" -> "media"
        "generate_images" -> "create"
        "generate_videos" -> "videoCreate"
        "context_usage" -> "contextRead"
        "ask_user" -> "question"
        else -> if (toolName.matches(Regex("mcp_[0-7]_call"))) "toolCall" else "generic"
    }
}
