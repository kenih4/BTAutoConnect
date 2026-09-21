package com.example.btautoconnect

import android.app.Activity
import android.text.Html
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Microsoft Graph APIでTeamsチャンネルの最新メッセージを取得する。 */
class TeamsChannelReader(private val activity: Activity) {

    data class Message(val sender: String, val text: String)

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scopes = listOf("Team.ReadBasic.All", "Channel.ReadBasic.All", "ChannelMessage.Read.All")

    fun fetch(
        teamName: String,
        channelName: String,
        onSignInLaunch: () -> Unit,
        onResult: (Result<List<Message>>) -> Unit
    ) {
        val fail = { e: Exception -> activity.runOnUiThread { onResult(Result.failure(e)) } }
        withApp(fail) { app ->
            acquireToken(app, onSignInLaunch, fail) { token ->
                executor.execute {
                    val result = runCatching { loadMessages(token, teamName, channelName) }
                    activity.runOnUiThread { onResult(result) }
                }
            }
        }
    }

    // ---------- 認証 ----------

    private fun withApp(fail: (Exception) -> Unit, ready: (ISingleAccountPublicClientApplication) -> Unit) {
        msalApp?.let { ready(it); return }
        PublicClientApplication.createSingleAccountPublicClientApplication(
            activity.applicationContext,
            R.raw.auth_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalApp = application
                    ready(application)
                }

                override fun onError(exception: MsalException) = fail(exception)
            }
        )
    }

    private fun acquireToken(
        app: ISingleAccountPublicClientApplication,
        onSignInLaunch: () -> Unit,
        fail: (Exception) -> Unit,
        succeed: (String) -> Unit
    ) {
        fun interactive() {
            onSignInLaunch()
            val params = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(scopes)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) =
                        succeed(authenticationResult.accessToken)

                    override fun onError(exception: MsalException) = fail(exception)
                    override fun onCancel() = fail(IOException("サインインがキャンセルされました"))
                })
                .build()
            app.acquireToken(params)
        }

        fun silent(account: IAccount) {
            val params = AcquireTokenSilentParameters.Builder()
                .forAccount(account)
                .fromAuthority(account.authority)
                .withScopes(scopes)
                .withCallback(object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) =
                        succeed(authenticationResult.accessToken)

                    override fun onError(exception: MsalException) = interactive()
                })
                .build()
            app.acquireTokenSilentAsync(params)
        }

        app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount != null) silent(activeAccount) else interactive()
            }

            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {}
            override fun onError(exception: MsalException) = fail(exception)
        })
    }

    // ---------- Graph API ----------

    private fun loadMessages(token: String, teamName: String, channelName: String): List<Message> {
        val teams = get("$GRAPH/me/joinedTeams", token).getJSONArray("value")
        val teamId = findId(teams, teamName)
            ?: throw IOException("チーム「$teamName」が見つかりません")

        val channels = get("$GRAPH/teams/${enc(teamId)}/channels", token).getJSONArray("value")
        val channelId = findId(channels, channelName)
            ?: throw IOException("チャンネル「$channelName」が見つかりません")

        val items = get(
            "$GRAPH/teams/${enc(teamId)}/channels/${enc(channelId)}/messages?\$top=$FETCH_COUNT",
            token
        ).getJSONArray("value")

        val messages = mutableListOf<Message>()
        for (i in 0 until items.length()) {
            val m = items.getJSONObject(i)
            if (m.optString("messageType") != "message" || !m.isNull("deletedDateTime")) continue
            val body = m.optJSONObject("body") ?: continue
            val raw = body.optString("content")
            val text = if (body.optString("contentType") == "html") {
                Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                raw.trim()
            }
            if (text.isEmpty()) continue
            val from = m.optJSONObject("from")
            val sender = from?.optJSONObject("user")?.optString("displayName")
                ?: from?.optJSONObject("application")?.optString("displayName")
                ?: "不明"
            messages.add(Message(sender, text))
            if (messages.size >= READ_COUNT) break
        }
        return messages.reversed()
    }

    private fun findId(items: org.json.JSONArray, name: String): String? {
        val list = (0 until items.length()).map { items.getJSONObject(it) }
        val hit = list.firstOrNull { it.optString("displayName").equals(name, ignoreCase = true) }
            ?: list.firstOrNull { it.optString("displayName").contains(name, ignoreCase = true) }
        return hit?.optString("id")
    }

    private fun get(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrDefault(body.take(200))
                throw IOException("Graph APIエラー($code): $detail")
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val FETCH_COUNT = 15
        private const val READ_COUNT = 5
    }
}
