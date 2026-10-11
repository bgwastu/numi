package net.wastu.numi.contracts

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.telecom.Call
import android.view.ViewGroup

/** Optional connector boundary: native messaging and calling work with no delegate installed. */
object NumiHooks {
    // True defers the native notification until durable screening has finished.
    var smsReceived: ((Context, Long, String, String, Int, String?) -> Boolean)? = null
    var spamIds: ((Context) -> Set<Long>)? = null
    var inboxSummary: ((Context, Long) -> InboxSummary?)? = null
    var openSpam: ((Activity) -> Unit)? = null
    var smsSending: ((Context, Uri, Int) -> Int)? = null
    var smsSent: ((Context, Uri, Int, Int, Int, Int) -> Int)? = null
    var smsFailed: ((Context, Uri, String) -> Unit)? = null
    var smsDelivered: ((Context, Uri, Int, Int, Int, Int) -> Int)? = null
    var callChanged: ((Context, Call, Int) -> Unit)? = null
    var callRemoved: ((Context, Call) -> Unit)? = null
    var openConnector: ((Activity) -> Unit)? = null
    var openCalls: ((Activity) -> Unit)? = null
    var openMessages: ((Activity) -> Unit)? = null
    var bindNavigation: ((Activity, ViewGroup, String) -> Unit)? = null
    var messagesOpened: ((Context) -> Unit)? = null
}

data class InboxSummary(val hidden: Boolean, val snippet: String = "", val date: Int = 0, val unread: Int = 0)
