package com.termux.app.fleet

import android.content.Context

object NativeSessionSettings {
    private const val PREFERENCES = "agent-fleet-native-session"
    private const val KEY_ENABLED = "enabled"

    fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun conversationView(context: Context): ConversationView =
        if (preferences(context).getString("view", "conversation") == "detailed") ConversationView.Detailed else ConversationView.Conversation
    fun setConversationView(context: Context, view: ConversationView) {
        preferences(context).edit().putString("view", view.wire).apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
